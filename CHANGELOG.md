# Changelog

All notable changes to this project are documented here.

## [0.1.0] - 2026-11-16

First community starter/reference release.

### Included

- Connector SPI with starter filesystem and Amazon S3 source connectors.
- JSONL manifest contract and deterministic destination-key handling for `LATEST_ONLY` and `ALL_VERSIONS`.
- SHA-256 content verification and Amazon S3 destination writes using SSE-S3.
- Bounded-memory sequential multipart uploads for large objects.
- Lease-fenced DynamoDB document state and checkpoint-backed multipart resume in context-backed sharded execution, including reconciliation against remote S3 parts.
- Terraform foundations for S3, DynamoDB, worker execution and environment bootstrap.
- Local validation, Docker build and GitHub Actions validation workflows.
- Operational, deployment, recovery and production-readiness documentation.

### Validation and limitations

- CI validates the Java build/tests, LocalStack S3 integration coverage, DynamoDB Local integration coverage, Terraform formatting/validation and Docker image build.
- Local validation does not deploy AWS infrastructure or prove behavior in a customer's AWS account.
- Real AWS pilot validation, representative throughput/load testing, security review, quota validation, cost measurement and end-to-end recovery/restore exercises remain necessary before production use.
- The repository is a starter/reference implementation, not a certified turnkey migration product.
