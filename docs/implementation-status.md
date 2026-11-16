# Implementation status

This document describes the current starter implementation. A successful build does not establish production readiness.

| Capability | Status | Notes |
|---|---|---|
| Connector SPI | Implemented (starter) | Java interface and capability declarations available |
| Filesystem source | Implemented (starter) | Discovers files as single logical versions |
| S3 source discovery | Implemented (starter) | Supports current-object snapshots in unversioned buckets and version enumeration in enabled/suspended buckets |
| S3 source retrieval | Implemented (starter) | Exact retrieval for source versions; conditional ETag and state validation for snapshots |
| JSONL manifest reader/writer | Implemented (starter) | Incremental UTF-8 JSON Lines processing |
| Automatic manifest generation | Implemented (starter) | Discovery records can be written incrementally using ManifestWriter |
| Version key resolution | Implemented (starter) | Explicit latest flag required for LATEST_ONLY |
| SHA-256 | Implemented (starter) | Streams from file/input stream |
| S3 destination writer | Implemented (starter) | SSE-S3; single PUT for small objects and sequential multipart upload for objects above 64 MiB |
| Multipart upload | Implemented (starter) | Bounded heap usage; one part is spooled to local temporary disk at a time; failed multipart uploads are aborted |
| DynamoDB document-state contract | Implemented (starter) | Conditional claim and lease-owned completion/failure operations |
| DynamoDB state repository | Implemented (starter) | Uses the existing pk/sk state table; deterministic job/shard/document keys |
| CLI document-state integration | Implemented (starter) | Existing `--state-table` mode uses durable document claims and lease-owned completion/failure |
| Read-only recovery planning | Implemented (starter) | Verifies the prepared manifest fingerprint, S3 source bucket/prefix, complete contiguous shard registration, and current shard eligibility without changing state |
| Operator-triggered recovery dispatch | Implemented (starter) | Requires explicit confirmation, a Standard state machine, a prior-execution terminal confirmation, a running-execution preflight, and a durable job-scoped wave lock; uses deterministic execution identity and fails closed on ambiguous outcomes. Destination bucket, version policy, lease duration and document-attempt limit remain operator-supplied because they are not persisted with the job |
| Recovery-wave reconciliation and finalization integration | Implemented (starter) | Verifies the exact terminal Step Functions execution against the durable wave, re-reads shard state consistently, invokes the existing guarded finalizer, and gates the next recovery generation until reconciliation is recorded |
| Recovery reconciliation audit history | Implemented (starter) | Atomically stores an immutable per-generation audit record with shard dispositions and finalization outcome while marking the matching wave reconciled; conditional transaction prevents duplicate concurrent commits and leaves interrupted pre-commit attempts safely retryable |
| Durable retry orchestration | Partial foundation | Document attempt limits and lease reclamation are integrated; automatic failed-shard dispatch/retry remains pending |
| Resumable multipart upload | Not implemented | Upload state and completed parts are not persisted across process restarts |
| Migration job and shard lifecycle | Implemented (starter) | Separate DynamoDB job-state table; conditional job/shard transitions, shard registration, and recoverable worker leases with expiry |
| Bounded shard recovery | Implemented (starter) | DynamoDB atomically persists at most three total shard claims; FAILED shards can be retried while attempts remain; expired RUNNING shards at the limit become terminal FAILED; stale workers remain fenced out. The state-store claim/reclaim behavior remains separate from the operator-triggered recovery dispatch workflow |
| Shard lease recovery and fencing | Implemented (starter) | Atomic claim, lease renewal, expiry-based reclaim, and fencing-token checks prevent a stale worker from completing a shard after ownership changes |
| Manifest fingerprinting and job preparation | Implemented (starter) | Exact manifest bytes are SHA-256 hashed as a stream; job preparation binds the fingerprint before shard registration and rejects a different manifest or shard configuration on resume |
| Job preparation | Implemented (starter) | CLI fingerprints exact manifest bytes, binds the fingerprint, registers contiguous shards and moves the job to READY before execution |
| Job-level finalization | Implemented (starter) | A running job finalizes only when every registered shard is COMPLETED or FAILED after all three claims are exhausted; retry-eligible failures and PENDING/RUNNING shards block finalization. Mixed outcomes become COMPLETED_WITH_ERRORS with aggregated shard errors |
| Deterministic shard assignment | Implemented (starter) | SHA-256 assignment from document/version identity; independent of manifest ordering; CLI executes only the selected shard |
| Distributed orchestration | Partial foundation | Operator-triggered recovery planning, Standard Step Functions dispatch, and post-execution reconciliation are implemented; automated scheduling remains pending |
| Production monitoring/alarms | Not implemented | Define SLOs, metrics and dashboards |

Do not describe this release as production-ready until all relevant production-readiness gates pass.


| Bounded automatic recovery orchestration | Not implemented | Design contract defined; operator-triggered plan, dispatch, and audit-backed reconciliation remain the supported workflow. Immutable job recovery configuration and a finite, fail-closed controller are prerequisites. |