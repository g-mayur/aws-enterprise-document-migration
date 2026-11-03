package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.domain.DocumentRecord;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Lazy S3 prefix source connector. Enumerates current objects, not historical S3 versions. */
public final class S3Source implements DocumentSource {
    private final S3Client s3; private final String bucket; private final String prefix;
    public S3Source(S3Client s3, String bucket, String prefix) { this.s3=s3; this.bucket=bucket; this.prefix=prefix == null ? "" : prefix; }
    public String connectorId() { return "s3"; }
    public void validateConnection() { s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build()); }
    public Iterator<DocumentRecord> discover() {
        return new Iterator<>() {
            private String token;
            private Iterator<S3Object> page = List.<S3Object>of().iterator();
            private boolean finished;
            private void fill() {
                while (!page.hasNext() && !finished) {
                    ListObjectsV2Response response = s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(token).build());
                    page = response.contents().iterator(); token = response.nextContinuationToken();
                    finished = token == null;
                }
            }
            public boolean hasNext() { fill(); return page.hasNext(); }
            public DocumentRecord next() {
                if (!hasNext()) throw new NoSuchElementException();
                S3Object object = page.next(); String key = object.key();
                String name = key.substring(key.lastIndexOf('/') + 1);
                return new DocumentRecord(key, "s3-current", name, key, object.size(), null, true, Map.of("sourceBucket", bucket));
            }
        };
    }
    public InputStream openStream(DocumentRecord record) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(record.relativePath()).build());
    }
}
