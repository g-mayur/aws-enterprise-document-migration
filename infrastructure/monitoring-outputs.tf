output "migration_dashboard_name" {
  description = "CloudWatch dashboard for migration orchestration, worker utilization and DynamoDB throttling."
  value       = aws_cloudwatch_dashboard.migration.dashboard_name
}

output "migration_alarm_names" {
  description = "CloudWatch alarms created for orchestration failures, worker utilization and DynamoDB throttling."
  value = concat(
    [
      aws_cloudwatch_metric_alarm.step_functions_failed.alarm_name,
      aws_cloudwatch_metric_alarm.step_functions_timed_out.alarm_name,
      aws_cloudwatch_metric_alarm.ecs_cpu_high.alarm_name,
      aws_cloudwatch_metric_alarm.ecs_memory_high.alarm_name
    ],
    [for alarm in values(aws_cloudwatch_metric_alarm.dynamodb_read_throttles) : alarm.alarm_name],
    [for alarm in values(aws_cloudwatch_metric_alarm.dynamodb_write_throttles) : alarm.alarm_name]
  )
}
