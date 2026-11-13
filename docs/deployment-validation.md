# Deployment validation runbook

This runbook takes the repository from a passing CI build to a controlled, small AWS pilot. It does not deploy resources automatically. Run commands with an approved AWS role in the target account and Region.

## 1. Confirm prerequisites

- Java 17 and Maven 3.9+ for the application build.
- Docker and AWS CLI v2 for image publication and AWS checks.
- Terraform >= 1.6 and AWS provider >= 5.40.
- An AWS account with an approved IAM Identity Center profile or deployment role.
- For a new account/VPC, deploy [the bootstrap stack](../infrastructure/bootstrap/README.md) first. It creates the private worker subnets, security group, VPC endpoints and ECR repository.
- An approved source bucket and a test-only source prefix.
- An IAM identity authorized to deploy the reviewed Terraform plans and publish the image.

Do not use production data for the first run.

## 2. Bootstrap the network and ECR repository (new environment)

Follow the bootstrap README. Review and apply that stack separately. Record its private subnet IDs, worker security group ID, ECR repository URL, account and Region. The bootstrap creates no NAT gateway or general internet route; source connectors requiring public APIs need a separately approved egress design.

## 3. Build and publish an immutable worker image

From the repository root, use the target account, Region and an immutable commit-derived tag:

    export AWS_REGION=us-east-1
    export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
    export ECR_URI=$(terraform -chdir=infrastructure/bootstrap output -raw ecr_repository_url)
    export IMAGE_TAG=sha-$(git rev-parse --short HEAD)

    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
    docker build --pull -f application/Dockerfile -t "$ECR_URI:$IMAGE_TAG" application
    docker push "$ECR_URI:$IMAGE_TAG"

Do not overwrite a published tag. Record the image digest for deployment evidence. If using an existing ECR/network environment, set ECR_URI to its approved repository URL instead.

## 4. Validate and deploy the workload Terraform stack

From the repository root:

    cd infrastructure
    cp terraform.tfvars.example terraform.tfvars

Edit terraform.tfvars with approved values. Set the immutable worker_image, worker_subnet_ids and worker_security_group_ids from bootstrap outputs, source_bucket_arn, and globally unique staging/destination bucket names. Do not commit terraform.tfvars, credentials, or state files.

Then run:

    terraform init
    terraform fmt -check -recursive
    terraform validate
    terraform plan -out=tfplan
    terraform show tfplan

Review the complete plan, IAM permissions, bucket names, network routes/endpoints, quotas and estimated cost. Obtain approval before applying:

    terraform apply tfplan

Keep the plan and state in approved secure storage. Do not apply a plan that creates or changes resources outside the reviewed scope.

## 5. Prepare a small migration job

Use a small JSONL manifest containing test documents only. The same exact manifest bytes must be used for job preparation and worker execution.

    cd ../application
    mvn --batch-mode clean verify
    java -jar target/document-migration-worker.jar \
      --mode prepare-job \
      --job-state-table YOUR_JOB_STATE_TABLE \
      --job-id YOUR_UNIQUE_TEST_JOB_ID \
      --total-shards 2 \
      --manifest /absolute/path/to/test-manifest.jsonl \
      --region "$AWS_REGION"

Upload that unchanged manifest to the staging bucket:

    aws s3 cp /absolute/path/to/test-manifest.jsonl s3://YOUR_STAGING_BUCKET/manifests/YOUR_UNIQUE_TEST_JOB_ID.jsonl

Before continuing, confirm job preparation completed successfully and that the source bucket, destination bucket, manifest URI, shard count, and configured DynamoDB table names are correct.

## 6. Start the Step Functions execution

Use the state machine ARN from Terraform output. Supply one shard number for each registered shard. The execution input shape and complete per-shard command arrays are documented in infrastructure/README.md. Replace all example values and table names with deployed values. Do not start a second execution for the same test job while the first is running.

## 7. Verify and record the pilot

- Confirm the Step Functions execution reaches SUCCEEDED; inspect failed Map items and ECS task logs if it does not.
- Confirm expected ECS tasks started and stopped, and review CloudWatch logs for errors.
- Reconcile source manifest records, completed/failed shard state, destination object count, object sizes, and SHA-256 checksums.
- Verify destination encryption and access controls.
- Confirm no test data was written outside the designated destination prefix.
- Retain execution ARN, image digest, Terraform plans, log references, reconciliation results, and operator approval.

A successful orchestration execution alone is not proof of data correctness. Do not scale concurrency or migrate production data until the pilot is reconciled and production-readiness gates are approved.

## Scope and limitations

CI formats/validates Terraform and builds the container; it does not authenticate to AWS, publish images, apply Terraform, or start executions. The bootstrap stack provisions only the private network, endpoints and ECR repository. Workload deployment, source-system access, migration data, production monitoring and alarms remain explicit operator responsibilities.
