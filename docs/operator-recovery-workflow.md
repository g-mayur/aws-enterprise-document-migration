# Operator recovery workflow

## Scope and safety

The CLI now provides two recovery modes:

- `plan-recovery`: read-only validation and shard eligibility report.
- `dispatch-recovery`: starts one recovery wave only after explicit operator confirmation.

The workflow validates the prepared job, exact manifest SHA-256 fingerprint, source bucket and prefix recorded by the S3 manifest, and complete contiguous shard registration. It selects only shards classified as pending, retryable failures, or running with an expired lease and remaining shard claims.

It does not reset shard attempts, clear leases, claim shards, or change shard status. The existing worker-side conditional `claimShard` remains the final authority. A shard can change after planning; a worker that cannot claim it must not process it.

No Terraform, IAM role, AWS resource, deployment, or live migration is changed by this code. Configure the separate operator permissions in the IAM policy example below and review them before use.

## Required operator inputs

The job record stores the immutable manifest fingerprint and shard plan, but it does not store the source prefix, destination bucket, or version policy. These values must be supplied explicitly:

- Job ID and job-state DynamoDB table.
- Exact manifest S3 URI.
- Source S3 bucket and source prefix. The bucket and prefix are checked against every record in the manifest.
- Document-state DynamoDB table.
- Destination S3 bucket.
- Version policy used for this migration.
- Worker lease duration and document-attempt limit used for this migration.
- Standard Step Functions state machine ARN for dispatch.

The destination bucket, version policy, lease duration and document-attempt limit cannot be verified against the prepared job because they are not persisted in its record. Supply the original values explicitly and confirm they match the original migration configuration before dispatch. The worker still verifies the prepared manifest fingerprint before claiming its shard.

The manifest must be an S3-source manifest with an explicit immutable source version or snapshot identity for each record. Legacy records without this identity are rejected.

## Step 1: review a read-only plan

Run from the application directory:

```bash
java -jar target/document-migration-worker.jar \
  --mode plan-recovery \
  --job-id job-001 \
  --job-state-table migration-job-state \
  --manifest-s3-uri s3://migration-staging/manifests/job-001.jsonl \
  --source-bucket source-bucket \
  --source-prefix "exports/" \
  --region us-east-1
```

For a bucket-root source, pass an explicitly empty prefix: `--source-prefix ""`.

The command downloads the exact manifest to a temporary local file, checks its fingerprint against the prepared job, verifies source bucket/prefix metadata, and reads the complete shard set consistently from DynamoDB. The temporary manifest file is deleted after inspection.

Review every shard disposition. Active leases, unknown lease expiry, completed shards, and exhausted shards are not selected. If no shards are eligible, dispatch is refused.

## Step 2: explicitly confirm one recovery wave

Only after reviewing the plan, run:

```bash
java -jar target/document-migration-worker.jar \
  --mode dispatch-recovery \
  --confirm-recovery true \
  --job-id job-001 \
  --job-state-table migration-job-state \
  --state-table migration-document-state \
  --manifest-s3-uri s3://migration-staging/manifests/job-001.jsonl \
  --source-bucket source-bucket \
  --source-prefix "exports/" \
  --destination-bucket destination-bucket \
  --version-policy ALL_VERSIONS \
  --lease-seconds 300 \
  --max-attempts 5 \
  --state-machine-arn arn:aws:states:us-east-1:123456789012:stateMachine:document-migration-shard-orchestration \
  --region us-east-1
```

The command:

1. Repeats all job, manifest, source and shard-plan validation.
2. Confirms the supplied state machine is a Standard workflow.
3. Builds the exact input shape expected by `infrastructure/orchestration.tf`, including a complete worker CLI command for each selected shard.
4. Acquires the durable job-scoped recovery-wave lock in the existing job-state table.
5. Starts or resolves the deterministic Step Functions execution using the fail-closed dispatcher.

The Map concurrency ceiling remains the value configured in the deployed state machine. The operator command does not override it. The generated execution input is checked against the Step Functions 256 KiB payload limit before the recovery-wave lock is acquired.

If another recovery wave is active, no new execution is started. If the Step Functions start outcome cannot be confirmed, the wave remains ACTIVE/UNKNOWN and the command reports that manual inspection is required. Do not delete or edit the recovery-wave record to force another run.

## IAM permissions for the operator

Attach a reviewed policy like the following to the operator identity or role used to run the CLI. Replace every placeholder with the exact resource ARN. This is a permissions example, not an automatically applied policy.

- DynamoDB read permissions inspect the prepared job and shard records.
- DynamoDB update permission is limited to the existing job-state table, where the recovery-wave coordination item is stored.
- S3 read permission is limited to the exact manifest object or a narrowly scoped manifest prefix.
- Step Functions permissions are limited to the selected state machine and its execution resources.

See [recovery-operator-policy.json](recovery-operator-policy.json).

## Important operational limitations

- Source bucket and prefix are verified against the manifest. Destination bucket and version policy are operator-supplied because they are not stored in the prepared job.
- The exact manifest is re-verified by workers before they claim shards. If the S3 object changes after planning, workers reject the changed fingerprint.
- A recovery wave starting successfully does not prove any shard was claimed or migrated.
- This milestone does not automatically re-read shard state or invoke the existing job finalization coordinator after the execution. Reconcile the durable job and shard state manually after the execution; the next milestone will automate this integration.
- Do not treat a terminal Step Functions status as proof that all shards are terminal.
- CI tests use mocked AWS clients and local test fixtures. They do not prove behavior in an AWS account. Run a small non-production pilot before operational use.
- No production data, AWS deployment, or live migration is accessed or started by CI.
