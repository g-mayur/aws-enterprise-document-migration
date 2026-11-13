output "job_state_table" {
  description = "DynamoDB table storing migration job and shard lifecycle state."
  value       = aws_dynamodb_table.job_state.name
}

output "worker_cluster_arn" {
  description = "ECS cluster hosting on-demand shard workers."
  value       = aws_ecs_cluster.migration.arn
}

output "worker_task_definition_arn" {
  description = "Fargate task definition used by the shard orchestration workflow."
  value       = aws_ecs_task_definition.worker.arn
}

output "orchestration_state_machine_arn" {
  description = "Standard Step Functions state machine ARN."
  value       = aws_sfn_state_machine.migration.arn
}
