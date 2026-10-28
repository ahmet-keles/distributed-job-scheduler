# AWS EC2 deployment template

This Terraform template provisions one `t4g.small` (ARM64) EC2 instance in
`us-east-2`, using the account's default VPC and one of its subnets. Amazon
Linux 2023 installs Docker and Docker Compose, then starts this repository's
`compose.yaml` (scheduler and PostgreSQL). It creates one security group:
SSH is limited to `my_ip`, and API port 8080 defaults to the same `my_ip/32`
CIDR. The optional `api_allowed_cidr` variable can allow another IPv4 CIDR.
There is no RDS, load balancer, NAT gateway, high availability, or
automated database backup. Use it for a small demo, not production workloads.

## Prerequisites

- Terraform 1.6 or newer, AWS CLI, and an AWS CLI profile with permission to
  create EC2 instances, security groups, and read the public SSM AMI parameter.
- An initialized default VPC with a public subnet in `us-east-2`.
- Your public IPv4 address and a long random PostgreSQL password.
- No local Java or Docker installation is needed; the instance clones the
  public GitHub repository and builds the scheduler image on first boot.
  Both `eclipse-temurin:21-jdk` and
  `eclipse-temurin:21-jre` base images, and `postgres:17-alpine`, publish
  ARM64 variants.

Terraform uses the normal AWS credential chain. Select your existing local
profile with `export AWS_PROFILE=your-profile-name`; no access key is stored
in this repository or passed to Terraform.

Copy the example variable file, restrict its permissions, and replace both
example values with your own values:

```sh
cd deploy/aws
cp terraform.tfvars.example terraform.tfvars
chmod 600 terraform.tfvars
```

`my_ip` is an IPv4 address without `/32`; `postgres_password` must be at least
16 characters and use only letters, digits, underscores, or hyphens (for
example, generate a hexadecimal value with `openssl rand -hex 24`). The tfvars
file and Terraform state contain the database password and must stay private.
They are git-ignored.
The password is also passed through EC2 user data to initialize Compose; AWS
principals allowed to read instance user data can see it. The API has no
authentication; the default CIDR restriction limits who can reach it. Use the
temporary public override only as described below, and destroy the deployment
when finished.

### Temporarily open the API for a demo

The API has no authentication. Keep the default `my_ip/32` restriction for
normal use. If a demo needs public access, temporarily add this line to your
local `terraform.tfvars`, then review and apply the security-group change:

```hcl
api_allowed_cidr = "0.0.0.0/0"
```

```sh
terraform plan -var-file=terraform.tfvars
terraform apply -var-file=terraform.tfvars
```

This allows anyone on the internet to reach the API while it is set. As soon
as the demo ends, remove the `api_allowed_cidr` line (or replace it with a
narrower IPv4 CIDR), then run the same `plan` and `apply` commands to restore
the default restriction. Do not leave public access enabled.

## Initialize, plan, apply, destroy

Run these commands from `deploy/aws` after setting `AWS_PROFILE` and editing
`terraform.tfvars`:

```sh
terraform init
terraform plan -var-file=terraform.tfvars
terraform apply -var-file=terraform.tfvars
terraform destroy -var-file=terraform.tfvars
```

Review the plan before applying. First boot clones the repository and builds
the application image, so it requires outbound internet access and can take
several minutes. To inspect startup over SSH, use the public IP from
Terraform output and run `sudo tail -f /var/log/cloud-init-output.log` or
`cd /opt/job-scheduler && sudo docker compose ps`.

## Check the deployment

Get the IP from `terraform output -raw instance_public_ip`, wait for cloud-init
to finish, then check the health endpoint:

```sh
curl "http://$(terraform output -raw instance_public_ip):8080/actuator/health"
```

Create a no-op test job (the response includes its id):

```sh
PUBLIC_IP="$(terraform output -raw instance_public_ip)"
curl -sS -X POST "http://${PUBLIC_IP}:8080/api/jobs" \
  -H 'Content-Type: application/json' \
  -d '{"type":"noop","payload":"{\"demo\":true}"}'
```

Check its state and attempt history at `http://PUBLIC_IP:8080/api/jobs/JOB_ID`.

## Stop and start to save compute cost

Stopping the instance halts compute charges; the 20 GB EBS volume remains and
continues to incur storage cost. The public IPv4 address changes when the
instance starts again. From this directory, use:

```sh
INSTANCE_ID="$(terraform output -raw instance_id)"
aws ec2 stop-instances --region us-east-2 --instance-ids "$INSTANCE_ID"
aws ec2 start-instances --region us-east-2 --instance-ids "$INSTANCE_ID"
aws ec2 describe-instances --region us-east-2 --instance-ids "$INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
```

The last command reports the current IP after start. Update `my_ip` and run
`terraform apply` if your own IP changed and SSH access needs updating.
PostgreSQL data persists on the instance volume across stop/start. `terraform
destroy` deletes the instance and its root volume, so export or back up data
before destroying if you need to keep it.

## Monthly cost estimate

At 730 hours/month, estimate about **$19.70/month** while running continuously:
approximately $12.26 for Linux On-Demand `t4g.small` ($0.0168/hour), $1.60
for 20 GB gp3 ($0.08/GB-month), and $5.84 for one public IPv4 address
($0.008/hour). This excludes data transfer, snapshots, taxes, and any surplus
T-family CPU credits. Stopping saves the instance charge; the EBS volume
continues to cost about $1.60/month. Prices vary; verify current rates with
the [AWS EC2 pricing](https://aws.amazon.com/ec2/pricing/on-demand/) and
[EBS pricing](https://aws.amazon.com/ebs/pricing/) pages and
[VPC pricing](https://aws.amazon.com/vpc/pricing/) before applying.
