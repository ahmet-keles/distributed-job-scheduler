# AWS deployment walkthrough

1. Terraform selects the account's default VPC and an available subnet in `us-east-2`.
2. It reads the current Amazon Linux 2023 ARM64 AMI ID from the public SSM parameter.
3. The instance type is one `t4g.small`, with a 20 GB encrypted gp3 root disk.
4. One security group allows SSH from the configured `my_ip` only.
5. The API on port 8080 defaults to `my_ip/32`; an optional IPv4 CIDR variable can override it.
6. The instance receives a public IPv4 address; there is no NAT, RDS, or load balancer.
7. User data installs Docker and the ARM64 Docker Compose plugin.
8. User data clones the public repository and starts its existing `compose.yaml`.
9. The PostgreSQL password is supplied as a sensitive Terraform variable.
10. Compose starts PostgreSQL, waits for its health check, then builds and starts the scheduler.
11. The app and Postgres images include ARM64 builds, matching the Graviton instance.
12. Health is checked at `/actuator/health`; a no-op job can verify the job API.
13. Stopping preserves the database disk but the public IP changes after restart.

## Interview questions

**Why use a default VPC?** It avoids provisioning extra network resources for a single-instance demo and keeps the template small.

**How is SSH access limited?** The security group allows TCP port 22 only from the IPv4 CIDR formed from `my_ip`.

**How can a demo temporarily allow outside API access?** Set `api_allowed_cidr` to `0.0.0.0/0`, apply the security-group update, then remove the override and apply again immediately after the demo.
