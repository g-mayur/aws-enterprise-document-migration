# Production readiness gates

- [ ] Connector tested against the actual source, including permissions, versions, deletions and throttling.
- [ ] Manifest sharding and replay behavior tested; duplicate events are safe.
- [x] Sequential multipart upload implemented for large objects; production integration and load validation remain required.
- [ ] Multipart upload resume across worker/process restarts implemented.
- [ ] Full-object SHA-256 semantics agreed with source; do not treat ETag as SHA-256.
- [ ] Conditional DynamoDB claims, lease expiry, retry policy and dead-letter handling implemented.
- [ ] Destination key collision and overwrite policy approved.
- [ ] IAM least privilege and secrets handling reviewed.
- [ ] TLS, bucket policy, encryption, access logging and retention reviewed.
- [ ] Network endpoints, NAT/egress and cross-region transfer costs assessed.
- [ ] Service quotas requested and validated in target Region.
- [ ] Load test uses representative file-size distribution and source API behavior.
- [ ] Recovery, restart, reconciliation and rollback/restore rehearsed.
- [ ] Cost model includes requests, storage, data transfer, logs, DynamoDB, compute and backup.
- [ ] Legal, privacy, residency and records-retention requirements approved.
