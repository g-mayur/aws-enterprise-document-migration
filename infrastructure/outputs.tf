output "job_state_table" {
  description = "DynamoDB table storing migration job and shard lifecycle state."
  value       = aws_dynamodb_table.job_state.name
}
