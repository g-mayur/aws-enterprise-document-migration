# Operations

## Batch lifecycle

Recommended states: `DISCOVERED`, `QUEUED`, `IN_PROGRESS`, `SUCCEEDED`, `PARTIAL`, `FAILED`, `CANCELLED`. Store per-record state separately from aggregate counters. Avoid a single hot DynamoDB partition/counter updated for every record; shard counters and aggregate periodically.

## Retry and idempotency

Use a stable idempotency key derived from source system + repository + document ID + version ID. Use conditional writes/leases with expiry for distributed workers. Retry only transient failures; classify authorization, not-found, checksum mismatch and invalid manifest as non-retryable or manual-review conditions. Use bounded attempts and a dead-letter/manual-review workflow.

## Observability

Emit structured logs and metrics: discovered, attempted, succeeded, failed, bytes read/written, checksum mismatch, retries, throttles, queue age, per-connector latency and worker utilization. Never log document content, credentials or sensitive metadata.
