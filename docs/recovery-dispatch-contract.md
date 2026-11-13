# Recovery dispatch contract

## Status

Design contract for the next recovery implementation. This document does not change application behavior, IAM policies, Terraform, or AWS resources. It defines the safety conditions that an implementation must satisfy before it can dispatch replacement workers.

## Why a dispatch contract is needed

The read-only `inspect-job` operation can identify shards that appear eligible for recovery. That assessment is advisory: the shard can change between inspection and dispatch, and multiple operators or automated processes can inspect the same job at the same time.

The existing conditional `claimShard` operation remains the final authority for worker ownership. However, that alone does not enforce a job-wide concurrency limit when two independent Step Functions executions are started for the same job. Each execution could independently respect `max_concurrent_shards` while their combined worker count exceeds the configured limit.

Recovery therefore needs coordination at both levels:

- **Shard ownership:** enforced by the existing conditional DynamoDB claim and fencing token.
- **Recovery-wave ownership:** enforced by a durable, job-scoped coordination record so that only one recovery wave can be active for a job at a time.

## Required dispatch input

A recovery request must be bound to the already prepared job and its immutable manifest:

- Job ID and job-state table.
- Expected total shard count.
- Manifest location and SHA-256 fingerprint.
- Source bucket and prefix.
- Destination bucket.
- State machine ARN and configured maximum shard concurrency.
- A unique recovery-wave generation and idempotency identity.

The dispatcher must verify that the job is in a recoverable non-terminal state, the manifest fingerprint matches the prepared job, and the complete contiguous shard set is registered before constructing a dispatch request. It must not infer source or destination details from shard state.

## Eligibility rules

Use the persisted shard state and the same classification rules as `ShardRecoveryInspector`:

| Shard state | Dispatch eligibility |
|---|---|
| `PENDING` below the claim limit | Eligible |
| `FAILED` below the claim limit | Eligible |
| `RUNNING` with an unexpired lease | Not eligible |
| `RUNNING` with an expired lease and remaining claims | Eligible |
| `RUNNING` with missing lease expiry | Not eligible; operator investigation required |
| `COMPLETED` | Never eligible |
| `FAILED` at the claim limit | Never eligible |

The dispatcher must select only eligible shard numbers. It must never reset `attemptCount`, change shard status, clear a lease, or directly claim a shard on behalf of a worker.

Eligibility is a snapshot, not a reservation. A worker must still call `claimShard`; a rejected claim is an expected safe outcome when state changes between planning and execution.

## Recovery-wave coordination

Before starting a recovery execution, the dispatcher must acquire a durable job-scoped recovery-wave record using a conditional write. The record must include at least:

- Job ID.
- Recovery generation.
- Unique owner token.
- State machine ARN and deterministic execution name.
- Selected shard set or its stable digest.
- Creation time and current wave status.

The conditional write must prevent a second dispatcher from creating another active wave for the same job. A concurrent request must return the existing active wave or a clear "recovery already in progress" result; it must not start another execution.

The coordination record must not be released merely because the dispatcher process exits or a client-side timeout occurs. It may be marked complete only after the Step Functions execution is confirmed terminal and the final durable shard state has been re-read. If a dispatcher crashes after recording the wave but before receiving the `StartExecution` response, a retry must resolve the deterministic execution identity before attempting a new generation.

A stale-wave takeover must first establish that the associated Step Functions execution is terminal or does not exist. Lease expiry alone is insufficient evidence that worker tasks have stopped. Never allow an expired coordination lease to create overlapping worker waves while the prior execution may still be active.

## Step Functions execution requirements

- Dispatch one recovery wave as one Step Functions execution, with only the selected shard entries in its Map input.
- Keep `MaxConcurrency` at the configured job limit; do not create one execution per shard.
- Preserve the existing ECS launch-rejection retry for `AmazonECS.Unknown` only.
- Do not add broad retries for `States.TaskFailed` or arbitrary ECS task failures.
- Pass the same job ID, manifest location, shard count, and source/destination settings used by the prepared job.
- Keep the worker's conditional claim as the final ownership check.
- Do not interpret a successful `StartExecution` response as proof that any shard was claimed or completed.

If a worker cannot claim a shard because it is already owned, completed, or no longer eligible, that worker must exit without modifying another worker's state. The orchestration result must make that outcome visible rather than silently treating the shard as migrated.

## Completion and finalization

After the recovery execution reaches a terminal state:

1. Read the job and complete shard set again using consistent reads.
2. Reclassify every shard using the persisted attempt count and lease expiry.
3. Do not finalize while any shard is pending, running, retryable, or has an unknown lease expiry.
4. Call the existing finalization coordinator only when all registered shards are terminal under the current policy.
5. Record the recovery-wave outcome and report completed, failed, still-active, and exhausted shards.
6. Preserve the immutable manifest fingerprint and existing completed-document idempotency behavior.

A Step Functions execution that failed does not, by itself, prove that every shard failed. Durable DynamoDB state is the source of truth.

## Failure handling and auditability

The implementation must define behavior for:

- Two dispatchers racing to start a wave.
- A dispatcher crashing before or after `StartExecution`.
- A client timeout when the execution may have started successfully.
- A Step Functions execution that fails while one or more ECS tasks remain active.
- A worker that starts but cannot claim its shard.
- A worker that disappears while its shard lease is active.
- A recovery wave that ends with a mixture of completed, retryable, and exhausted shards.

Every wave must have a stable identity and auditable timestamps, selected shard set, execution ARN, terminal execution status, and final shard summary. Do not log credentials, document contents, or sensitive source metadata.

## Validation required before implementation is considered ready

Automated tests must cover:

- Eligibility for every shard disposition, including unknown lease expiry.
- Complete and contiguous shard registration and manifest fingerprint mismatch.
- Concurrent recovery-wave acquisition; exactly one caller succeeds.
- Idempotent resolution after a timeout around `StartExecution`.
- Refusal to take over a wave while its execution is running or its status is unknown.
- Preservation of configured concurrency in the Map input.
- Worker claim rejection after state changes between planning and execution.
- No attempt-count reset and no direct shard mutation by the dispatcher.
- Finalization only after a fresh durable-state read confirms all shards terminal.
- Mixed outcomes and exhausted-shard reporting.

The non-production AWS pilot must then test launch rejection, duplicate dispatch attempts, forced worker termination, lease expiry, stale-worker fencing, and recovery-wave completion. CI validation alone is not evidence that these AWS behaviors have been verified.

## Explicit non-goals

- No automatic increase to the three-claim limit.
- No manual reset of exhausted shards.
- No broad ECS task retry.
- No parallel recovery executions for one job.
- No deployment, live migration, or production-data access as part of this design change.
