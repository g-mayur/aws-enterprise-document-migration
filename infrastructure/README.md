# Terraform workload foundation

This Terraform root module provisions the migration workload resources: destination/staging S3 buckets with SSE-S3 and public-access blocking, DynamoDB document-state, batch-metadata and job/shard-state tables, ECS/Fargate worker resources, Step Functions orchestration, and CloudWatch operational monitoring.

It expects an existing worker image, private subnet IDs, and worker security group IDs. For a new AWS account/VPC, first deploy the separate [bootstrap stack](bootstrap/README.md). That stack creates a private VPC, two private subnets, VPC endpoints for ECR/CloudWatch Logs/S3/DynamoDB, an outbound-only worker security group, and an immutable ECR repository. Publish the worker image, then supply the bootstrap outputs to this workload stack.

Requirements: Terraform >= 1.6, AWS provider >= 5.40 and < 6.0, configured AWS credentials.

    cp terraform.tfvars.example terraform.tfvars
    terraform init
    terraform fmt -check -recursive
    terraform validate
    terraform plan
    # Review the plan and obtain approval before apply.
    terraform apply

Use remote state with encryption, access controls and locking in team environments. Do not commit terraform.tfvars, state files or credentials. Set globally unique bucket names. Review lifecycle, retention, object ownership, replication, logging, data residency and IAM requirements before deployment.

## ECS Fargate and Step Functions shard orchestration

The orchestration resources create an ECS cluster, a Fargate task definition, CloudWatch log group, least-privilege worker/orchestrator roles, and a Standard Step Functions state machine using a Distributed Map. Each map item starts one ECS task. The workload stack does not provision a VPC, subnets, security groups, NAT gateways, VPC endpoints, ECR repositories, or a container image.

Set these values before planning:

- worker_image: already-published immutable image URI containing the application JAR.
- worker_subnet_ids and worker_security_group_ids: network placement for Fargate. The bootstrap stack outputs matching private subnet IDs and worker security group ID. Its private subnets have ECR, CloudWatch Logs, S3 and DynamoDB connectivity through VPC endpoints.
- source_bucket_arn: source S3 bucket ARN. Workers receive ListBucket/GetObject access to this bucket; the staging bucket is used for the manifest and the destination bucket for writes.
- max_concurrent_shards: Distributed Map concurrency ceiling. Tune against account Fargate quotas, source/destination throughput and DynamoDB capacity.

## CloudWatch monitoring

The workload stack creates a dashboard covering Step Functions execution counts, ECS cluster CPU/memory utilization, and DynamoDB read/write throttling for both migration state tables. It also creates alarms for failed/timed-out state-machine executions, sustained high worker CPU/memory, and read/write throttles on either state table.

Optional notifications can be configured by supplying an existing SNS topic ARN (or another supported CloudWatch alarm action ARN) through `monitoring_alarm_actions`. The default is an empty list: alarms and dashboard are created, but no notifications are sent. The stack does not create an SNS topic or subscribe recipients.

Thresholds and evaluation periods are configurable through `monitoring_cpu_threshold`, `monitoring_memory_threshold`, `monitoring_period_seconds`, and `monitoring_evaluation_periods`. Alarm resources and the dashboard name are exposed as Terraform outputs.

### Initial operational objectives

Use these as pilot starting points, not contractual or production-validated SLOs:

- Orchestration reliability: investigate every failed or timed-out migration execution; the corresponding alarms trigger on one event in a period.
- Worker capacity: keep average ECS cluster CPU and memory below 80%; alarms default to two consecutive five-minute periods at or above 80%.
- State-store availability: target zero DynamoDB read/write throttle events; alarms trigger on the first event in a five-minute period.
- Visibility: review the dashboard during each migration wave and retain worker logs according to the configured log-group retention.

These are infrastructure-level indicators. They do not measure document-level success rate, end-to-end migration latency, checksum mismatch rate, queue age, or dead-letter backlog. Those require application-level metrics and representative pilot data before numerical SLOs can be approved. CloudWatch metrics may also be absent when no executions or worker tasks are running; missing data is intentionally treated as non-breaching.

## Starting an orchestration execution

The state machine input must include jobId, sourceBucket, sourcePrefix, destinationBucket, manifestS3Uri, totalShards, and a shards array. Each shards item contains shardNumber and a complete ECS container command array. The job must already be prepared in DynamoDB and the exact manifest uploaded to the configured staging bucket. The fingerprint is checked by each worker against the prepared job.

This stack does not create the job, generate/upload the manifest, publish the container image, or invoke the state machine automatically. Those are separate deployment/ingestion responsibilities. Step Functions coordinates task launch and waits for each Fargate task; DynamoDB remains authoritative for shard claims, leases and terminal state. Worker task failures are allowed to fail the corresponding Map item and the overall execution instead of being converted into a successful result. Step Functions retries only the documented `AmazonECS.Unknown` RunTask API rejection twice with exponential backoff; this rejection occurs before a worker can claim its shard. Application/worker failures are not automatically retried, because a failed worker may have left a shard lease active. If the bounded launch retries are exhausted, the shard may remain PENDING in DynamoDB and requires operator investigation; automatic dispatch/reconciliation for worker failures and expired leases is not implemented.

Before applying in a real account, validate the state machine definition and IAM policy with Terraform, confirm the worker image entrypoint accepts the CLI arguments, and test a small prepared job end to end. Review AWS service quotas and estimated Step Functions/ECS costs before increasing concurrency.
