package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.DocumentRecord;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.InputStream;

/** Synchronous starter writer. For large objects, implement multipart upload with bounded buffering. */
public final class S3DestinationWriter implements DestinationWriter {
    private final S3Client s3; private final String bucket;
    public S3DestinationWriter(S3Client s3, String bucket) { this.s3=s3; this.bucket=bucket; }
    public void write(DocumentRecord record, String key, InputStream content, String sha256) {
        PutObjectRequest request=PutObjectRequest.builder().bucket(bucket).key(key).serverSideEncryption(ServerSideEncryption.AES256)
            .metadata(java.util.Map.of("document-id", safe(record.documentId()), "source-version-id", safe(record.versionId()), "sha256", sha256)).build();
        s3.putObject(request, RequestBody.fromInputStream(content, record.sizeBytes()));
    }
    private static String safe(String s) { return s.replaceAll("[^A-Za-z0-9._-]", "_"); }
}
