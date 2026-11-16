# Local development and validation

This guide covers cloning the repository, building the Java application and running its automated tests. These steps do **not** require an AWS account, AWS CLI, AWS credentials, an S3 bucket or deployed infrastructure.

## Prerequisites

- Git
- JDK 17 or newer
- Apache Maven 3.9 or newer
- Docker Desktop or Docker Engine (recommended for DynamoDB integration tests)
- Terraform (optional; used only for the infrastructure formatting check)

The application is built for Java 17. The CI workflow uses Temurin 17 and Maven.

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

1. Checks that Java 17+ and Maven are installed.
2. Uses an existing DynamoDB Local endpoint supplied through `DYNAMODB_LOCAL_ENDPOINT`, or a service already listening on port `8000`.
3. If neither is available and Docker is running, starts a temporary DynamoDB Local container using the same image version as CI, waits for it, and removes only the container it started.
4. Runs `mvn --batch-mode clean verify` for the application.
5. Runs recursive Terraform formatting validation when Terraform is installed.

If Docker is unavailable, Maven tests still run, but the DynamoDB integration tests that require `DYNAMODB_LOCAL_ENDPOINT` are skipped by their JUnit assumptions. To run those integration tests, install/start Docker or point `DYNAMODB_LOCAL_ENDPOINT` at a disposable DynamoDB Local instance.

The default local DynamoDB endpoint is `http://127.0.0.1:8000`. If that port is already occupied by another service, stop that service or configure a separate DynamoDB Local instance and set `DYNAMODB_LOCAL_ENDPOINT` accordingly. The script never removes a container it did not create.

## Run Maven directly

For a Java build and test run without starting local DynamoDB:

```bash
mvn --batch-mode clean verify -f application/pom.xml
```

DynamoDB integration tests are skipped unless `DYNAMODB_LOCAL_ENDPOINT` is configured. This is expected; unit tests still execute.

## AWS credentials are not needed for local tests

The DynamoDB integration tests use local endpoint configuration and dummy local credentials. They do not connect to AWS. Do not add real access keys, session tokens, customer data, production manifests or Terraform state to the repository.

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
| `mvn: command not found` | Install Maven 3.9+ and confirm `mvn -version` |
| Docker daemon unavailable | Start Docker Desktop/Engine; otherwise run Maven directly and note that DynamoDB integration tests are skipped |
| Port 8000 is already in use | Stop the conflicting service or configure a separate DynamoDB Local endpoint and export `DYNAMODB_LOCAL_ENDPOINT` |
| DynamoDB integration tests cannot connect | Confirm the endpoint is reachable and DynamoDB Local is ready before running Maven |
| Terraform formatting check is skipped | Install Terraform and ensure it is available on `PATH` |
| AWS CLI reports missing credentials | Local tests do not need AWS credentials; for AWS operations, use an administrator-approved IAM Identity Center profile or workload role |

## What local validation does not prove

A successful local build does not prove that an AWS deployment, IAM policy, S3 transfer, Step Functions workflow, service quota, network path, throughput target or production recovery procedure works in your account. Those require a separately approved non-production AWS pilot. See [Production readiness](production-readiness.md) and [Deployment validation](deployment-validation.md).
