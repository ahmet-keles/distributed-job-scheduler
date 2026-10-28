data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

locals {
  api_allowed_cidr = var.api_allowed_cidr == null ? "${var.my_ip}/32" : var.api_allowed_cidr
}

resource "aws_security_group" "scheduler" {
  name_prefix = "job-scheduler-"
  description = "SSH and scheduler API access restricted by configured IPv4 CIDRs."
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH from the configured owner IP only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["${var.my_ip}/32"]
  }

  ingress {
    description = "Scheduler REST API"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [local.api_allowed_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "scheduler" {
  ami                         = data.aws_ssm_parameter.al2023_arm64.value
  instance_type               = "t4g.small"
  subnet_id                   = sort(data.aws_subnets.default.ids)[0]
  vpc_security_group_ids      = [aws_security_group.scheduler.id]
  associate_public_ip_address = true
  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    postgres_password_b64 = base64encode(var.postgres_password)
  })
  user_data_replace_on_change = true

  root_block_device {
    volume_size           = 20
    volume_type           = "gp3"
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "distributed-job-scheduler"
  }
}
