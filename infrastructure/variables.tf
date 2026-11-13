variable "aws_region" {
  type    = string
  default = "us-east-1"
}
variable "project_name" {
  type    = string
  default = "document-migration"
}
variable "staging_bucket_name" {
  type = string
}
variable "destination_bucket_name" {
  type = string
}
variable "state_table_name" {
  type    = string
  default = "document-migration-state"
}
variable "batch_table_name" {
  type    = string
  default = "document-migration-batches"
}

variable "job_state_table_name" {
  type    = string
  default = "document-migration-job-state"
}

variable "worker_image" {
  description = "Immutable container image URI for the migration worker."
  type        = string
}

variable "worker_subnet_ids" {
  description = "Private subnets for Fargate workers. Provide NAT or VPC endpoints for required AWS services."
  type        = list(string)
}

variable "worker_security_group_ids" {
  description = "Security groups attached to Fargate workers."
  type        = list(string)
}

variable "source_bucket_arn" {
  description = "Optional source S3 bucket ARN granted read access to workers."
  type        = string
  default     = null
}

variable "assign_public_ip" {
  description = "Assign public IPs to Fargate tasks. Prefer false with private subnets and VPC endpoints/NAT."
  type        = bool
  default     = false
}

variable "worker_cpu" {
  type    = number
  default = 1024
}

variable "worker_memory" {
  type    = number
  default = 2048
}

variable "max_concurrent_shards" {
  description = "Maximum number of shard tasks executed concurrently by the Distributed Map."
  type        = number
  default     = 10
}

variable "worker_log_retention_days" {
  type    = number
  default = 30
}
