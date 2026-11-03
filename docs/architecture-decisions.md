# Architecture decisions

1. Source systems integrate through a connector SPI; migration engine remains source-independent.
2. Discovery and transfer are separate phases; manifests are sharded and replayable.
3. Source version history is modeled explicitly; S3 bucket versioning is an independent destination control.
4. `LATEST_ONLY` requires source-authoritative latest designation; never infer by sorting IDs.
5. `ALL_VERSIONS` writes each source version to a deterministic version-specific key.
6. SSE-S3 is the starter encryption choice; customer requirements may require SSE-KMS or other controls.
7. At-least-once event delivery and worker retries are assumed; operations must be idempotent.
8. Scale parameters are measured in a representative pilot, not copied blindly from benchmark examples.
