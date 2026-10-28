variable "aws_region" {
  description = "AWS region for the single EC2 deployment."
  type        = string
  default     = "us-east-1"
}

variable "my_ip" {
  description = "Your public IPv4 address, without a CIDR suffix, for SSH access."
  type        = string

  validation {
    condition     = can(cidrhost("${var.my_ip}/32", 0))
    error_message = "my_ip must be a valid IPv4 address without a CIDR suffix."
  }
}

variable "postgres_password" {
  description = "Password for the PostgreSQL application user. Keep the local tfvars and state private."
  type        = string
  sensitive   = true

  validation {
    condition     = can(regex("^[A-Za-z0-9_-]{16,}$", var.postgres_password))
    error_message = "postgres_password must be at least 16 characters and use only letters, digits, underscore, or hyphen."
  }
}
