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
