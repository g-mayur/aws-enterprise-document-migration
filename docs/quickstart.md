# Quick start

1. Build Java: `mvn -f application/pom.xml clean verify`.
2. Provision or select an S3 destination bucket with SSE-S3 enabled.
3. Use IAM Identity Center or an instance/task role. Grant only source read, destination write and required state-table actions.
4. Prepare a JSONL manifest. Use a small test sample first.
5. Run the CLI from `application/` with required options.

Example filesystem source: `java -jar target/document-migration-worker.jar --source-type filesystem --source-root /data/export --destination-bucket my-destination --manifest ../examples/manifests/sample.jsonl --version-policy ALL_VERSIONS --region us-east-1`

**Important:** The sample manifest's `relativePath` must match the source-relative file path. The current CLI uses a temporary local file for checksum-then-upload to avoid loading the object into heap; ensure the worker has adequate encrypted ephemeral disk. Large-object multipart streaming is a required production enhancement.
