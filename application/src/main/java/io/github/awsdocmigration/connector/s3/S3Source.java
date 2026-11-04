
package io.github.awsdocmigration.connector.s3;

import io.github.awsdocmigration.connector.ConnectorCapability;
import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.domain.DocumentRecord;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Amazon S3 source connector.
 *
 * Discovers current objects under a configured prefix.
 * Historical S3 object versions are not enumerated by this implementation.
 *
 * The S3Client is externally managed and is not closed by this connector.
 */
public final class S3Source implements DocumentSource {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3Source(
            S3Client s3,
            String bucket,
            String prefix
    ) {
        if (s3 == null) {
            throw new IllegalArgumentException("S3 client is required");
        }

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("Source bucket is required");
        }

        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
    }

    @Override
    public String connectorId() {
        return "s3";
    }

    @Override
    public Set<ConnectorCapability> capabilities() {
        return Set.of(
                ConnectorCapability.DOCUMENT_DISCOVERY,
                ConnectorCapability.STREAMING_READ,
                ConnectorCapability.LATEST_VERSION_IDENTIFICATION
        );
    }

    @Override
    public void validateConnection() {
        s3.headBucket(
                HeadBucketRequest.builder()
                        .bucket(bucket)
                        .build()
        );
    }

    @Override
    public Iterator<DocumentRecord> discover() {

        return new Iterator<>() {

            private String token;

            private Iterator<S3Object> page =
                    List.<S3Object>of().iterator();

            private boolean finished;

            private void fill() {

                while (!page.hasNext() && !finished) {

                    ListObjectsV2Response response =
                            s3.listObjectsV2(
                                    ListObjectsV2Request.builder()
                                            .bucket(bucket)
                                            .prefix(prefix)
                                            .continuationToken(token)
                                            .build()
                            );

                    page = response.contents().iterator();

                    token = response.nextContinuationToken();

                    finished = token == null;
                }
            }

            @Override
            public boolean hasNext() {
                fill();
                return page.hasNext();
            }

            @Override
            public DocumentRecord next() {

                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                S3Object object = page.next();

                String key = object.key();

                String name = key.substring(
                        key.lastIndexOf('/') + 1
                );

                return new DocumentRecord(
                        key,
                        "s3-current",
                        name,
                        key,
                        object.size(),
                        null,
                        true,
                        Map.of("sourceBucket", bucket)
                );
            }
        };
    }

    @Override
    public InputStream openStream(
            DocumentRecord record
    ) throws IOException {

        if (record == null) {
            throw new IllegalArgumentException(
                    "Document record is required"
            );
        }

        String recordBucket = record.metadata().get("sourceBucket");

        if (recordBucket != null && !bucket.equals(recordBucket)) {
            throw new SecurityException(
                    "Document record belongs to a different S3 bucket"
            );
        }

        String key = record.relativePath();

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "S3 object key is required"
            );
        }

        if (!key.startsWith(prefix)) {
            throw new SecurityException(
                    "S3 object key is outside the configured source prefix"
            );
        }

        return s3.getObject(
                GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .build()
        );
    }
}
