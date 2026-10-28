output "instance_id" {
  description = "EC2 instance ID, used by the start/stop commands."
  value       = aws_instance.scheduler.id
}

output "instance_public_ip" {
  description = "Public IPv4 address for the API and SSH. It changes after stop/start."
  value       = aws_instance.scheduler.public_ip
}

output "api_url" {
  description = "Scheduler API base URL."
  value       = "http://${aws_instance.scheduler.public_ip}:8080"
}
