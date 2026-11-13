# Recovery-wave coordination foundation

## Scope

The recovery-wave coordination store provides a durable, job-scoped lock in the existing DynamoDB job-state table. It is the first implementation step for safe recovery dispatch; it does not start Step Functions executions, launch ECS workers, or change shard records.

The coordination item uses the job partition key and the `RECOVERY_WAVE` sort key. This keeps the lock in the same durable state table without requiring a new table or Terraform change.

## Acquisition rules

- A job can have only one active recovery wave at a time.
- Acquisition is one conditional DynamoDB update. The condition allows a new item or a previous item whose state is terminal and whose execution status is confirmed terminal.
- The generation is incremented atomically. Every update must match the generation, owner token, and wave ID.
- The selected shard numbers are validated, sorted, persisted, and fingerprinted with SHA-256.
- Acquiring a wave does not claim shards, increment shard attempts, clear leases, or modify shard status.
- The worker's existing conditional shard claim remains the final authority when a worker eventually starts.

## Fail-closed execution tracking

A newly acquired wave is stored as `ACTIVE` with execution status `UNKNOWN`. This is intentional. If a future dispatcher times out while calling Step Functions `StartExecution`, it cannot safely assume that the execution was not created. The unknown status therefore retains the job-wide lock.

`RUNNING` also retains the lock. Only `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `ABORTED`, or `NONEXISTENT` can move the coordination item to terminal state. A caller must use an authoritative Step Functions execution status check, or confirm that the deterministic execution identity does not exist, before recording one of these terminal outcomes.

Stale owners are fenced out by generation, owner token, and wave ID. An old coordinator cannot update a later generation.

## Deliberate limitations in this milestone

- No Step Functions `StartExecution` or `DescribeExecution` calls are made.
- No automatic stale-wave takeover or timeout-based lock expiry is implemented.
- No ECS dispatch or worker retry behavior is changed.
- No attempt count is reset or increased by the coordinator.
- No AWS infrastructure is deployed or modified.

The next milestone can integrate deterministic Step Functions execution identity and status resolution on top of these persistence primitives.
