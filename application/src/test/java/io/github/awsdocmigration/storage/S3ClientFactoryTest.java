package io.github.awsdocmigration.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class S3ClientFactoryTest {

    @Test
    void createsDefaultAwsS3ClientWithoutEndpointOverride() {
        try (S3Client client = S3ClientFactory.create(
                Region.US_EAST_1,
                null
        )) {
            assertNotNull(client);
            assertEquals(
                    RequestChecksumCalculation.WHEN_SUPPORTED,
                    client.serviceClientConfiguration().requestChecksumCalculation()
            );
            assertEquals(
                    ResponseChecksumValidation.WHEN_SUPPORTED,
                    client.serviceClientConfiguration().responseChecksumValidation()
            );
        }
    }

    @Test
    void usesRequiredOnlyChecksumsForLocalEndpointOverrides() {
        try (S3Client client = S3ClientFactory.create(
                Region.US_EAST_1,
                "http://localhost:4566"
        )) {
            assertEquals(
                    RequestChecksumCalculation.WHEN_REQUIRED,
                    client.serviceClientConfiguration().requestChecksumCalculation()
            );
            assertEquals(
                    ResponseChecksumValidation.WHEN_REQUIRED,
                    client.serviceClientConfiguration().responseChecksumValidation()
            );
        }
    }

    @Test
    void acceptsLocalHttpEndpoint() {
        assertEquals(
                URI.create("http://localhost:4566"),
                S3ClientFactory.parseEndpoint("http://localhost:4566")
        );
    }

    @Test
    void acceptsHttpsEndpoint() {
        assertEquals(
                URI.create("https://s3-compatible.example.test"),
                S3ClientFactory.parseEndpoint(
                        "https://s3-compatible.example.test"
                )
        );
    }

    @Test
    void rejectsNonHttpSchemesAndMalformedEndpointValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> S3ClientFactory.parseEndpoint("file:///tmp/s3")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> S3ClientFactory.parseEndpoint("localhost:4566")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> S3ClientFactory.parseEndpoint(
                        "http://user:password@localhost:4566"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> S3ClientFactory.parseEndpoint(
                        "http://localhost:4566/path"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> S3ClientFactory.parseEndpoint(
                        "http://localhost:4566?token=secret"
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> S3ClientFactory.parseEndpoint(" ")
        );
    }
}
