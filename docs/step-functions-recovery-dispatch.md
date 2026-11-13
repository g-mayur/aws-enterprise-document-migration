# Step Functions recovery-wave dispatch

## Scope

The application now contains a fail-closed dispatcher that connects a previously acquired `RecoveryWaveLease` to a Standard Step Functions execution. It uses the deterministic execution name already stored in the wave and persists the exact state-machine ARN and SHA-256 digest of the exact JSON input before making the network call.

This is a reusable application component. It is not yet wired into an operator CLI, an automated scheduler, or the existing migration state machine as a recovery entry point. No AWS resources or IAM policies are changed by this milestone.

## Request binding

Before calling Step Functions, the dispatcher:

- Confirms that the JSON input is valid.
- Confirms the input `jobId` matches the recovery wave.
- Confirms the `shards` array contains exactly the selected shard numbers, with no missing or additional shard.
- Computes a SHA-256 digest over the exact input string.
- Uses a conditional DynamoDB update fenced by job, generation, owner token, and wave ID.
- Allows a repeated binding only when both the state-machine ARN and input digest match the first binding.

This prevents a retry from reusing the same execution name with a changed request body.

## Start and resolve behavior

The dispatcher calls `StartExecution` with the stored deterministic execution name and exact input. For Standard workflows, repeating the same name and input is the idempotent retry path while the execution is running.

If Step Functions reports that the execution name already exists, or the start call fails with an SDK-level ambiguous outcome, the dispatcher searches executions for the same state machine and exact execution name. If found, it calls `DescribeExecution` and verifies the returned input exactly matches the requested input before recording status.

The dispatcher maps authoritative execution statuses as follows:

- `RUNNING`: wave remains active and locked.
- `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `ABORTED`: wave is recorded terminal, allowing a later generation.
- Unsupported or unavailable status: recorded as `UNKNOWN`; lock remains active.

## Fail-closed rules

- An empty or temporarily incomplete `ListExecutions` result is not proof that an execution does not exist.
- A StartExecution timeout is not proof that AWS rejected the request.
- A DescribeExecution failure does not release the wave.
- An execution with a different input is treated as a mismatch and the wave remains `UNKNOWN`.
- A stale owner cannot update the wave because DynamoDB status updates remain generation-, owner-, and wave-fenced.
- The dispatcher never writes `NONEXISTENT` based solely on list results.

The selected shard set is validated before dispatch. The worker's conditional shard claim remains the final authority; this component does not claim shards, change attempt counts, clear leases, or finalize jobs.

## Validation and remaining integration

Unit tests cover successful start and authoritative terminal status, deterministic-name resolution of an existing execution, ambiguous timeout with no visible execution, and rejection of a mismatched shard set.

Before operational use, a later milestone must add a supported operator entry point and validate the complete recovery input against the prepared job, manifest fingerprint, source/destination settings, and contiguous registered shard set. It must also define the least-privilege IAM role and non-production AWS pilot procedure. CI tests do not prove AWS account behavior, and this change does not deploy or execute anything.
