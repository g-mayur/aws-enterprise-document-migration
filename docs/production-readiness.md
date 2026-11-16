# Production readiness gates

A checked implementation item means the capability exists in the current starter code. It does **not** mean that capability has passed validation in the target AWS environment. Keep the pilot and production validation gates unchecked until evidence is recorded.

## Implementation present; target-environment validation still required

- [x] Sequential multipart upload implemented for large objects; production integration and load validation remain required.
- [x] Durable multipart checkpoint model and lease-fenced DynamoDB persistence implemented in the existing document-state table; S3 writer resume integration remains outstanding.
- [x] DynamoDB conditional document claims, lease expiry/reclamation, attempt limits and lease-owned state transitions implemented as a repository foundation.
- [x] Sharded migration CLI integrated with durable document state, shard leases, deterministic assignment and job finalization; end-to-end pilot validation remains required.
- [x] Worker identity, shard/document lease renewal and fencing implemented; long-running and interrupted-worker behavior must still be exercised in the target environment.
- [x] Read-only recovery planning, immutable recovery configuration, operator-confirmed dispatch, exact-execution reconciliation, immutable audit history and bounded foreground automatic recovery are implemented as starter capabilities. AWS integration and operational behavior remain unvalidated in a target account.

## Outstanding implementation work

- [ ] Multipart upload resume across worker/process restarts implemented.
- [ ] Unattended scheduled dispatch/retry and reconciliation implemented. This is intentionally not included in the current release; the controller is foreground and explicit opt-in only.
- [x] Dedicated dead-letter reporting and exhausted-shard manual-review metadata implemented in the existing job-state table; operational ownership and target-environment procedures still require validation.
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

## Recovery implementation boundary

The `auto-recover` command runs a finite sequence of dispatch, exact-execution polling, audit-backed reconciliation and fresh planning. It requires explicit confirmations and enforces wave, polling and runtime limits. It does not create AWS resources, schedule itself, reset attempts, clear leases or replay exhausted shards. A non-production AWS pilot and production monitoring/operational gates remain required.
