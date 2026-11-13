
package io.github.awsdocmigration;

import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionIdentityType;
import io.github.awsdocmigration.domain.VersionPolicy;
import io.github.awsdocmigration.engine.DestinationKeyResolver;
import io.github.awsdocmigration.storage.DestinationWriter;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationIntegrityTest {

    private DocumentRecord createRecord(long expectedSize) {

        return new DocumentRecord(
                "document-1",
                "version-1",
                "document.pdf",
                "documents/document.pdf",
                expectedSize,
                null,
                true,
                Map.of(),
                VersionIdentityType.SOURCE_VERSION
        );
    }

    @Test
    void uploadsDocumentWhenDownloadedSizeMatchesManifest()
            throws Exception {

        byte[] content = "valid document".getBytes(
                StandardCharsets.UTF_8
        );

        DocumentRecord record = createRecord(content.length);

        DocumentSource source = mock(DocumentSource.class);
        DestinationWriter writer = mock(DestinationWriter.class);

        when(source.openStream(record))
                .thenReturn(new ByteArrayInputStream(content));

        MigrationCli.migrateDocument(
                source,
                writer,
                new DestinationKeyResolver(),
                record,
                VersionPolicy.ALL_VERSIONS
        );

        verify(writer, times(1)).write(
                eq(record),
                anyString(),
                any(java.io.InputStream.class),
                anyString()
        );
    }

    @Test
    void rejectsDocumentWhenDownloadedSizeIsSmallerThanManifest()
            throws Exception {

        byte[] content = "short".getBytes(StandardCharsets.UTF_8);

        DocumentRecord record = createRecord(content.length + 10);

        DocumentSource source = mock(DocumentSource.class);
        DestinationWriter writer = mock(DestinationWriter.class);

        when(source.openStream(record))
                .thenReturn(new ByteArrayInputStream(content));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> MigrationCli.migrateDocument(
                        source,
                        writer,
                        new DestinationKeyResolver(),
                        record,
                        VersionPolicy.ALL_VERSIONS
                )
        );

        assertTrue(
                exception.getMessage().contains("Downloaded size mismatch")
        );

        verifyNoInteractions(writer);
    }

    @Test
    void rejectsDocumentWhenDownloadedSizeIsLargerThanManifest()
            throws Exception {

        byte[] content = "larger document".getBytes(
                StandardCharsets.UTF_8
        );

        DocumentRecord record = createRecord(content.length - 5);

        DocumentSource source = mock(DocumentSource.class);
        DestinationWriter writer = mock(DestinationWriter.class);

        when(source.openStream(record))
                .thenReturn(new ByteArrayInputStream(content));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> MigrationCli.migrateDocument(
                        source,
                        writer,
                        new DestinationKeyResolver(),
                        record,
                        VersionPolicy.ALL_VERSIONS
                )
        );

        assertTrue(
                exception.getMessage().contains("Downloaded size mismatch")
        );

        verifyNoInteractions(writer);
    }

    @Test
    void rejectsDocumentWhenExpectedChecksumDoesNotMatch()
            throws Exception {

        byte[] content = "valid document".getBytes(
                StandardCharsets.UTF_8
        );

        DocumentRecord record = new DocumentRecord(
                "document-1",
                "version-1",
                "document.pdf",
                "documents/document.pdf",
                content.length,
                "0".repeat(64),
                true,
                Map.of(),
                VersionIdentityType.SOURCE_VERSION
        );

        DocumentSource source = mock(DocumentSource.class);
        DestinationWriter writer = mock(DestinationWriter.class);

        when(source.openStream(record))
                .thenReturn(new ByteArrayInputStream(content));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> MigrationCli.migrateDocument(
                        source,
                        writer,
                        new DestinationKeyResolver(),
                        record,
                        VersionPolicy.ALL_VERSIONS
                )
        );

        assertTrue(
                exception.getMessage().contains("SHA-256 mismatch")
        );

        verifyNoInteractions(writer);
    }
}
