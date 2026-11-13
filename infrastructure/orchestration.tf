locals {
  worker_source_bucket_resources = var.source_bucket_arn == null ? [] : [
    "${var.source_bucket_arn}/*"
  ]
}

resource "aws_ecs_cluster" "migration" {
  name = "${var.project_name}-workers"
}

resource "aws_cloudwatch_log_group" "workers" {
  name              = "/aws/ecs/${var.project_name}"
  retention_in_days = var.worker_log_retention_days
}

data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "worker_execution" {
  name               = "${var.project_name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

resource "aws_iam_role_policy_attachment" "worker_execution" {
  role       = aws_iam_role.worker_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role" "worker_task" {
  name               = "${var.project_name}-worker"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

data "aws_iam_policy_document" "worker_task" {
  statement {
    sid       = "ReadManifestAndSourceObjects"
    actions   = ["s3:GetObject"]
    resources = concat(["${aws_s3_bucket.staging.arn}/*"], local.worker_source_bucket_resources)
  }

  dynamic "statement" {
    for_each = var.source_bucket_arn == null ? [] : [var.source_bucket_arn]
    content {
      sid       = "ListSourceBucket"
      actions   = ["s3:ListBucket"]
      resources = [statement.value]
    }
  }

  statement {
    sid       = "WriteDestinationObjects"
    actions   = ["s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
    resources = ["${aws_s3_bucket.destination.arn}/*"]
  }

  statement {
    sid = "ReadAndUpdateMigrationState"
    actions = [
      "dynamodb:DescribeTable",
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:Query",
      "dynamodb:BatchGetItem"
    ]
    resources = [
      aws_dynamodb_table.state.arn,
      aws_dynamodb_table.job_state.arn,
      "${aws_dynamodb_table.job_state.arn}/index/*"
    ]
  }
}

resource "aws_iam_role_policy" "worker_task" {
  name   = "migration-worker-access"
  role   = aws_iam_role.worker_task.id
  policy = data.aws_iam_policy_document.worker_task.json
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.project_name}-shard-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.worker_cpu)
  memory                   = tostring(var.worker_memory)
  execution_role_arn       = aws_iam_role.worker_execution.arn
  task_role_arn            = aws_iam_role.worker_task.arn

  container_definitions = jsonencode([{
    name      = "migration-worker"
    image     = var.worker_image
    essential = true
    environment = [
      { name = "AWS_REGION", value = var.aws_region }
    ]
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.workers.name
        awslogs-region        = var.aws_region
        awslogs-stream-prefix = "shard"
      }
    }
  }])
}

data "aws_iam_policy_document" "step_functions_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "orchestrator" {
  name               = "${var.project_name}-orchestrator"
  assume_role_policy = data.aws_iam_policy_document.step_functions_assume_role.json
}

data "aws_iam_policy_document" "orchestrator" {
  statement {
    sid       = "RunMigrationWorkerTasks"
    actions   = ["ecs:RunTask"]
    resources = [aws_ecs_task_definition.worker.arn]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [aws_ecs_cluster.migration.arn]
    }
  }

  statement {
    sid       = "ManageWorkerTasks"
    actions   = ["ecs:DescribeTasks", "ecs:StopTask"]
    resources = ["*"]
  }

  statement {
    sid = "DistributedMapChildExecutions"
    actions = [
      "states:StartExecution",
      "states:DescribeExecution",
      "states:StopExecution"
    ]
    resources = ["*"]
  }

  statement {
    sid = "EcsTaskEventIntegration"
    actions = [
      "events:PutTargets",
      "events:PutRule",
      "events:DescribeRule"
    ]
    resources = ["*"]
  }

  statement {
    sid       = "PassWorkerRoles"
    actions   = ["iam:PassRole"]
    resources = [aws_iam_role.worker_execution.arn, aws_iam_role.worker_task.arn]
    condition {
      test     = "StringLike"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "orchestrator" {
  name   = "run-ecs-shard-workers"
  role   = aws_iam_role.orchestrator.id
  policy = data.aws_iam_policy_document.orchestrator.json
}

resource "aws_sfn_state_machine" "migration" {
  name     = "${var.project_name}-shard-orchestration"
  role_arn = aws_iam_role.orchestrator.arn
  type     = "STANDARD"

  definition = jsonencode({
    Comment = "Run one Fargate task for each prepared migration shard."
    StartAt = "RunShards"
    States = {
      RunShards = {
        Type           = "Map"
        ItemsPath      = "$.shards"
        MaxConcurrency = var.max_concurrent_shards
        ItemSelector = {
          "jobId.$"             = "$$.Execution.Input.jobId"
          "sourceBucket.$"      = "$$.Execution.Input.sourceBucket"
          "sourcePrefix.$"      = "$$.Execution.Input.sourcePrefix"
          "destinationBucket.$" = "$$.Execution.Input.destinationBucket"
          "manifestS3Uri.$"     = "$$.Execution.Input.manifestS3Uri"
          "totalShards.$"       = "$$.Execution.Input.totalShards"
          "shardNumber.$"       = "$.Map.Item.Value.shardNumber"
          "command.$"           = "$.Map.Item.Value.command"
        }
        ItemProcessor = {
          ProcessorConfig = {
            Mode          = "DISTRIBUTED"
            ExecutionType = "STANDARD"
          }
          StartAt = "RunShardWorker"
          States = {
            RunShardWorker = {
              Type     = "Task"
              Resource = "arn:aws:states:::ecs:runTask.sync"
              Parameters = {
                Cluster        = aws_ecs_cluster.migration.arn
                TaskDefinition = aws_ecs_task_definition.worker.arn
                LaunchType     = "FARGATE"
                NetworkConfiguration = {
                  AwsvpcConfiguration = {
                    Subnets        = var.worker_subnet_ids
                    SecurityGroups = var.worker_security_group_ids
                    AssignPublicIp = var.assign_public_ip ? "ENABLED" : "DISABLED"
                  }
                }
                Overrides = {
                  ContainerOverrides = [{
                    Name = "migration-worker"
                    "Command.$" = "$.command"
                  }]
                }
              }
              Catch = [{
                ErrorEquals = ["States.ALL"]
                ResultPath  = "$.workerError"
                Next        = "RecordShardFailure"
              }]
              End = true
            }
            RecordShardFailure = {
              Type = "Pass"
              Parameters = {
                "shardNumber.$" = "$.shardNumber"
                "error.$"       = "$.workerError"
              }
              End = true
            }
          }
        }
        End = true
      }
    }
  })
}
