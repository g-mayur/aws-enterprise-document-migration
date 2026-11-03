# Terraform foundation

This module is a starting point for an AWS account. It provisions destination/staging S3 buckets with SSE-S3 and public-access blocking, plus DynamoDB state tables. It does **not** provision a complete production VPC, ECS service, Lambda/API/EventBridge orchestration, private endpoints, CI/CD deployment role or cross-account source access.

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

Use remote state with encryption, access controls and locking in team environments. Do not commit `terraform.tfvars`, state files or credentials. Set unique bucket names. Review lifecycle, retention, object ownership, replication, logging, data residency and IAM requirements before deployment.
