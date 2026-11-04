
package io.github.awsdocmigration;

import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.connector.filesystem.FilesystemSource;
import io.github.awsdocmigration.connector.s3.S3Source;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.VersionPolicy;
import io.github.awsdocmigration.engine.DestinationKeyResolver;
import io.github.awsdocmigration.engine.ManifestReader;
import io.github.awsdocmigration.storage.DestinationWriter;
import io.github.awsdocmigration.storage.S3DestinationWriter;
import io.github.awsdocmigration.util.Checksums;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class MigrationCli {

    private MigrationCli() {
    }

    public static void main(String[] args) throws Exception {

        Map<String, String> arguments = parse(args);

        String sourceType = required(arguments, "source-type");
        String destinationBucket =
                required(arguments, "destination-bucket");

        String region = arguments.getOrDefault(
                "region",
                System.getenv().getOrDefault(
                        "AWS_REGION",
                        "us-east-1"
                )
        );

        VersionPolicy policy = VersionPolicy.valueOf(
                arguments.getOrDefault(
                        "version-policy",
                        "ALL_VERSIONS"
                )
        );

        Path manifestPath = Path.of(
                required(arguments, "manifest")
        );

        long succeeded = 0;
        long failed = 0;

        try (S3Client s3 = S3Client.builder()
                .region(Region.of(region))
                .build()) {

            DocumentSource source = createSource(
                    sourceType,
                    arguments,
                    s3
            );

            try (
                    source;
                    ManifestReader manifestReader =
                            new ManifestReader(manifestPath)
            ) {

                source.validateConnection();

                DestinationWriter writer =
                        new S3DestinationWriter(
                                s3,
                                destinationBucket
                        );

                DestinationKeyResolver keyResolver =
                        new DestinationKeyResolver();

                for (DocumentRecord record : manifestReader) {

                    try {
                        migrateDocument(
                                source,
                                writer,
                                keyResolver,
                                record,
                                policy
                        );

                        succeeded++;

                    } catch (Exception e) {

                        failed++;

                        System.err.println(
                                "FAILED "
                                        + record.idempotencyKey()
                                        + ": "
                                        + e.getMessage()
                        );
                    }
                }

            }
        }

        System.out.printf(
                "Migration run finished. succeeded=%d failed=%d%n",
                succeeded,
                failed
        );

        if (failed > 0) {
            throw new IllegalStateException(
                    "Migration completed with "
                            + failed
                            + " failed document(s)"
            );
        }
    }

    private static DocumentSource createSource(
            String sourceType,
            Map<String, String> arguments,
            S3Client s3
    ) {

        return switch (sourceType) {

            case "filesystem" -> new FilesystemSource(
                    Path.of(
                            required(arguments, "source-root")
                    )
            );

            case "s3" -> new S3Source(
                    s3,
                    required(arguments, "source-bucket"),
                    arguments.getOrDefault(
                            "source-prefix",
                            ""
                    )
            );

            default -> throw new IllegalArgumentException(
                    "Unsupported source-type: " + sourceType
            );
        };
    }

    private static void migrateDocument(
            DocumentSource source,
            DestinationWriter writer,
            DestinationKeyResolver keyResolver,
            DocumentRecord record,
            VersionPolicy policy
    ) throws Exception {

        Path temporaryFile = null;

        try (InputStream input = source.openStream(record)) {

            temporaryFile = Files.createTempFile(
                    "migration-",
                    ".bin"
            );

            Files.copy(
                    input,
                    temporaryFile,
                    StandardCopyOption.REPLACE_EXISTING
            );

            String digest;

            try (InputStream verificationStream =
                         Files.newInputStream(temporaryFile)) {

                digest = Checksums.sha256(verificationStream);
            }

            String expectedChecksum =
                    record.expectedSha256();

            if (expectedChecksum != null
                    && !expectedChecksum.equalsIgnoreCase(digest)) {

                throw new IllegalStateException(
                        "SHA-256 mismatch for "
                                + record.idempotencyKey()
                );
            }

            String destinationKey = keyResolver.resolve(
                    record,
                    policy
            );

            try (InputStream uploadStream =
                         Files.newInputStream(temporaryFile)) {

                writer.write(
                        record,
                        destinationKey,
                        uploadStream,
                        digest
                );
            }

        } finally {

            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private static Map<String, String> parse(String[] args) {

        Map<String, String> arguments = new HashMap<>();

        for (int i = 0; i < args.length; i++) {

            if (!args[i].startsWith("--")
                    || i + 1 >= args.length
                    || args[i + 1].startsWith("--")) {

                throw new IllegalArgumentException(
                        "Expected --name value arguments"
                );
            }

            String key = args[i].substring(2);

            if (arguments.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Duplicate argument: --" + key
                );
            }

            arguments.put(key, args[++i]);
        }

        return arguments;
    }

    private static String required(
            Map<String, String> arguments,
            String key
    ) {

        String value = arguments.get(key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing --" + key
            );
        }

        return value;
    }
}
