# Implementation status

| Capability | Status | Notes |
|---|---|---|
| Connector SPI | Starter | Java interface available |
| Filesystem source | Starter | Single logical version per file |
| S3 source | Starter | Current objects only; not S3 historical versions |
| JSONL manifest reader | Starter | Incremental line reader |
| Version key resolution | Starter | Explicit latest flag required |
| SHA-256 | Starter | Streams from file/input stream |
| S3 destination writer | Starter | SSE-S3; synchronous single PUT |
| Multipart/resume | Not implemented | Required for large objects |
| DynamoDB worker state/leases | Not implemented | Terraform tables are foundation only |
| Distributed orchestration | Not implemented | Add ECS service, EventBridge/Lambda/SQS if design requires |
| Private API and notifications | Not implemented | Optional control-plane extensions |
| Production monitoring/alarms | Not implemented | Define SLOs and dashboards |

Do not describe this release as production-ready until all relevant production-readiness gates pass.
