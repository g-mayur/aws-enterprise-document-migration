# AWS environment bootstrap

This separate Terraform root module provisions the network and image-registry foundation for the existing workload stack. It does not deploy workers, create migration buckets or tables, or start a migration.

## Resources

- One DNS-enabled VPC and two private subnets in separate Availability Zones.
- Private route table with no default internet route.
- Outbound-only worker security group and a separate endpoint security group.
- Interface endpoints for ECR API, ECR Docker registry, and CloudWatch Logs in both private subnets.
- Gateway endpoints for S3 and DynamoDB.
- Private ECR repository with immutable tags, scan-on-push, AES-256 encryption, and lifecycle cleanup.

There is no NAT gateway, internet gateway, public subnet, public IP, or general internet egress. This keeps the pilot network private and avoids NAT hourly charges. If a source connector needs a non-AWS public API, design and approve controlled egress before using that connector.

## Prerequisites

- AWS account and approved IAM Identity Center profile or role with permission to create VPC, subnet, route table, security group, VPC endpoint, and ECR resources.
- Terraform >= 1.6.0 and AWS provider >= 5.40, < 6.0.
- Two available Availability Zones and a VPC CIDR that does not overlap connected networks.

## Deploy

From the repository root:

    cd infrastructure/bootstrap
    cp terraform.tfvars.example terraform.tfvars
    # Edit the Region, CIDR, and names if required.
    terraform init
    terraform fmt -check -recursive
    terraform validate
    terraform plan -out=bootstrap.tfplan
    terraform show bootstrap.tfplan

Review the account, Region, CIDR overlap, endpoint costs, and complete plan. Obtain approval before applying:

    terraform apply bootstrap.tfplan
    terraform output

Keep this stack's state separate from the workload stack. Use encrypted remote state and locking before shared or production use. Never commit tfvars, state, plans, or credentials.

## Build and publish the worker image

Use the immutable repository URL from the bootstrap output. From the repository root:

    export AWS_REGION=us-east-1
    export ECR_URI=$(terraform -chdir=infrastructure/bootstrap output -raw ecr_repository_url)
    export AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
    export IMAGE_TAG=sha-$(git rev-parse --short HEAD)

    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
    docker build --pull --file application/Dockerfile --tag "$ECR_URI:$IMAGE_TAG" application
    docker push "$ECR_URI:$IMAGE_TAG"

Never reuse an image tag. Record the pushed digest and use the full immutable image URI in the workload stack.

## Configure the workload stack

The existing workload stack remains in infrastructure/. Set these values in its terraform.tfvars:

- worker_image: full immutable ECR image URI, including the tag.
- worker_subnet_ids: JSON list returned by terraform output -json private_subnet_ids.
- worker_security_group_ids: a one-item list containing worker_security_group_id.
- source_bucket_arn: approved source bucket ARN.
- Unique staging and destination bucket names, plus matching Region and project name.

Retrieve outputs with:

    terraform -chdir=infrastructure/bootstrap output -json private_subnet_ids
    terraform -chdir=infrastructure/bootstrap output -raw worker_security_group_id
    terraform -chdir=infrastructure/bootstrap output -raw ecr_repository_url

Then follow the deployment validation runbook for workload plan, review, apply, small test job, and reconciliation.

## Cost and safety

- Three interface endpoint services are deployed in each of two AZs. Interface endpoints are billed per endpoint-hour and data processed. S3 and DynamoDB gateway endpoints do not have an hourly endpoint charge.
- ECR storage/scanning, endpoint traffic, CloudWatch Logs, S3, DynamoDB, ECS/Fargate, and Step Functions can incur charges. Check current regional pricing and quotas before apply.
- The bootstrap grants no source-bucket permissions. The workload task role and source bucket policy must both allow the required read operations.
- Endpoint security group ingress allows HTTPS only from worker tasks. Worker tasks have no inbound rules. Worker egress is currently unrestricted; narrow it when source requirements are known.
- This is a pilot foundation, not a complete production landing zone. Add organization-required logging, flow logs, policy guardrails, backup/state controls, monitoring, and incident response before production.
