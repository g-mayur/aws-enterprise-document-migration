package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Assigns each document version to exactly one stable shard.
 *
 * Assignment is independent of manifest order and worker identity. The
 * identity type is included so a source version and a snapshot with the
 * same textual version ID cannot accidentally share an identity.
 */
public final class ShardAssignment {

    private ShardAssignment() {
    }

    /**
     * Returns a shard number in the range [0, totalShards).
     */
    public static int shardFor(DocumentRecord record, int totalShards) {
        Objects.requireNonNull(record, "Document record is required");

        if (totalShards < 1) {
            throw new IllegalArgumentException(
                    "totalShards must be greater than zero"
            );
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            updatePart(digest, record.documentId());
            updatePart(digest, record.versionIdentityType().name());
            updatePart(digest, record.versionId());

            long unsignedHash = Integer.toUnsignedLong(
                    ByteBuffer.wrap(digest.digest()).getInt()
            );

            return (int) (unsignedHash % totalShards);

        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 is not available in this Java runtime",
                    impossible
            );
        }
    }

    private static void updatePart(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length)
                .array());
        digest.update(bytes);
    }
}
