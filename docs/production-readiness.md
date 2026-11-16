# Production readiness gates

A checked implementation item means the capability exists in the current starter code. It does **not** mean that capability has passed validation in the target AWS environment. Keep the pilot and production validation gates unchecked until evidence is recorded.

## Implementation present; target-environment validation still required

- [x] Sequential multipart upload implemented for large objects; production integration and load validation remain required.
- [x] DynamoDB conditional document claims, lease expiry/reclamation, attempt limits and lease-owned state transitions implemented as a repository foundation.
- [x] Sharded migration CLI integrated with durable document state, shard leases, deterministic assignment and job finalization; end-to-end pilot validation remains required.
- [x] Worker identity, shard/document lease renewal and fencing implemented; long-running and interrupted-worker behavior must still be exercised in the target environment.

## Implemented orchestration safeguards; target-environment validation still required

- [x] Step Functions retries the documented `AmazonECS.Unknown` RunTask rejection twice with exponential backoff; this is limited to ECS API launch rejections before a worker can claim a shard.

## Outstanding implementation work

- [x] Read-only recovery planning validates the prepared manifest fingerprint, S3 source settings and complete shard registration; explicit operator-confirmed recovery-wave dispatch uses a durable job lock and deterministic Standard Step Functions execution.
- [ ] Post-execution recovery reconciliation and operator-facing final shard summary implemented.
- [ ] Multipart upload resume across worker/process restarts implemented.
- [ ] Automatic scheduled dispatch/retry and reconciliation for failed or never-started shard workers implemented.
- [ ] Dead-letter reporting and operator replay workflow implemented.
- [ ] Production monitoring, alarms, dashboards and operational SLOs implemented.

## Pilot and production validation

- [ ] Connector tested against the actual source, including permissions, versions, deletions and throttling.
- [ ] Manifest sharding, replay and duplicate handling tested with representative data.
- [ ] Full-object SHA-256 semantics agreed with source; do not treat ETag as SHA-256.
- [ ] Destination key collision and overwrite policy approved and tested.
- [ ] IAM least privilege and secrets handling reviewed.
- [ ] TLS, bucket policy, encryption, access logging and retention reviewed.
- [ ] Network endpoints, NAT/egress and cross-region transfer costs assessed.
- [ ] Service quotas requested and validated in target Region.
- [ ] Load test completed with representative file-size distribution and source API behavior.
- [ ] Recovery, restart, reconciliation and rollback/restore rehearsed, including worker failure and expired-lease scenarios.
- [ ] Cost model includes requests, storage, data transfer, logs, DynamoDB, compute and backup.
- [ ] Legal, privacy, residency and records-retention requirements approved.

Do not describe this release as production-ready until all applicable implementation and validation gates pass and evidence is recorded.
