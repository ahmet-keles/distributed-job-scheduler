variable "aws_region" {
  description = "AWS region for the single EC2 deployment."
  type        = string
  default     = "us-east-2"
}

variable "my_ip" {
  description = "Your public IPv4 address, without a CIDR suffix, for SSH access."
  type        = string

  validation {
    condition     = can(cidrhost("${var.my_ip}/32", 0))
    error_message = "my_ip must be a valid IPv4 address without a CIDR suffix."
  }
}

variable "api_allowed_cidr" {
  description = "Optional IPv4 CIDR allowed to reach the API; defaults to my_ip/32."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.api_allowed_cidr == null ? true : can(cidrnetmask(var.api_allowed_cidr))
    error_message = "api_allowed_cidr must be a valid IPv4 CIDR, such as 203.0.113.0/24."
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
