output "vpc_id" {
  description = "Dedicated migration VPC ID."
  value       = aws_vpc.migration.id
}

output "private_subnet_ids" {
  description = "Private subnet IDs across two Availability Zones."
  value       = [for key in sort(keys(aws_subnet.private)) : aws_subnet.private[key].id]
}

output "worker_subnet_ids" {
  description = "Private subnet IDs in the exact list format consumed by infrastructure/worker_subnet_ids."
  value       = [for key in sort(keys(aws_subnet.private)) : aws_subnet.private[key].id]
}

output "worker_security_group_id" {
  description = "Outbound-only worker security group ID."
  value       = aws_security_group.worker.id
}

output "worker_security_group_ids" {
  description = "Worker security group IDs in the exact list format consumed by infrastructure/worker_security_group_ids."
  value       = [aws_security_group.worker.id]
}

output "ecr_repository_url" {
  description = "Private ECR repository URL for the worker image."
  value       = aws_ecr_repository.worker.repository_url
}

output "aws_region" {
  description = "AWS Region selected for bootstrap."
  value       = var.aws_region
}
