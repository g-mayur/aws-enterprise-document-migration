# Local development and validation

This guide covers cloning the repository, building the Java application and running its automated tests. The default steps do **not** require an AWS account, AWS CLI, AWS credentials, an S3 bucket or deployed infrastructure.

## Prerequisites

- Git
- JDK 17 or newer
- Apache Maven 3.9 or newer
- Docker Desktop or Docker Engine (recommended for DynamoDB and S3 integration tests)
- Terraform (optional; used only for the infrastructure formatting check)

The application is built for Java 17. The Java Build and Validation CI workflow runs this same repository validation script with Temurin 17, DynamoDB Local and LocalStack's S3 service.

## Clone and validate

```bash
git clone https://github.com/g-mayur/aws-enterprise-document-migration.git
cd aws-enterprise-document-migration
```

Run the repository validation script:

```bash
bash scripts/local-validate.sh
```

The script:

1. Checks that Java 17+ and Maven 3.9+ are installed.
2. Uses an existing DynamoDB Local endpoint supplied through `DYNAMODB_LOCAL_ENDPOINT`, or a service already listening on port `8000`.
3. If neither is available and Docker is running, starts a temporary DynamoDB Local container using the same image version as CI, waits for it, and removes only the container it started.
4. Runs `mvn --batch-mode clean verify` for the application.
5. Runs recursive Terraform formatting validation when Terraform is installed.

If Docker is unavailable, Maven tests still run, but the DynamoDB and S3 integration tests that require their respective endpoints are skipped by JUnit assumptions. To run those integration tests, start the local services and set their endpoint variables.

## Run Maven directly

For a Java build and test run without starting local services:

```bash
mvn --batch-mode clean verify -f application/pom.xml
```

DynamoDB and S3 integration tests are skipped unless their endpoint variables are configured. Unit tests still execute.

## S3 API integration test with LocalStack

The Maven test suite includes `S3ApiIntegrationTest`. It uses the production `S3ClientFactory` and `S3DestinationWriter` against a real S3-compatible HTTP API. It creates a temporary bucket, uploads a document, checks its server-side encryption and metadata through `HeadObject`, downloads it through `GetObject`, verifies the bytes, and removes the test object and bucket.

Start LocalStack with only its S3 service enabled:

```bash
docker run --detach --rm --name aws-doc-migration-localstack \
  -p 4566:4566 \
  -e SERVICES=s3 \
  -e DEFAULT_REGION=us-east-1 \
  localstack/localstack:3.8.1
```

Configure dummy credentials and the local endpoint, then run Maven:

```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
export S3_INTEGRATION_ENDPOINT_URL=http://localhost:4566

mvn --batch-mode clean verify -f application/pom.xml
```

These credentials are local placeholders only. The test uses the endpoint supplied by `S3_INTEGRATION_ENDPOINT_URL`; it does not contact AWS. The GitHub Actions Java validation job starts the same pinned LocalStack image and sets these variables automatically. The companion `S3SourceApiIntegrationTest` uses the same endpoint to create a real object, validate the source bucket, discover the object under its configured prefix, and read it through the source connector's conditional snapshot request. It verifies the discovered record's identity, size and snapshot metadata before comparing the streamed bytes.

## AWS-free migration-flow integration test

The DynamoDB document-state integration suite also verifies that multipart upload checkpoints survive a new client/process, remain available after an expired lease is reclaimed, reject writes from stale lease owners, and can be cleared by the current owner. These checkpoints use the existing document-state table; no additional AWS resource or schema migration is required. The Maven test suite also includes `LocalFilesystemMigrationFlowIntegrationTest`. It creates real files in a temporary directory, discovers them through `FilesystemSource`, writes and reads a JSONL manifest, then runs each document through the migration verification and destination-key logic. A test-only in-memory destination captures the uploaded bytes and SHA-256 values so the test can verify content integrity without contacting AWS.

This filesystem test exercises the local source-to-destination migration flow, but it does not invoke the production S3 API or exercise the full command-line `migrate` entry point. The separate `S3ApiIntegrationTest` covers actual S3 API calls for the production S3 destination writer. `S3SourceApiIntegrationTest` covers the production S3 source connector's unversioned-bucket discovery and snapshot-read path. `S3VersionedSourceApiIntegrationTest` enables bucket versioning, writes two versions of one object, verifies that discovery preserves both exact version IDs and latest-version state, and reads each version independently through the source connector. `S3SuspendedSourceApiIntegrationTest` creates an immutable version, suspends versioning, writes the mutable `null` version, and verifies snapshot-based reading alongside historical-version reading; it also checks that delete markers are not exposed as document records. `S3UnversionedPaginationApiIntegrationTest` uploads 1,001 objects and verifies that the source connector discovers objects across the real S3 1,000-key listing boundary, without duplicates, and can read an object from the following page. `S3VersionedPaginationApiIntegrationTest` creates more than 1,000 object versions and verifies that the source connector enumerates versions across the S3 version-list boundary, preserves latest-version state, and reads exact versions from the later page; its cleanup also paginates version listings and deletes versions in batches. All six S3 API integration tests are skipped unless `S3_INTEGRATION_ENDPOINT_URL` is configured.

## Optional S3-compatible endpoint

The CLI accepts an optional `S3_ENDPOINT_URL` environment variable. When set, the S3 SDK client uses that endpoint and enables path-style bucket addressing, which is commonly required by local S3-compatible services such as LocalStack and MinIO.

Example for a local service listening on port 4566:

```bash
export S3_ENDPOINT_URL=http://localhost:4566
```

Use only a local or otherwise explicitly trusted endpoint for development. Do not include credentials, query parameters or paths in the endpoint URL. The endpoint must be an absolute HTTP or HTTPS URL containing a host.

When `S3_ENDPOINT_URL` is unset or blank, the SDK's standard AWS S3 endpoint resolution is unchanged. The override applies only to S3; DynamoDB and Step Functions clients continue to use their normal AWS endpoint resolution. This setting alone does not provision or start an S3-compatible service, create buckets, or make the full migration workflow AWS-free.

## AWS credentials are not needed for local tests

The DynamoDB integration tests use local endpoint configuration and dummy local credentials. The S3 API integration tests use LocalStack and dummy local credentials when `S3_INTEGRATION_ENDPOINT_URL` is configured. Neither test should connect to AWS. Do not add real access keys, session tokens, customer data, production manifests or Terraform state to the repository.

## Configure AWS credentials only when preparing to use AWS

Actual S3 transfers, DynamoDB-backed migration state, Step Functions dispatch and AWS deployment require an AWS account, provisioned resources, appropriate permissions and credentials. Those are separate from local validation.

For an organisation using IAM Identity Center, an administrator should first provide the approved start URL, SSO Region, AWS account and role. Then configure a named profile:

```bash
aws configure sso --profile migration-dev
aws sso login --profile migration-dev
export AWS_PROFILE=migration-dev
aws sts get-caller-identity
```

Use the approved AWS Region explicitly in application commands. Do not paste credentials into source files, command examples, issues or pull requests. For workloads running on AWS, prefer an appropriately scoped ECS task role or EC2 instance role rather than long-lived access keys.

If you do not have an AWS account or an approved role, stop after local validation. Do not attempt an AWS migration or deployment.

## Troubleshooting

| Symptom | What to check |
|---|---|
| `java: command not found` or Java version below 17 | Install a JDK 17+ and confirm `java -version` and `JAVA_HOME` point to it |
| `mvn: command not found` or Maven version below 3.9 | Install Maven 3.9+ and confirm `mvn -version` |
| Docker daemon unavailable | Start Docker Desktop/Engine; otherwise run Maven directly and note that local-service integration tests are skipped |
| Port 8000 is already in use | Stop the conflicting service or configure a separate DynamoDB Local endpoint and export `DYNAMODB_LOCAL_ENDPOINT` |
| S3 integration test is skipped | Start LocalStack and export `S3_INTEGRATION_ENDPOINT_URL`, `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` |
| DynamoDB integration tests cannot connect | Confirm the endpoint is reachable and DynamoDB Local is ready before running Maven |
| Terraform formatting check is skipped | Install Terraform and ensure it is available on `PATH` |
| AWS CLI reports missing credentials | Local tests do not need AWS credentials; for AWS operations, use an administrator-approved IAM Identity Center profile or workload role |

## What local validation does not prove

A successful local build does not prove that an AWS deployment, IAM policy, S3 transfer against AWS, Step Functions workflow, service quota, network path, throughput target or production recovery procedure works in your account. Those require a separately approved non-production AWS pilot. See [Production readiness](production-readiness.md) and [Deployment validation](deployment-validation.md).
