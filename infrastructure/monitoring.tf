locals {
  monitoring_alarm_actions = var.monitoring_alarm_actions
  monitoring_tables = {
    document_state = aws_dynamodb_table.state.name
    job_state      = aws_dynamodb_table.job_state.name
  }

  migration_dashboard_body = {
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6,
        properties = {
          title = "Step Functions executions"
          view = "timeSeries", stacked = false, region = var.aws_region, period = 300
          metrics = [
            ["AWS/States", "ExecutionsStarted", "StateMachineArn", aws_sfn_state_machine.migration.arn],
            [".", "ExecutionsSucceeded", ".", "."],
            [".", "ExecutionsFailed", ".", "."],
            [".", "ExecutionsTimedOut", ".", "."]
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6,
        properties = {
          title = "Worker cluster utilization"
          view = "timeSeries", stacked = false, region = var.aws_region, period = 300
          metrics = [
            ["AWS/ECS", "CPUUtilization", "ClusterName", aws_ecs_cluster.migration.name],
            [".", "MemoryUtilization", ".", "."]
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6,
        properties = {
          title = "DynamoDB throttled requests"
          view = "timeSeries", stacked = false, region = var.aws_region, period = 300
          metrics = [
            for table_name in values(local.monitoring_tables) : [
              "AWS/DynamoDB", "ReadThrottleEvents", "TableName", table_name
            ]
          ]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6,
        properties = {
          title = "DynamoDB write throttling"
          view = "timeSeries", stacked = false, region = var.aws_region, period = 300
          metrics = [
            for table_name in values(local.monitoring_tables) : [
              "AWS/DynamoDB", "WriteThrottleEvents", "TableName", table_name
            ]
          ]
        }
      }
    ]
  }
}

resource "aws_cloudwatch_metric_alarm" "step_functions_failed" {
  alarm_name          = "${var.project_name}-step-functions-executions-failed"
  alarm_description   = "One or more migration state machine executions failed in the evaluation period."
  namespace           = "AWS/States"
  metric_name         = "ExecutionsFailed"
  statistic           = "Sum"
  period              = var.monitoring_period_seconds
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions = {
    StateMachineArn = aws_sfn_state_machine.migration.arn
  }
  alarm_actions = local.monitoring_alarm_actions
  ok_actions    = local.monitoring_alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "step_functions_timed_out" {
  alarm_name          = "${var.project_name}-step-functions-executions-timed-out"
  alarm_description   = "One or more migration state machine executions timed out in the evaluation period."
  namespace           = "AWS/States"
  metric_name         = "ExecutionsTimedOut"
  statistic           = "Sum"
  period              = var.monitoring_period_seconds
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions = {
    StateMachineArn = aws_sfn_state_machine.migration.arn
  }
  alarm_actions = local.monitoring_alarm_actions
  ok_actions    = local.monitoring_alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ecs_cpu_high" {
  alarm_name          = "${var.project_name}-worker-cpu-high"
  alarm_description   = "Average ECS worker cluster CPU utilization is above the configured threshold."
  namespace           = "AWS/ECS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = var.monitoring_period_seconds
  evaluation_periods  = var.monitoring_evaluation_periods
  threshold           = var.monitoring_cpu_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions = {
    ClusterName = aws_ecs_cluster.migration.name
  }
  alarm_actions = local.monitoring_alarm_actions
  ok_actions    = local.monitoring_alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "ecs_memory_high" {
  alarm_name          = "${var.project_name}-worker-memory-high"
  alarm_description   = "Average ECS worker cluster memory utilization is above the configured threshold."
  namespace           = "AWS/ECS"
  metric_name         = "MemoryUtilization"
  statistic           = "Average"
  period              = var.monitoring_period_seconds
  evaluation_periods  = var.monitoring_evaluation_periods
  threshold           = var.monitoring_memory_threshold
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions = {
    ClusterName = aws_ecs_cluster.migration.name
  }
  alarm_actions = local.monitoring_alarm_actions
  ok_actions    = local.monitoring_alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "dynamodb_read_throttles" {
  for_each = local.monitoring_tables

  alarm_name          = "${var.project_name}-${each.key}-read-throttles"
  alarm_description   = "DynamoDB read requests for ${each.value} are being throttled."
  namespace           = "AWS/DynamoDB"
  metric_name         = "ReadThrottleEvents"
  statistic           = "Sum"
  period              = var.monitoring_period_seconds
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions = {
    TableName = each.value
  }
  alarm_actions = local.monitoring_alarm_actions
  ok_actions    = local.monitoring_alarm_actions
}

resource "aws_cloudwatch_metric_alarm" "dynamodb_write_throttles" {
  for_each = local.monitoring_tables

  alarm_name          = "${var.project_name}-${each.key}-write-throttles"
  alarm_description   = "DynamoDB write requests for ${each.value} are being throttled."
  namespace           = "AWS/DynamoDB"
  metric_name         = "WriteThrottleEvents"
  statistic           = "Sum"
  period              = var.monitoring_period_seconds
  evaluation_periods  = 1
  threshold           = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  dimensions = {
    TableName = each.value
  }
  alarm_actions = local.monitoring_alarm_actions
  ok_actions    = local.monitoring_alarm_actions
}

resource "aws_cloudwatch_dashboard" "migration" {
  dashboard_name = "${var.project_name}-operations"
  dashboard_body = jsonencode(local.migration_dashboard_body)
}
