# Operator recovery workflow

## Scope and safety

The CLI provides read-only planning, explicitly confirmed single-wave dispatch, audit-backed reconciliation, and an explicitly invoked bounded controller, plus `configure-recovery` to bind immutable execution settings to a prepared job:

- `configure-recovery`: validates the S3 manifest fingerprint and Standard state machine, then conditionally stores the immutable recovery configuration for a `READY` job.
- `plan-recovery`: read-only validation and shard eligibility report.
- `dispatch-recovery`: starts one recovery wave only after explicit operator confirmation.
- `reconcile-recovery`: verifies the terminal execution and commits the durable reconciliation audit.
- `auto-recover`: runs a finite sequence of dispatch, exact-execution polling, reconciliation, and fresh planning passes under explicit wave and runtime ceilings.

The workflow validates the prepared job, exact manifest SHA-256 fingerprint, source bucket and prefix recorded by the S3 manifest, and complete contiguous shard registration. It selects only shards classified as pending, retryable failures, or running with an expired lease and remaining shard claims.

It does not reset shard attempts, clear leases, claim shards, or change shard status. The existing worker-side conditional `claimShard` remains the final authority. A shard can change after planning; a worker that cannot claim it must not process it.

No Terraform, IAM role, AWS resource, deployment, or live migration is changed by this code. Configure the separate operator permissions in the IAM policy example below and review them before use.

## Required operator inputs

The prepared job stores the immutable manifest fingerprint and shard plan. The separate immutable recovery-configuration item now stores the source/destination settings and execution limits after `configure-recovery` succeeds. The `dispatch-recovery` command remains operator-driven, but now loads all execution settings from this immutable record. It accepts only the job ID, job-state table, AWS Region, and explicit safety confirmations; source, destination, manifest, state-machine, version, lease, and attempt settings are not accepted as dispatch overrides.

- Job ID and job-state DynamoDB table.
- Exact manifest S3 URI.
- Source S3 bucket and source prefix. The bucket and prefix are checked against every record in the manifest.
- Document-state DynamoDB table.
- Destination S3 bucket.
- Version policy used for this migration.
- Worker lease duration and document-attempt limit used for this migration.
- Standard Step Functions state machine ARN for dispatch.
- Explicit confirmation that any previous execution for this job has reached a terminal state.

The destination bucket, version policy, lease duration, document-attempt limit, concurrency ceiling, and state-machine ARN are not derivable from the prepared job. The configure command records the operator-supplied values as the immutable recovery configuration, so verify them against the original migration deployment before binding. Dispatch reads these values from DynamoDB and rejects a mismatched job-state table or runtime Region. It also recalculates the manifest fingerprint before acquiring the recovery-wave lock.

The manifest must be an S3-source manifest with an explicit immutable source version or snapshot identity for each record. Legacy records without this identity are rejected.

## Step 0: bind immutable recovery settings

After `prepare-job` completes successfully, bind the exact recovery settings once:

```bash
java -jar target/document-migration-worker.jar \
  --mode configure-recovery \
  --job-id job-001 \
  --job-state-table migration-job-state \
  --manifest-s3-uri s3://migration-staging/manifests/job-001.jsonl \
  --source-bucket source-bucket \
  --source-prefix "exports/" \
  --destination-bucket destination-bucket \
  --state-table migration-document-state \
  --version-policy ALL_VERSIONS \
  --lease-seconds 300 \
  --max-attempts 5 \
  --max-concurrent-shards 20 \
  --state-machine-arn arn:aws:states:us-east-1:123456789012:stateMachine:document-migration-shard-orchestration \
  --region us-east-1
```

The command downloads the specified S3 manifest, recalculates its SHA-256 fingerprint, validates the source bucket/prefix and complete registered shard plan, verifies it matches the prepared `READY` job, and verifies the state machine is Standard. It then atomically writes a separate `RECOVERY_CONFIG` item only if the job remains `READY` with that fingerprint. The DynamoDB transaction prevents configuration binding to a missing, unprepared, or differently fingerprinted job.

The configuration includes source bucket/prefix, destination bucket, manifest URI and fingerprint, document/job state tables, state-machine ARN, version policy, Region, worker lease duration, document-attempt limit, and maximum shard concurrency. The concurrency value must match the deployed Step Functions Map configuration; this command records the value but does not inspect the definition's Map state.

Repeating the exact same configuration is safe. A different configuration for a job that already has one is rejected; do not delete or edit the item to force a change. This command does not start workers or enable unattended recovery.

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
  --confirm-prior-execution-terminal true \
  --job-id job-001 \
  --job-state-table migration-job-state \
  --region us-east-1
```

The command:

1. Repeats all job, manifest, source and shard-plan validation.
2. Confirms the supplied state machine is a Standard workflow and checks for running executions associated with the same job.
3. Requires the operator to confirm that the prior execution is terminal because Step Functions execution listings are eventually consistent.
4. Builds the exact input shape expected by `infrastructure/orchestration.tf`, including a complete worker CLI command for each selected shard.
5. Acquires the durable job-scoped recovery-wave lock in the existing job-state table.
6. Starts or resolves the deterministic Step Functions execution using the fail-closed dispatcher.

The Map concurrency ceiling remains the value configured in the deployed state machine. Its intended value is stored in the immutable recovery configuration; the operator command does not override it. The generated execution input is checked against the Step Functions 256 KiB payload limit before the recovery-wave lock is acquired.

If another recovery wave is active, or a running Step Functions execution for the same job is found, no new execution is started. The execution-list preflight is eventually consistent; before dispatch, independently verify in Step Functions that the previous execution for this job is terminal, then provide the explicit confirmation flag. If the Step Functions start outcome cannot be confirmed, the wave remains ACTIVE/UNKNOWN and the command reports that manual inspection is required. Do not delete or edit the recovery-wave record to force another run.

## IAM permissions for the operator

Attach a reviewed policy like the following to the operator identity or role used to run the CLI. Replace every placeholder with the exact resource ARN. This is a permissions example, not an automatically applied policy.

- DynamoDB read permissions inspect the prepared job and shard records.
- DynamoDB update permission is limited to the existing job-state table, where the recovery-wave coordination item is stored.
- S3 read permission is limited to the exact manifest object or a narrowly scoped manifest prefix.
- Step Functions permissions are limited to the selected state machine and its execution resources.

See [recovery-operator-policy.json](recovery-operator-policy.json).

## Important operational limitations

- Source bucket and prefix are verified against the manifest. Destination bucket and version policy are stored in the immutable recovery configuration because they are not stored in the prepared job.
- The exact manifest is re-verified by workers before they claim shards. If the S3 object changes after planning, workers reject the changed fingerprint.
- A recovery wave starting successfully does not prove any shard was claimed or migrated.
- The `auto-recover` mode observes the exact persisted execution ARN, writes only status observations fenced by wave generation and identity, and invokes the existing audit-backed reconciliation before another plan. If execution identity/status is ambiguous or the runtime expires, it stops and leaves the wave locked.
- Do not treat a terminal Step Functions status as proof that all shards are terminal.
- CI tests use mocked AWS clients and local test fixtures. They do not prove behavior in an AWS account. Run a small non-production pilot before operational use.
- No production data, AWS deployment, or live migration is accessed or started by CI.


## Step 3: reconcile the terminal recovery execution

After the Step Functions execution reaches a terminal status, run the reconciliation command before planning or dispatching another recovery wave:

```bash
java -jar target/document-migration-worker.jar \\
  --mode reconcile-recovery \\
  --job-id job-001 \\
  --job-state-table migration-job-state \\
  --region us-east-1
```

The command reads the execution ARN and state-machine ARN from the durable recovery-wave record, verifies the exact terminal execution with Step Functions, and re-reads every registered shard. It invokes the existing job finalizer only when all shards are terminal. The recovery-wave slot is not available for another generation until this reconciliation is recorded. See [recovery-wave-reconciliation.md](recovery-wave-reconciliation.md) for the report fields, safety gates, and failure handling. The final wave unlock and an immutable per-generation audit item are committed in one DynamoDB transaction. The audit item records the execution status, shard disposition counts, reconciliation time, and job finalization result. If the process stops before that transaction commits, the wave remains unreconciled and the command can be safely retried. Concurrent attempts are fenced so only one can commit the audit and unlock.

## Step 4: run the bounded controller (explicit opt-in)

The controller is a foreground CLI operation, not a scheduler or background service. It still requires the same explicit confirmations as a single-wave dispatch and applies hard limits to the number of waves, polling interval, and total runtime:

```bash
java -jar target/document-migration-worker.jar \
  --mode auto-recover \
  --confirm-recovery true \
  --confirm-prior-execution-terminal true \
  --job-id job-001 \
  --job-state-table migration-job-state \
  --max-waves 3 \
  --poll-interval-seconds 5 \
  --max-runtime-seconds 900 \
  --region us-east-1
```

Bounds are enforced by the CLI: 1–10 waves, 1–30 seconds between polls, and 30–3600 seconds total runtime. Each wave uses the persisted immutable configuration and existing job-scoped lock. The controller waits on the exact execution ARN stored for that wave, persists status only when the wave generation, ID, and ARN still match, then performs audit-backed reconciliation before generating a fresh plan.

The controller stops when the job is terminal, no eligible shards remain, active leases or unknown lease expiry are present, the wave ceiling or runtime ceiling is reached, or an execution identity/status cannot be verified. A timeout, AWS API error, or ambiguous execution leaves the current wave locked for manual inspection; do not clear the lock or start another wave. Exhausted shards are never reset or replayed.

This is not permission to schedule unattended production recovery. Validate the controller with DynamoDB Local tests and a controlled non-production AWS pilot before considering any scheduled use.

## Operational enablement remains gated

The `auto-recover` command is explicitly invoked and bounded, but it is not a background scheduler and must not be scheduled unattended. Production enablement remains gated on the full validation and non-production AWS pilot described in shard-recovery-design.md. Continue to review the job configuration and require explicit authorization before each controller run.