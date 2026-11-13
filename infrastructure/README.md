# Terraform foundation

This module is a starting point for an AWS account. It provisions destination/staging S3 buckets with SSE-S3 and public-access blocking, plus DynamoDB document-state, batch-metadata and job/shard-state tables. It does **not** provision a complete production VPC, ECS service, Lambda/API/EventBridge orchestration, private endpoints, CI/CD deployment role or cross-account source access.

Requirements: Terraform >= 1.6, AWS provider >= 5.40, configured AWS credentials.

```bash
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform fmt -check -recursive
terraform validate
terraform plan
# Review the plan and obtain approval before apply.
terraform apply
```

Use remote state with encryption, access controls and locking in team environments. Do not commit `terraform.tfvars`, state files or credentials. Set unique bucket names. The dedicated job-state table uses a `pk`/`sk` key schema and on-demand capacity, with point-in-time recovery and server-side encryption enabled. Review lifecycle, retention, object ownership, replication, logging, data residency and IAM requirements before deployment.


## ECS Fargate and Step Functions shard orchestration

The orchestration resources create an ECS cluster, a Fargate task definition, CloudWatch log group, least-privilege worker/orchestrator roles, and a Standard Step Functions state machine using a Distributed Map. Each map item starts one ECS task. The workflow does not provision a VPC, subnets, security groups, NAT gateways, VPC endpoints, ECR repositories, or a container image.

Set the following values before planning:

- `worker_image`: an already-published immutable image URI containing the application JAR.
- `worker_subnet_ids` and `worker_security_group_ids`: network placement for Fargate. With `assign_public_ip = false`, ensure the subnets have NAT or the required VPC endpoints for ECR, CloudWatch Logs, S3 and DynamoDB.
- `source_bucket_arn`: the source S3 bucket ARN. Workers receive ListBucket/GetObject access to this bucket; the staging bucket is used for the manifest and the destination bucket for writes.
- `max_concurrent_shards`: the Distributed Map concurrency ceiling. Tune this against account Fargate quotas, source/destination throughput and DynamoDB capacity.

### Starting an orchestration execution

The state machine input is JSON in this shape:

```json
{
  "jobId": "migration-2026-001",
  "sourceBucket": "source-bucket-name",
  "sourcePrefix": "exports/",
  "destinationBucket": "destination-bucket-name",
  "manifestS3Uri": "s3://staging-bucket/manifests/migration-2026-001.jsonl",
  "totalShards": 2,
  "shards": [
    {
      "shardNumber": 0,
      "command": ["--mode", "migrate", "--source-type", "s3", "--source-bucket", "source-bucket-name", "--source-prefix", "exports/", "--destination-bucket", "destination-bucket-name", "--manifest-s3-uri", "s3://staging-bucket/manifests/migration-2026-001.jsonl", "--state-table", "document-migration-state", "--job-state-table", "document-migration-job-state", "--job-id", "migration-2026-001", "--total-shards", "2", "--shard-number", "0"]
    },
    {
      "shardNumber": 1,
      "command": ["--mode", "migrate", "--source-type", "s3", "--source-bucket", "source-bucket-name", "--source-prefix", "exports/", "--destination-bucket", "destination-bucket-name", "--manifest-s3-uri", "s3://staging-bucket/manifests/migration-2026-001.jsonl", "--state-table", "document-migration-state", "--job-state-table", "document-migration-job-state", "--job-id", "migration-2026-001", "--total-shards", "2", "--shard-number", "1"]
    }
  ]
}
```

The job must already be prepared in DynamoDB and the manifest must be uploaded to the configured staging bucket. Its fingerprint is checked by each worker against the prepared job. The CLI downloads the S3 manifest to an ephemeral local file before scanning it. Each `shards` item contains its `shardNumber` and a complete ECS container `command` array. Build the command from the prepared job's source/destination settings and the Terraform output table names; use the matching shard number in each command. Passing the complete array avoids relying on oversized Step Functions intrinsic expressions.

This PR deliberately does not create the job, generate/upload the manifest, publish the container image, or invoke the state machine automatically. Those are separate deployment/ingestion responsibilities. Step Functions coordinates task launch and waits for each Fargate task; DynamoDB remains authoritative for shard claims, leases and terminal state. No automatic Step Functions retry is configured, so orchestration does not bypass the application's durable attempt limits.

Before applying in a real account, validate the state machine definition and IAM policy with Terraform, confirm the worker image's entrypoint accepts the CLI arguments, and test a small prepared job end to end. Review AWS service quotas and estimated Step Functions/ECS costs before increasing concurrency.
