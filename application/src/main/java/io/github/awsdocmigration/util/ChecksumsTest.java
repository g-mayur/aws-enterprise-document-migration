
package io.github.awsdocmigration.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class ChecksumsTest {

    @Test
    void shouldGenerateCorrectSha256ForKnownInput() throws IOException {
        byte[] input = "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String result = Checksums.sha256(new ByteArrayInputStream(input));

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad",
                result
        );
    }

    @Test
    void shouldGenerateCorrectSha256ForEmptyInput() throws IOException {
        String result = Checksums.sha256(
                new ByteArrayInputStream(new byte[0])
        );

        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb924"
                        + "27ae41e4649b934ca495991b7852b855",
                result
        );
    }

    @Test
    void shouldHandleLargeInputStream() throws Exception {
        byte[] input = new byte[5 * 1024 * 1024];

        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) (i % 251);
        }

        String expected = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input)
        );

        String actual = Checksums.sha256(
                new ByteArrayInputStream(input)
        );

        assertEquals(expected, actual);
    }

    @Test
    void shouldNotCloseCallerProvidedInputStream() throws IOException {
        class TrackingInputStream extends FilterInputStream {
            private boolean closed;

            TrackingInputStream(InputStream input) {
                super(input);
            }

            @Override
            public void close() throws IOException {
                closed = true;
                super.close();
            }
        }

        TrackingInputStream input = new TrackingInputStream(
                new ByteArrayInputStream("test".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                ))
        );

        Checksums.sha256(input);

        assertFalse(input.closed, "Checksum calculation must not close the input stream");
    }
}
