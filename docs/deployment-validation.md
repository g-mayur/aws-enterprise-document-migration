# Deployment validation runbook

This runbook takes the repository from a passing CI build to a controlled, small AWS pilot. It does not deploy resources automatically. Run commands with an approved AWS role in the target account and Region.

## 1. Confirm prerequisites

- Java 17 and Maven 3.9+ for the application build.
- Docker and AWS CLI v2 for image publication and AWS checks.
- Terraform >= 1.6 and AWS provider >= 5.40.
- An existing ECR repository, VPC subnets, security group, and source bucket.
- Network egress or VPC endpoints from worker subnets to ECR, CloudWatch Logs, S3 and DynamoDB.
- An IAM identity authorized to deploy the reviewed Terraform plan and publish the image.

Do not use production data for the first run. Use a dedicated test source prefix, destination bucket, manifest, and job ID.

## 2. Build and publish an immutable worker image

From the repository root, choose the target account and Region, then use the Git commit as the image tag:

```bash
export AWS_REGION=us-east-1
export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR_REPOSITORY=document-migration-worker
export IMAGE_TAG=$(git rev-parse --short HEAD)
export ECR_URI=$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPOSITORY

aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
docker build --pull -f application/Dockerfile -t "$ECR_URI:$IMAGE_TAG" application
docker push "$ECR_URI:$IMAGE_TAG"
```

Create the ECR repository before this step if it does not exist. Do not overwrite a published tag; configure ECR tag immutability where possible. Record the image digest for deployment evidence.

## 3. Validate and deploy Terraform

From the repository root:

```bash
cd infrastructure
cp terraform.tfvars.example terraform.tfvars
```

Edit `terraform.tfvars` with real, approved values. In particular, set the immutable `worker_image`, existing `worker_subnet_ids`, `worker_security_group_ids`, and `source_bucket_arn`. Confirm the staging and destination bucket names are globally unique. Do not commit `terraform.tfvars`, credentials, or state files.

Then run:

```bash
terraform init
terraform fmt -check -recursive
terraform validate
terraform plan -out=tfplan
terraform show tfplan
```

Review the complete plan, IAM permissions, bucket names, network routes/endpoints, quotas and estimated cost. Obtain approval before:

```bash
terraform apply tfplan
```

Keep the plan and state in the team's approved secure storage. Do not apply a plan that creates or changes resources outside the reviewed scope.

## 4. Prepare a small migration job

Use a small JSONL manifest containing test documents only. The same exact manifest bytes must be used for job preparation and worker execution.

Prepare the job against the deployed job-state table:

```bash
cd ../application
mvn --batch-mode clean verify
java -jar target/document-migration-worker.jar \
  --mode prepare-job \
  --job-state-table YOUR_JOB_STATE_TABLE \
  --job-id YOUR_UNIQUE_TEST_JOB_ID \
  --total-shards 2 \
  --manifest /absolute/path/to/test-manifest.jsonl \
  --region "$AWS_REGION"
```

Upload that unchanged manifest to the staging bucket:

```bash
aws s3 cp /absolute/path/to/test-manifest.jsonl s3://YOUR_STAGING_BUCKET/manifests/YOUR_UNIQUE_TEST_JOB_ID.jsonl
```

Before continuing, confirm the job preparation completed successfully and that the source bucket, destination bucket, manifest URI, shard count, and configured DynamoDB table names are correct.

## 5. Start the Step Functions execution

Use the state machine ARN from Terraform output. Supply one shard number for each registered shard:

```bash
aws stepfunctions start-execution \
  --state-machine-arn YOUR_STATE_MACHINE_ARN \
  --input '{
    "jobId": "YOUR_UNIQUE_TEST_JOB_ID",
    "sourceBucket": "YOUR_SOURCE_BUCKET",
    "sourcePrefix": "YOUR_TEST_SOURCE_PREFIX",
    "destinationBucket": "YOUR_DESTINATION_BUCKET",
    "manifestS3Uri": "s3://YOUR_STAGING_BUCKET/manifests/YOUR_UNIQUE_TEST_JOB_ID.jsonl",
    "totalShards": 2,
    "shards": [0, 1]
  }' \
  --region "$AWS_REGION"
```

Use the exact same job ID, total shard count, and manifest that were prepared above. Do not start a second execution for the same test job while the first is running.

## 6. Verify and record the pilot

- Confirm the Step Functions execution reaches `SUCCEEDED`; inspect failed Map items and ECS task logs if it does not.
- Confirm the expected ECS tasks started and stopped, and review CloudWatch logs for errors.
- Reconcile source manifest records, completed/failed shard state, destination object count, object sizes, and SHA-256 checksums.
- Verify destination encryption and access controls.
- Confirm no test data was written outside the designated destination prefix.
- Retain the execution ARN, image digest, Terraform plan, log references, reconciliation results, and operator approval.

A successful orchestration execution alone is not proof of data correctness. Do not scale concurrency or migrate production data until the pilot is reconciled and the relevant gates in [production-readiness.md](production-readiness.md) are approved.

## Scope and limitations

The CI workflow formats/validates Terraform and builds the container; it does not authenticate to AWS, publish images, apply Terraform, or start executions. Those actions remain explicit operator steps. The reference workflow also does not provision ECR, VPC networking, source-system access, or production monitoring/alarms. Review [implementation-status.md](implementation-status.md) and [production-readiness.md](production-readiness.md) before production use.
