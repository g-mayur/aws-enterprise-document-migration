package io.github.awsdocmigration.engine;

import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShardAssignmentTest {

    private DocumentRecord record(
            String documentId,
            String versionId,
            VersionIdentityType identityType
    ) {
        return new DocumentRecord(
                documentId,
                versionId,
                "file.pdf",
                "source/file.pdf",
                10,
                null,
                true,
                Map.of(),
                identityType
        );
    }

    @Test
    void assignmentIsStableForTheSameDocumentVersion() {
        DocumentRecord record = record(
                "document-1",
                "version-1",
                VersionIdentityType.SOURCE_VERSION
        );

        int first = ShardAssignment.shardFor(record, 17);

        assertEquals(first, ShardAssignment.shardFor(record, 17));
    }

    @Test
    void assignmentIsIndependentOfManifestOrder() {
        DocumentRecord first = record(
                "document-1",
                "version-1",
                VersionIdentityType.SOURCE_VERSION
        );
        DocumentRecord second = record(
                "document-2",
                "version-1",
                VersionIdentityType.SOURCE_VERSION
        );

        int firstAssignment = ShardAssignment.shardFor(first, 13);
        ShardAssignment.shardFor(second, 13);

        assertEquals(firstAssignment, ShardAssignment.shardFor(first, 13));
    }

    @Test
    void assignmentAlwaysFallsWithinConfiguredShardRange() {
        for (int shardCount = 1; shardCount <= 64; shardCount++) {
            int shard = ShardAssignment.shardFor(
                    record("document-1", "version-1",
                            VersionIdentityType.SOURCE_VERSION),
                    shardCount
            );

            assertTrue(shard >= 0);
            assertTrue(shard < shardCount);
        }
    }

    @Test
    void rejectsInvalidInputs() {
        DocumentRecord record = record(
                "document-1",
                "version-1",
                VersionIdentityType.SOURCE_VERSION
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ShardAssignment.shardFor(record, 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ShardAssignment.shardFor(record, -1)
        );
        assertThrows(
                NullPointerException.class,
                () -> ShardAssignment.shardFor(null, 2)
        );
    }
}
