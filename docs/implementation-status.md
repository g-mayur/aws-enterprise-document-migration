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
| CLI state integration | Not implemented | MigrationCli does not yet use the state repository |
| Durable retry orchestration | Partial foundation | Attempt limits and lease reclamation are supported by the repository; worker integration is pending |
| Resumable multipart upload | Not implemented | Upload state and completed parts are not persisted across process restarts |
| Migration job and shard lifecycle | Implemented (starter) | Separate DynamoDB job-state table; conditional job/shard transitions and shard registration |
| Manifest fingerprinting and job preparation | Implemented (starter) | Exact manifest bytes are SHA-256 hashed as a stream; job preparation binds the fingerprint before shard registration and rejects a different manifest or shard configuration on resume |
| Job preparation | Implemented (starter) | Resumable preparation registers contiguous shards and moves a job to READY only after registration is complete |
| Deterministic shard assignment | Implemented (starter) | SHA-256 assignment from document/version identity; independent of manifest ordering |
| Distributed orchestration | Not implemented | ECS worker deployment and job scheduling/dispatch are pending |
| Production monitoring/alarms | Not implemented | Define SLOs, metrics and dashboards |

Do not describe this release as production-ready until all relevant production-readiness gates pass.
