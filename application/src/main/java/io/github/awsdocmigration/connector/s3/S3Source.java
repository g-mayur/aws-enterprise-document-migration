
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
            throw new IllegalArgumentException(
                    "S3 client is required"
            );
        }

        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException(
                    "Source bucket is required"
            );
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

            private String continuationToken;

            private Iterator<S3Object> currentPage =
                    List.<S3Object>of().iterator();

            private boolean finished;

            private void loadNextPage() {

                while (!currentPage.hasNext() && !finished) {

                    ListObjectsV2Response response =
                            s3.listObjectsV2(
                                    ListObjectsV2Request.builder()
                                            .bucket(bucket)
                                            .prefix(prefix)
                                            .continuationToken(
                                                    continuationToken
                                            )
                                            .build()
                            );

                    String nextToken =
                            response.nextContinuationToken();

                    if (response.isTruncated()) {

                        if (nextToken == null
                                || nextToken.isBlank()) {
                            throw new IllegalStateException(
                                    "S3 returned a truncated listing "
                                            + "without a continuation token"
                            );
                        }

                        if (nextToken.equals(continuationToken)) {
                            throw new IllegalStateException(
                                    "S3 returned a repeated continuation token"
                            );
                        }
                    }

                    continuationToken = nextToken;
                    finished = !response.isTruncated();

                    currentPage = response.contents()
                            .stream()
                            .filter(S3Source.this::isDocumentObject)
                            .iterator();
                }
            }

            @Override
            public boolean hasNext() {
                loadNextPage();
                return currentPage.hasNext();
            }

            @Override
            public DocumentRecord next() {

                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                S3Object object = currentPage.next();

                String key = object.key();

                String fileName = key.substring(
                        key.lastIndexOf('/') + 1
                );

                return new DocumentRecord(
                        key,
                        "s3-current",
                        fileName,
                        key,
                        object.size(),
                        null,
                        true,
                        Map.of("sourceBucket", bucket)
                );
            }
        };
    }

    private boolean isDocumentObject(S3Object object) {

        String key = object.key();

        if (key == null || key.isBlank()) {
            return false;
        }

        // Ignore conventional zero-byte S3 folder-marker objects.
        return !(key.endsWith("/") && object.size() == 0);
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

        String recordBucket =
                record.metadata().get("sourceBucket");

        if (recordBucket != null
                && !bucket.equals(recordBucket)) {
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
