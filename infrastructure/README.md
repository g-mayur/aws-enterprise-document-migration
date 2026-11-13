# Terraform workload foundation

This Terraform root module provisions the migration workload resources: destination/staging S3 buckets with SSE-S3 and public-access blocking, DynamoDB document-state, batch-metadata and job/shard-state tables, ECS/Fargate worker resources, and Step Functions orchestration.

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

## Starting an orchestration execution

The state machine input must include jobId, sourceBucket, sourcePrefix, destinationBucket, manifestS3Uri, totalShards, and a shards array. Each shards item contains shardNumber and a complete ECS container command array. The job must already be prepared in DynamoDB and the exact manifest uploaded to the configured staging bucket. The fingerprint is checked by each worker against the prepared job.

This stack does not create the job, generate/upload the manifest, publish the container image, or invoke the state machine automatically. Those are separate deployment/ingestion responsibilities. Step Functions coordinates task launch and waits for each Fargate task; DynamoDB remains authoritative for shard claims, leases and terminal state. Worker task failures are allowed to fail the corresponding Map item and the overall execution instead of being converted into a successful result. No automatic Step Functions retry is configured, so orchestration does not bypass the application's durable attempt limits. If ECS cannot start a task at all, its shard may remain PENDING in DynamoDB and requires operator investigation; automatic dispatch/reconciliation is not implemented.

Before applying in a real account, validate the state machine definition and IAM policy with Terraform, confirm the worker image entrypoint accepts the CLI arguments, and test a small prepared job end to end. Review AWS service quotas and estimated Step Functions/ECS costs before increasing concurrency.
