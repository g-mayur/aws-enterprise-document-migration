# Connector development

Implement `DocumentSource`. A connector must: authenticate using an approved credential provider; validate permissions; paginate without unbounded memory; expose stable document and version IDs; stream content; handle throttling with bounded exponential backoff and jitter; document consistency and version semantics; reject path/key traversal; and close resources.

Capability metadata should be added before connectors become production-supported: version history, latest-version identification, checksum type, server-side filtering, incremental discovery, resumable reads, max object size, rate-limit behavior and metadata fidelity.

Never assume all repositories have the same meaning for "latest", modified time, deleted items, permissions or version identifiers.
