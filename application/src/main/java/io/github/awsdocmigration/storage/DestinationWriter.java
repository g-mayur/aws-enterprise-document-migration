package io.github.awsdocmigration.storage;

import io.github.awsdocmigration.domain.DocumentRecord;

import java.io.InputStream;

public interface DestinationWriter {
    void write(DocumentRecord record, String key, InputStream content, String sha256)
            throws Exception;

    /**
     * Context-aware extension. Implementations that do not support durable
     * multipart resume retain their original behavior.
     */
    default void write(
            DocumentRecord record, String key, InputStream content, String sha256,
            MultipartUploadContext context
    ) throws Exception {
        write(record, key, content, sha256);
    }
}
