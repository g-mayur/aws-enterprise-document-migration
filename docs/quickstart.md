# Quick start

1. Build Java: `mvn -f application/pom.xml clean verify`.
2. Provision or select an S3 destination bucket with SSE-S3 enabled.
3. Use IAM Identity Center or an instance/task role. Grant only source read, destination write and required state-table actions.
4. Prepare a JSONL manifest. Use a small test sample first.
5. Run the CLI from `application/` with required options.

Example filesystem source: `java -jar target/document-migration-worker.jar --source-type filesystem --source-root /data/export --destination-bucket my-destination --manifest ../examples/manifests/sample.jsonl --version-policy ALL_VERSIONS --region us-east-1`

**Important:** The sample manifest's `relativePath` must match the source-relative file path. The current CLI uses a temporary local file for checksum-then-upload to avoid loading the object into heap; ensure the worker has adequate encrypted ephemeral disk. Large-object uploads use sequential multipart streaming above 64 MiB; the current implementation spools one part at a time to temporary disk. Multipart resume across worker/process restarts is not implemented.

## Run a durable sharded migration

The CLI supports an opt-in sharded execution mode. It uses two separate DynamoDB tables: the document-state table (`--state-table`) and the job/shard lifecycle table (`--job-state-table`). This matches the Terraform reference architecture.

First prepare a JSONL manifest and ensure both tables exist. Prepare the job once before starting workers. This fingerprints the exact manifest bytes, binds the fingerprint to the job ID and registers the complete shard plan:

```bash
java -jar target/document-migration-worker.jar \
  --mode prepare-job \
  --job-state-table migration-job-state \
  --job-id job-001 \
  --total-shards 16 \
  --manifest /data/manifests/job-001.jsonl \
  --region us-east-1
```

Then start one worker for each shard. Each worker receives the same job ID, manifest, shard count and table names, but a different shard number and worker ID:

```bash
java -jar target/document-migration-worker.jar \
  --source-type filesystem \
  --source-root /data/export \
  --destination-bucket my-destination \
  --manifest /data/manifests/job-001.jsonl \
  --version-policy ALL_VERSIONS \
  --state-table migration-document-state \
  --job-state-table migration-job-state \
  --job-id job-001 \
  --total-shards 16 \
  --shard-number 0 \
  --worker-id worker-0 \
  --lease-seconds 300 \
  --max-attempts 5 \
  --region us-east-1
```

Run additional workers with `--shard-number` values from `1` through `15` and unique worker IDs. All workers must use the exact same manifest file bytes and the same `--total-shards` value. Workers verify the prepared fingerprint and registered shard plan before claiming their assigned shard; they do not attempt to prepare the job themselves.

A worker executes one shard and records that shard as COMPLETED or FAILED. This CLI mode does not yet dispatch workers, automatically retry failed shards, or mark the overall job COMPLETED after every shard finishes; those require a separate orchestration/finalization step.