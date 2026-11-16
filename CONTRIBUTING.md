# Contributing

1. Open an issue describing the use case or defect.
2. Keep connector-specific behavior inside a connector module.
3. Include unit tests and, where possible, integration tests using disposable resources.
4. Document authentication, permissions, pagination, rate limits, version semantics and unsupported capabilities.
5. Run `bash scripts/local-validate.sh` (Java 17+, Maven 3.9+; Docker is recommended for DynamoDB Local integration tests).
6. If Terraform is installed, run `terraform -chdir=infrastructure fmt -check -recursive`.
7. Never commit credentials, customer data, real manifests, state files or account-specific identifiers.

Local validation does not require an AWS account or AWS credentials. See [Local development and validation](docs/local-development.md) for setup details. AWS-backed integration and deployment testing must use an approved non-production account.

All contributions are submitted under Apache-2.0.
