# Recovery-wave reconciliation and job finalization

## Purpose

A terminal Step Functions execution is not proof that every migration shard reached a terminal state. ECS launch failures, worker crashes, exhausted retries, and still-active shard leases can leave durable shard records in different states.

The `reconcile-recovery` CLI mode verifies the terminal execution against the durable recovery-wave record, re-reads the complete current shard state using consistent DynamoDB reads, and then invokes the existing `JobFinalizationCoordinator`. The finalizer remains the only component that changes the job to `COMPLETED` or `COMPLETED_WITH_ERRORS`.

## Command

Run after the recovery execution has reached a terminal Step Functions status:

```bash
java -jar target/document-migration-worker.jar \
  --mode reconcile-recovery \
  --job-id job-001 \
  --job-state-table migration-job-state \
  --region us-east-1
```

The command does not accept a user-supplied execution ARN. It reads the latest wave record and uses the exact persisted execution ARN and state-machine ARN.

## Safety sequence

1. Read the latest recovery-wave item with a strongly consistent DynamoDB read.
2. Require the wave to be durably marked `TERMINAL` with a terminal execution status.
3. Call `DescribeExecution` for the exact persisted execution ARN.
4. Verify the returned execution ARN, state-machine ARN, and terminal status match the durable wave record. Any mismatch or unreadable execution blocks reconciliation.
5. Read the job and all registered shards through the existing consistent-read state store.
6. Classify every shard as completed, retryable, active, blocked due to unknown lease expiry, or attempts exhausted.
7. Call `JobFinalizationCoordinator.finalizeIfReady`. It verifies the complete contiguous shard registry and only finalizes when every shard is either completed or failed after the shard-attempt limit.
8. Mark the wave `RECONCILED` using a conditional update fenced by its generation and wave ID.

The recovery-wave slot cannot be acquired for another generation until the previous terminal wave is marked `RECONCILED`. This closes the gap between Step Functions finishing and the operator reviewing durable shard state. A failed or interrupted reconciliation leaves the slot unavailable; rerun reconciliation after investigating the cause. Do not edit or delete the wave item manually.

## Report fields

- `completed`: shards already completed.
- `retryable`: pending shards, failed shards with remaining claims, and expired running leases with remaining claims.
- `active`: running shards with an active lease.
- `blocked`: running shards whose lease expiry is unknown.
- `exhausted`: shards at the three-claim limit.
- `jobFinalization`: final job status, `WAITING_FOR_SHARDS`, or a not-ready job state.

The report reflects durable shard state at reconciliation time. Workers may still be running when an active lease is reported; do not dispatch a replacement for those shards. A subsequent recovery plan can be reviewed after the wave is reconciled and any active leases have expired.

## Operational notes

- Reconciliation does not reset shard attempts, clear leases, claim shards, or dispatch ECS tasks.
- It does not infer shard completion from the Step Functions result.
- The job finalizer remains conditional and idempotent. Completed shards produce `COMPLETED`; exhausted failed shards produce `COMPLETED_WITH_ERRORS` when all registered shards are terminal.
- A Step Functions execution status mismatch, active/unknown wave, missing execution identity, incomplete wave record, or stale reconciliation update fails closed.
- A terminal wave created before this gate was introduced must be reconciled before a new recovery generation can be acquired.
- CI uses mocked AWS clients and local fixtures. A non-production AWS pilot is still required before operational use.
- No Terraform, IAM policy, AWS resource, deployment, or live migration is changed by this milestone.
