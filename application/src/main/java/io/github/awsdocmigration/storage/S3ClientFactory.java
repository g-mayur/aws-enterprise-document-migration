package io.github.awsdocmigration.storage;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;
import java.util.Objects;

/**
 * Builds S3 clients with optional endpoint overrides for local S3-compatible
 * services. When an override is configured, path-style addressing is enabled
 * for compatibility with services such as LocalStack and MinIO.
 */
public final class S3ClientFactory {

    private S3ClientFactory() {
    }

    public static S3Client create(Region region, String endpointUrl) {
        Objects.requireNonNull(region, "AWS Region is required");

        var builder = S3Client.builder().region(region);

        if (endpointUrl != null && !endpointUrl.isBlank()) {
            URI endpoint = parseEndpoint(endpointUrl);
            builder.endpointOverride(endpoint)
                    .serviceConfiguration(
                            S3Configuration.builder()
                                    .pathStyleAccessEnabled(true)
                                    .build()
                    )
                    .overrideConfiguration(
                            ClientOverrideConfiguration.builder()
                                    .requestChecksumCalculation(
                                            RequestChecksumCalculation.WHEN_REQUIRED)
                                    .build()
                    );
        }

        return builder.build();
    }

    static URI parseEndpoint(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "S3 endpoint URL must not be blank"
            );
        }

        final URI endpoint;
        try {
            endpoint = URI.create(endpointUrl.trim());
        } catch (IllegalArgumentException invalidUri) {
            throw new IllegalArgumentException(
                    "S3 endpoint URL must be a valid absolute HTTP(S) URL",
                    invalidUri
            );
        }

        String scheme = endpoint.getScheme();
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || (!"http".equalsIgnoreCase(scheme)
                    && !"https".equalsIgnoreCase(scheme))
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || (endpoint.getPath() != null
                    && !endpoint.getPath().isEmpty()
                    && !"/".equals(endpoint.getPath()))) {
            throw new IllegalArgumentException(
                    "S3 endpoint must be an absolute HTTP(S) URL with a host and no credentials, path, query or fragment"
            );
        }

        return endpoint;
    }
}
