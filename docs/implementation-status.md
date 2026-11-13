# Implementation status

This document describes the current starter implementation. A successful build does not establish production readiness.

| Capability | Status | Notes |
|---|---|---|
| Connector SPI | Implemented (starter) | Java interface and capability declarations available |
| Filesystem source | Implemented (starter) | Discovers files as single logical versions |
| S3 source discovery | Implemented (starter) | Supports current-object snapshots in unversioned buckets and version enumeration in enabled/suspended buckets |
| S3 source retrieval | Implemented (starter) | Exact retrieval for source versions; conditional ETag and state validation for snapshots |
| Legacy S3 identity handling | Safe rejection | S3 records with LEGACY_UNSPECIFIED identity are rejected rather than silently fetching the current object |
| JSONL manifest reader/writer | Implemented (starter) | Incremental UTF-8 JSON Lines processing |
| Manifest schema | Updated | Optional versionIdentityType preserves compatibility with older v1 records |
| Automatic manifest generation | Implemented (starter) | Discovery records can be written incrementally using ManifestWriter |
| Version key resolution | Implemented (starter) | Explicit latest flag required for LATEST_ONLY |
| SHA-256 | Implemented (starter) | Streams from file/input stream |
| Downloaded-size verification | Implemented (starter) | CLI checks source bytes against manifest size |
| S3 destination writer | Implemented (starter) | SSE-S3; single PUT for small objects and sequential multipart upload for objects above 64 MiB |
| Multipart upload | Implemented (starter) | Bounded heap usage; one part is spooled to local temporary disk at a time; failed multipart uploads are aborted |
| Resumable upload | Not implemented | Upload state and completed parts are not persisted across process restarts |
| Durable DynamoDB worker state/leases | Not implemented | Terraform tables are foundation only; application integration is pending |
| Distributed orchestration | Not implemented | ECS worker deployment and job orchestration are pending |
| Private API and notifications | Not implemented | Optional control-plane extensions |
| Production monitoring/alarms | Not implemented | Define SLOs, metrics and dashboards |

Do not describe this release as production-ready until all relevant production-readiness gates pass.
