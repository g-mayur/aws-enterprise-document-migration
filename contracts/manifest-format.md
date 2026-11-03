# Manifest format

The manifest is UTF-8 JSON Lines (one JSON object per line). Keep manifests sharded; a shard should be bounded and load-tested. 100,000 records is only an initial pilot value, not a universal optimum.

Fields: `documentId`, `versionId`, `fileName`, `relativePath`, `sizeBytes`, `isLatest`, optional `expectedSha256`, optional string-valued `metadata`. `relativePath` is interpreted by the selected connector and must be validated by that connector.

The manifest is not a place for credentials, access tokens, personal secrets or document content. Treat it as sensitive metadata.

`LATEST_ONLY` requires a source-authoritative `isLatest=true` record for each logical document. `ALL_VERSIONS` requires one row per source version.
