# AWS Enterprise Document Migration

A source-agnostic, extensible boilerplate for migrating documents, metadata and version history to Amazon S3 using AWS-native building blocks.

> **Status: community starter / reference implementation.** This repository is a foundation for engineering teams. It is not a certified turnkey migration product. Validate connector behavior, security, quotas, throughput, recovery and cost with a representative pilot before production use.

## What it provides

- A connector SPI so source systems can be added without changing the migration engine.
- Starter filesystem and Amazon S3 source connectors.
- JSONL manifest contract and schema.
- Deterministic destination-key strategy for LATEST_ONLY and ALL_VERSIONS.
- Streaming SHA-256 utility and S3 destination writer using SSE-S3, with bounded-memory sequential multipart uploads for large objects.
- Terraform foundation for encrypted S3 buckets, DynamoDB state tables and ECS/Fargate execution primitives.
- Separate AWS environment bootstrap for a private VPC, VPC endpoints, worker security group and immutable ECR repository.
- Docker build, GitHub Actions checks, operational documentation and examples.

## Architecture

    Source system(s)
      | connector plugin (filesystem / S3 / custom DMS / SaaS API)
      v
    Discovery + manifest shards (JSONL in S3)
      v
    ECS Fargate migration workers
      |-- stream source content
      |-- calculate/verify SHA-256
      |-- write deterministic S3 object key
      |-- persist migration state / retry metadata
      v
    Amazon S3 destination + DynamoDB state

The framework deliberately separates discovery from transfer. Do not attempt to enumerate hundreds of millions of records in one Lambda invocation or one in-memory manifest. The current CLI stages each document to local temporary disk for size and checksum verification. The S3 writer uses a single PUT up to 64 MiB and sequential multipart upload above that threshold, spooling one part at a time to temporary disk. Ensure the worker has sufficient ephemeral disk space. Multipart resume across process restarts is not implemented.

## Quick start: local filesystem to S3

Requirements: Java 17+, Maven 3.9+, AWS CLI v2, an AWS account and an S3 destination bucket. Terraform is optional for the local build.

1. Clone this repository.
2. Build and test with mvn clean verify from the application directory.
3. Configure AWS credentials using an AWS-supported provider (for example, an IAM Identity Center profile). Do not put access keys in source control.
4. Create a JSONL manifest using examples/manifests/sample.jsonl as a template. Each line is one document-version record.
5. Run the worker:

       java -jar target/document-migration-worker.jar \
         --source-type filesystem \
         --source-root /absolute/path/to/source \
         --destination-bucket YOUR_DESTINATION_BUCKET \
         --manifest ../examples/manifests/sample.jsonl \
         --version-policy ALL_VERSIONS \
         --region us-east-1

For durable sharded execution, configure the separate document-state and job-state DynamoDB tables and use the job-state options described in docs/quickstart.md. See docs/implementation-status.md for current limitations. Shard claims are persisted with a limit of three total attempts (including the initial claim); failed shards can be reclaimed while attempts remain, and an expired final attempt becomes terminally failed. This does not automatically dispatch replacement ECS workers.

## Configure for your environment

Copy application/src/main/resources/application.yml or provide environment variables. At minimum configure the source type and connector settings, destination bucket and AWS Region, version policy, manifest location, worker concurrency and retry limits.

The source connector must implement the DocumentSource interface. See docs/connector-development.md.

## Version semantics

- LATEST_ONLY: the manifest must explicitly identify the authoritative current version using isLatest=true. The engine does not guess latest by sorting version IDs or timestamps.
- ALL_VERSIONS: every source version is represented as a distinct destination object.
- Source version history and S3 bucket-native versioning are different concepts. Enable bucket versioning separately if required.

## Deploy the AWS foundation

For a new AWS account/VPC, start with [infrastructure/bootstrap/README.md](infrastructure/bootstrap/README.md). It provisions the private worker network, required VPC endpoints, worker security group and ECR repository. Then configure and deploy the workload stack in infrastructure/ using the bootstrap outputs. The [deployment validation runbook](docs/deployment-validation.md) covers image publication, Terraform deployment, a small Step Functions pilot, and post-run reconciliation. CI validates Terraform and builds the worker image but does not deploy to AWS.

## Production readiness

Before production: complete the gates in docs/production-readiness.md, run load/recovery/security tests, request service quotas, validate source API limits, and test a full restore/rollback procedure. No throughput or 25-day completion guarantee is implied.

## Contributing

See CONTRIBUTING.md. Connector contributions should include capability declarations, tests, pagination/throttling behavior and documentation.

## License

Apache-2.0. See LICENSE.
