variable "aws_region" {
  description = "AWS Region for the bootstrap environment."
  type        = string
  default     = "us-east-1"
}
variable "project_name" {
  description = "Short project identifier."
  type        = string
  default     = "document-migration"
}
variable "environment" {
  description = "Environment tag."
  type        = string
  default     = "dev"
}
variable "vpc_cidr" {
  description = "Non-overlapping IPv4 CIDR for the dedicated VPC."
  type        = string
  default     = "10.40.0.0/16"
}
variable "private_subnet_cidrs" {
  description = "Two non-overlapping private subnet CIDRs within the VPC."
  type        = list(string)
  default     = ["10.40.1.0/24", "10.40.2.0/24"]
  validation {
    condition     = length(var.private_subnet_cidrs) == 2
    error_message = "Exactly two private subnet CIDRs are required."
  }
}
variable "ecr_repository_name" {
  description = "Private ECR repository for the migration worker."
  type        = string
  default     = "document-migration-worker"
}
variable "ecr_image_retention_count" {
  description = "Number of tagged images retained by ECR lifecycle policy."
  type        = number
  default     = 20
  validation {
    condition     = var.ecr_image_retention_count >= 1
    error_message = "At least one tagged image must be retained."
  }
}
