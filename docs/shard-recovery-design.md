# Shard recovery and reconciliation design

## Status

Proposed design. This document records the recovery boundary before adding automatic worker redispatch. It does not change application behavior or AWS infrastructure.

## Problem

The current orchestration starts one ECS worker for each prepared shard. PR #27 adds bounded retries for the optimized ECS `runTask.sync` error `AmazonECS.Unknown`, which represents an ECS RunTask launch rejection. That retry happens before a worker can claim the shard.

A worker can also fail after it starts. The durable shard state then has three materially different conditions:

| Durable state | Meaning | Safe recovery condition |
|---|---|---|
| `PENDING` | No worker has successfully claimed the shard. This includes exhausted ECS launch rejections. | Eligible to dispatch; the worker's conditional claim remains authoritative. |
| `FAILED` | A worker recorded a failure and released its lease. | Eligible only while fewer than three durable claims have been made. |
| `RUNNING` with an unexpired lease | A worker still owns the shard, or its disappearance has not yet been proven by lease expiry. | Do not dispatch a replacement. |
| `RUNNING` with an expired lease | The previous worker can no longer renew or complete the shard using its old fencing token. | Eligible for reclaim only while fewer than three durable claims have been made. |
| `COMPLETED` | Work is complete. | Never dispatch again. |
| `FAILED` at the attempt limit | The shard has exhausted its durable retry budget. | Terminal; report for operator review. |

## Existing safety guarantees

- `JobStateStore.MAX_SHARD_ATTEMPTS` is three successful shard claims, not three ECS task launches.
- `claimShard` conditionally claims `PENDING` and retryable `FAILED` shards, and reclaims `RUNNING` shards only after lease expiry.
- Every successful claim increments the persisted attempt count and receives a new fencing token.
- Lease renewal and terminal transitions require the current lease token and an unexpired lease. A stale worker cannot complete a shard after it has been reclaimed.
- Job finalization waits for all registered shards to be terminal. A retry-eligible failure, pending shard, or running shard blocks finalization.
- PR #27 retries only `AmazonECS.Unknown`; it deliberately does not retry broad task/application failures.

These guarantees are the source of truth. Orchestration retry counters must never be treated as a replacement for the DynamoDB attempt count.

## Recommended recovery architecture

Introduce a reconciliation phase that can be run after an orchestration wave fails or on an explicitly configured schedule:

1. Read the prepared job and its complete shard set from the job-state table using consistent reads.
2. Classify each shard using its persisted status, attempt count, and lease expiry.
3. Dispatch only `PENDING`, retryable `FAILED`, and expired `RUNNING` shards. Do not dispatch active, completed, or exhausted shards.
4. Let each dispatched worker call the existing conditional `claimShard` operation. The read performed by the reconciler is advisory; the atomic claim is the final authority. If two reconcilers race, at most one worker can own a given shard lease.
5. Keep the existing ECS launch-rejection retry separate from the durable worker-claim budget. A launch rejection does not consume a shard claim.
6. After workers finish, run job finalization again. The job becomes `COMPLETED` only when every shard completes, or `COMPLETED_WITH_ERRORS` when every shard is terminal and at least one has exhausted its attempts.
7. Report exhausted shards with their last error for operator review. Do not silently reset attempt counts or mutate DynamoDB records manually.

The shard read model should expose the lease expiry timestamp so the reconciler can distinguish an active `RUNNING` shard from an expired one. This is a read-model change only; the existing conditional claim and fencing-token writes remain authoritative.

## Retry and timing rules

- Never start a replacement while a `RUNNING` lease is still valid.
- Use the persisted lease expiry as the earliest safe reclaim time. Do not hard-code the default 300-second lease; deployments can configure a different duration.
- Keep ECS API launch retries bounded and separate from worker claim attempts.
- Do not add a broad Step Functions retry for `States.TaskFailed` or all ECS task failures. A failed task may have claimed a shard, and an abruptly terminated worker may leave a live lease until it expires.
- A reconciliation pass must be idempotent. Repeated passes may rediscover eligible shards, but only the conditional claim may grant ownership.
- Preserve the configured maximum concurrent shard count for every recovery wave.

## Implementation sequence

1. **Read-only visibility:** expose lease expiry in the shard read model and provide a job inspection/reporting operation that classifies shards without changing state.
2. **Recovery dispatch:** add a reconciliation entry point that selects eligible shards and dispatches workers while preserving the existing concurrency limit and immutable manifest fingerprint.
3. **Finalization and reporting:** make the recovery flow re-check durable state, finalize only when all shards are terminal, and produce an actionable report for exhausted shards.
4. **Automated validation:** cover pending launch rejection, retryable failure, active lease, expired lease, attempt exhaustion, concurrent reconcilers, duplicate worker starts, stale worker fencing, and mixed success/failure finalization.
5. **Target AWS pilot:** validate ECS launch rejection, worker process termination, lease expiry, duplicate dispatch, and recovery behavior in a non-production environment before enabling production use.

## Operator replay policy

An exhausted shard must not be made retryable by editing its DynamoDB item or resetting its attempt count. An operator replay mechanism needs an explicit, auditable policy (for example, a new recovery generation or a separately approved retry budget) and must preserve completed-document idempotency and manifest fingerprint checks. That policy is intentionally outside the first automatic-reconciliation implementation.

## Non-goals

- No AWS deployment or live migration.
- No automatic reset of exhausted shards.
- No increase to the three-claim budget.
- No assumption that ECS task launch retries and durable shard attempts are interchangeable.
- No production-readiness claim based on unit tests alone.


## First implementation slice: read-only inspection

The first implementation adds a read-only `inspect-job` CLI mode. It reports each shard's status, durable attempt count, worker, lease expiry, last error, and a conservative recovery assessment.

Example invocation:

```bash
java -jar document-migration-worker.jar \
  --mode inspect-job \
  --job-id JOB_ID \
  --job-state-table JOB_STATE_TABLE \
  --region AWS_REGION
```

The inspection does not claim shards, dispatch ECS tasks, or write to DynamoDB. A running shard with missing lease-expiry metadata is classified as unknown and is never recommended for immediate replacement. This command is an operator visibility tool, not an automatic recovery mechanism.
