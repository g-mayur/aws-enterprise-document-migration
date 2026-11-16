# Shard recovery and reconciliation design

## Status

The original recovery design described below has been implemented as a guarded operator workflow and bounded foreground controller. The current starter includes read-only inspection/planning, immutable recovery configuration, operator-confirmed dispatch, exact-execution reconciliation, immutable audit history and bounded automatic recovery. See [operator-recovery-workflow.md](operator-recovery-workflow.md) for the current commands and [implementation-status.md](implementation-status.md) for the capability inventory.

Implementation does not establish target-account validation or production readiness. No scheduler, background service or AWS deployment is included.

## Problem and recovery boundary

The orchestration starts one ECS worker for each prepared shard. The bounded Step Functions retry for `AmazonECS.Unknown` covers ECS RunTask launch rejection before a worker can claim a shard. A worker can also fail after it starts, so recovery must use durable shard state rather than treating an orchestration retry counter as the source of truth.

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
- ECS launch-rejection retries remain separate from durable worker-claim attempts.

## Implemented recovery workflow

The workflow is a finite controller around the existing planner, dispatcher and reconciliation coordinator:

1. Load and validate the immutable job recovery configuration.
2. Build a fresh read-only recovery plan from the exact manifest fingerprint and complete registered shard set.
3. Stop if no shard is eligible; active leases, unknown lease expiry and exhausted shards are not dispatched.
4. Acquire the existing job-scoped recovery-wave lock before dispatch.
5. Dispatch only selected shard numbers. The worker-side conditional claim remains authoritative.
6. Observe only the exact persisted Step Functions execution identity. Ambiguous identity or status leaves the wave locked and stops the controller.
7. Reconcile terminal execution using strongly consistent shard reads, the guarded finalizer and the atomic immutable audit transaction.
8. Continue only after reconciliation commits and a fresh plan confirms eligible work remains.
9. Stop at the wave/runtime ceiling, on a safety failure, or when no eligible work remains.

The CLI limits the controller to 1–10 waves, 1–30 seconds between polls and 30–3600 seconds total runtime. It is explicitly invoked in the foreground and is not a scheduler.

## Mandatory invariants

- DynamoDB's three total shard claims remain authoritative. Controller iterations and ECS launch retries do not increase that budget.
- Never dispatch a RUNNING shard with an unexpired lease or unknown lease expiry.
- Never reset attempt counts, clear leases, or replay an exhausted shard automatically.
- Never overlap recovery waves for one job. A wave slot is reusable only after its audit-backed reconciliation transaction commits.
- Preserve the configured Step Functions Map concurrency ceiling; do not infer or raise it from selected shard count.
- Bind worker execution settings immutably to the prepared job before recovery dispatch.
- A terminal Step Functions status is not proof that all shards are terminal. Durable shard state and the existing finalizer remain authoritative.

## Operator replay policy

An exhausted shard must not be made retryable by editing its DynamoDB item or resetting its attempt count. A future operator replay mechanism needs an explicit, auditable policy and must preserve completed-document idempotency and manifest fingerprint checks. That policy is outside the current automatic-recovery implementation.

## Non-goals and validation gate

- No AWS deployment or live migration is performed by the recovery CLI.
- No automatic reset of exhausted shards or increase to the three-claim budget.
- No unattended scheduler or background recovery service.
- No production-readiness claim based on unit tests or CI alone.

A controlled non-production AWS pilot must validate worker termination, lease expiry, duplicate dispatch, exact-execution observation, reconciliation and audit behavior before operational use.
