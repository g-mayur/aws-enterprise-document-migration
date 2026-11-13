# Manifest format

The manifest is UTF-8 JSON Lines (one JSON object per line). Keep manifests sharded; a shard should be bounded and load-tested. 100,000 records is only an initial pilot value, not a universal optimum.

Each record contains: `documentId`, `versionId`, `fileName`, `relativePath`, `sizeBytes`, and `isLatest`. Optional fields are `expectedSha256`, string-valued `metadata`, and `versionIdentityType`.

## Version identity

`versionIdentityType` distinguishes the source identity semantics:

- `SOURCE_VERSION`: the source supplies an explicit version identifier that can be retrieved exactly.
- `SNAPSHOT`: the record identifies an observed object state. The connector must validate that the source still matches that state before reading it.
- `LEGACY_UNSPECIFIED`: an older manifest did not record the identity type. This value exists only for backward compatibility and must not be treated as proof of an immutable source version.

The field is optional in schema v1 so existing manifests remain readable. When omitted, the Java reader maps it to `LEGACY_UNSPECIFIED`. Newly generated manifests should always write the field explicitly.

For S3, versioned objects use `SOURCE_VERSION`. Unversioned objects and the mutable null version in a versioning-suspended bucket use `SNAPSHOT`. Snapshot metadata includes the source ETag and last-modified timestamp. ETag is used as a conditional object-state validator, not as a SHA-256 checksum. S3 legacy records with `LEGACY_UNSPECIFIED` are rejected by the S3 connector because fetching the current object could silently return different content.

`relativePath` is interpreted by the selected connector and must be validated by that connector.

The manifest is not a place for credentials, access tokens, personal secrets or document content. Treat it as sensitive metadata.

`LATEST_ONLY` requires a source-authoritative `isLatest=true` record for each logical document. `ALL_VERSIONS` requires one row per source version or observed snapshot, according to the source's capabilities.
