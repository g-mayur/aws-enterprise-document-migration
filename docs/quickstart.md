# AWS migration quickstart

This guide covers actual AWS-backed migration execution. If you only want to clone the repository and run its build and automated tests, start with [Local development and validation](local-development.md). Local validation does not require AWS credentials.

## Before using AWS

Actual migration operations require:

- An AWS account and an approved non-production environment.
- AWS CLI v2 and an approved identity (IAM Identity Center profile or workload role).
- An S3 destination bucket with the required encryption and access configuration.
- DynamoDB document-state and job-state tables for durable sharded execution.
- Step Functions and the supporting infrastructure when using recovery dispatch.
- Source access and destination/state permissions appropriate to the selected connectors and execution mode.

Do not use production data for the first pilot. Confirm region, quotas, security, retention, expected cost and rollback approach with the account owner before provisioning resources.

## Configure credentials

For IAM Identity Center, obtain the approved SSO start URL, SSO Region, account and role from your AWS administrator. Configure a named profile:

```bash
aws configure sso --profile migration-dev
aws sso login --profile migration-dev
export AWS_PROFILE=migration-dev
aws sts get-caller-identity
```

Use the approved Region explicitly in commands. For workloads running on AWS, prefer a scoped ECS task role or EC2 instance role. Avoid long-lived access keys and never commit credentials or local AWS configuration files.

If you do not have an AWS account or an approved role, do not proceed with the AWS sections below. You can still complete all local build and test steps in the local development guide.

## Basic filesystem-to-S3 migration

1. Build and test the application: `mvn --batch-mode clean verify -f application/pom.xml`.
2. Provision or select an S3 destination bucket with SSE-S3 enabled.
3. Prepare a JSONL manifest. Use `examples/manifests/sample.jsonl` as a template. Each line is one document-version record.
4. Run the CLI from `application/` with the required options:

```bash
java -jar target/document-migration-worker.jar \
  --source-type filesystem \
  --source-root /absolute/path/to/source \
  --destination-bucket YOUR_DESTINATION_BUCKET \
  --manifest ../examples/manifests/sample.jsonl \
  --version-policy ALL_VERSIONS \
  --region us-east-1
```

The sample manifest's `relativePath` must match the source-relative file path.

The current CLI stages each document to a temporary local file for checksum-then-upload to avoid loading the object into heap; ensure the worker has adequate encrypted ephemeral disk. Large-object uploads use sequential multipart streaming above 64 MiB and spool one part at a time to temporary disk. In context-backed sharded execution, multipart checkpoints are persisted under the active document lease and can be resumed after a worker/process restart by reconciling the checkpoint with S3's uploaded parts. This behavior has automated LocalStack coverage, but still requires validation in the target AWS environment.

## Run a durable sharded migration

The CLI supports an opt-in sharded execution mode. It uses two separate DynamoDB tables: the document-state table (`--state-table`) and the job/shard lifecycle table (`--job-state-table`). This matches the Terraform reference architecture.

First prepare a JSONL manifest and ensure both tables exist. Prepare the job once before starting workers. This fingerprints the exact manifest bytes, binds the fingerprint to the job ID and registers the complete shard plan:

```bash
java -jar target/document-migration-worker.jar \
  --mode prepare-job \
  --job-state-table migration-job-state \
  --job-id job-001 \
  --total-shards 16 \
  --manifest /data/manifests/job-001.jsonl \
  --region us-east-1
```

Then start one worker for each shard. Each worker receives the same job ID, manifest, shard count and table names, but a different shard number and worker ID:

```bash
java -jar target/document-migration-worker.jar \
  --source-type filesystem \
  --source-root /data/export \
  --destination-bucket my-destination \
  --manifest /data/manifests/job-001.jsonl \
  --version-policy ALL_VERSIONS \
  --state-table migration-document-state \
  --job-state-table migration-job-state \
  --job-id job-001 \
  --total-shards 16 \
  --shard-number 0 \
  --worker-id worker-0 \
  --lease-seconds 300 \
  --max-attempts 5 \
  --region us-east-1
```

Run additional workers with `--shard-number` values from `1` through `15` and unique worker IDs. All workers must use the exact same manifest file bytes and the same `--total-shards` value. Workers verify the prepared fingerprint and registered shard plan before claiming their assigned shard; they do not attempt to prepare the job themselves.

A worker executes one shard and records that shard as COMPLETED or FAILED. The per-shard worker command does not dispatch other workers or automatically retry failed shards. Use the separate [operator recovery workflow](operator-recovery-workflow.md) to plan and explicitly dispatch an eligible recovery wave. Post-execution reconciliation remains a separate step.

## Related guides

- [Local development and validation](local-development.md)
- [Deployment validation](deployment-validation.md)
- [Operator recovery workflow](operator-recovery-workflow.md)
- [Production readiness](production-readiness.md)
