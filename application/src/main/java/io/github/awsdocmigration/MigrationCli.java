package io.github.awsdocmigration;

import io.github.awsdocmigration.connector.DocumentSource;
import io.github.awsdocmigration.connector.filesystem.FilesystemSource;
import io.github.awsdocmigration.connector.s3.S3Source;
import io.github.awsdocmigration.domain.DocumentRecord;
import io.github.awsdocmigration.domain.MigrationJobStatus;
import io.github.awsdocmigration.domain.VersionPolicy;
import io.github.awsdocmigration.engine.DestinationKeyResolver;
import io.github.awsdocmigration.engine.DocumentMigrationCoordinator;
import io.github.awsdocmigration.engine.DocumentStateStore;
import io.github.awsdocmigration.engine.DynamoDbDocumentStateStore;
import io.github.awsdocmigration.engine.DynamoDbJobStateStore;
import io.github.awsdocmigration.engine.JobPreparationCoordinator;
import io.github.awsdocmigration.engine.JobFinalizationCoordinator;
import io.github.awsdocmigration.engine.JobStateStore;
import io.github.awsdocmigration.engine.ManifestFingerprint;
import io.github.awsdocmigration.engine.MigrationJobState;
import io.github.awsdocmigration.engine.ShardExecutionCoordinator;
import io.github.awsdocmigration.engine.ShardRecoveryInspector;
import io.github.awsdocmigration.engine.RecoveryWavePlanner;
import io.github.awsdocmigration.engine.RecoveryWaveInputBuilder;
import io.github.awsdocmigration.engine.DynamoDbRecoveryWaveStore;
import io.github.awsdocmigration.engine.StepFunctionsRecoveryWaveDispatcher;
import io.github.awsdocmigration.engine.RecoveryWaveExecutionGuard;
import io.github.awsdocmigration.engine.RecoveryWaveReconciliationCoordinator;
import io.github.awsdocmigration.engine.RecoveryJobConfiguration;
import io.github.awsdocmigration.engine.RecoveryWaveSnapshot;
import io.github.awsdocmigration.engine.ManifestReader;
import io.github.awsdocmigration.engine.ManifestWriter;
import io.github.awsdocmigration.storage.DestinationWriter;
import io.github.awsdocmigration.storage.S3DestinationWriter;
import io.github.awsdocmigration.util.Checksums;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.DescribeStateMachineRequest;
import software.amazon.awssdk.services.sfn.model.StateMachineType;
import software.amazon.awssdk.services.sfn.model.DescribeExecutionRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.core.sync.ResponseTransformer;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class MigrationCli {

    private MigrationCli() {
    }

    public static void main(String[] args) throws Exception {

        Map<String, String> arguments = parse(args);

        String mode = arguments.getOrDefault("mode", "migrate");

        String region = arguments.getOrDefault(
                "region",
                System.getenv().getOrDefault("AWS_REGION", "us-east-1")
        );

        try (
                S3Client s3 = S3Client.builder()
                        .region(Region.of(region))
                        .build();
                DynamoDbClient dynamoDb = DynamoDbClient.builder()
                        .region(Region.of(region))
                        .build();
                SfnClient stepFunctions = SfnClient.builder()
                        .region(Region.of(region))
                        .build()
        ) {

            switch (mode) {

                case "generate-manifest" -> generateManifest(
                        createSource(required(arguments, "source-type"), arguments, s3),
                        Path.of(required(arguments, "manifest-output"))
                );

                case "prepare-job" -> prepareJob(dynamoDb, arguments);

                case "configure-recovery" -> configureRecovery(
                        dynamoDb, s3, stepFunctions, arguments, region
                );

                case "inspect-job" -> inspectJob(dynamoDb, arguments);

                case "plan-recovery" -> planRecovery(dynamoDb, s3, arguments);

                case "dispatch-recovery" -> dispatchRecovery(
                        dynamoDb, s3, stepFunctions, arguments, region
                );

                case "reconcile-recovery" -> reconcileRecovery(
                        dynamoDb, stepFunctions, arguments
                );

                case "auto-recover" -> autoRecover(
                        dynamoDb, s3, stepFunctions, arguments, region
                );

                case "migrate" -> migrate(
                        createSource(required(arguments, "source-type"), arguments, s3),
                        s3,
                        dynamoDb,
                        arguments
                );

                default -> throw new IllegalArgumentException(
                        "Unsupported mode: " + mode
                                + ". Supported modes: generate-manifest, prepare-job, configure-recovery, inspect-job, plan-recovery, dispatch-recovery, reconcile-recovery, auto-recover, migrate"
                );
            }
        }
    }

    /**
     * Discovers source documents and writes them incrementally
     * to a new JSONL manifest.
     */
    static void generateManifest(
            DocumentSource source,
            Path manifestPath
    ) throws Exception {

        if (source == null) {
            throw new IllegalArgumentException(
                    "Document source is required"
            );
        }

        if (manifestPath == null) {
            throw new IllegalArgumentException(
                    "Manifest output path is required"
            );
        }

        long discovered = 0;

        try (source) {

            source.validateConnection();

            try (ManifestWriter writer = new ManifestWriter(manifestPath)) {

                try {
                    Iterator<DocumentRecord> records = source.discover();

                    if (records == null) {
                        throw new IllegalStateException(
                                "Source discovery returned a null iterator"
                        );
                    }

                    while (records.hasNext()) {

                        DocumentRecord record = records.next();

                        writer.write(record);

                        discovered++;
                    }

                } catch (Exception e) {

                    try {
                        Files.deleteIfExists(manifestPath);
                    } catch (IOException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }

                    throw e;
                }
            }
        }

        System.out.printf(
                "Manifest generation completed. discovered=%d manifest=%s%n",
                discovered,
                manifestPath.toAbsolutePath()
        );
    }

    /**
     * Migrates a JSONL manifest. Durable state is enabled only when
     * --state-table is supplied, preserving the original stateless mode.
     */
    private static void migrate(
            DocumentSource source,
            S3Client s3,
            DynamoDbClient dynamoDb,
            Map<String, String> arguments
    ) throws Exception {

        validateShardedExecutionArguments(arguments);

        if (hasText(arguments.get("job-state-table"))) {
            migrateSharded(
                    source,
                    s3,
                    dynamoDb,
                    arguments
            );
            return;
        }

        String destinationBucket = required(arguments, "destination-bucket");

        VersionPolicy policy = VersionPolicy.valueOf(
                arguments.getOrDefault("version-policy", "ALL_VERSIONS")
        );

        Path manifestPath = resolveManifestPath(s3, arguments);

        DocumentMigrationCoordinator coordinator = null;
        String stateTable = arguments.get("state-table");

        if (stateTable != null && !stateTable.isBlank()) {
            String jobId = required(arguments, "job-id");
            int shardNumber = parseNonNegativeInt(
                    arguments.getOrDefault("shard-number", "0"),
                    "shard-number"
            );
            int leaseSeconds = parsePositiveInt(
                    arguments.getOrDefault("lease-seconds", "300"),
                    "lease-seconds"
            );
            int maximumAttempts = parsePositiveInt(
                    arguments.getOrDefault("max-attempts", "5"),
                    "max-attempts"
            );
            String workerId = arguments.getOrDefault(
                    "worker-id",
                    "worker-" + UUID.randomUUID()
            );

            DocumentStateStore stateStore =
                    new DynamoDbDocumentStateStore(dynamoDb, stateTable);

            coordinator = new DocumentMigrationCoordinator(
                    stateStore,
                    jobId,
                    shardNumber,
                    workerId,
                    Duration.ofSeconds(leaseSeconds),
                    maximumAttempts
            );

        } else if (arguments.containsKey("job-id")
                || arguments.containsKey("shard-number")
                || arguments.containsKey("lease-seconds")
                || arguments.containsKey("max-attempts")
                || arguments.containsKey("worker-id")) {

            throw new IllegalArgumentException(
                    "Durable state options require --state-table"
            );
        }

        long succeeded = 0;
        long failed = 0;
        long skipped = 0;

        try (
                source;
                ManifestReader manifestReader = new ManifestReader(manifestPath)
        ) {

            source.validateConnection();

            DestinationWriter writer =
                    new S3DestinationWriter(s3, destinationBucket);

            DestinationKeyResolver keyResolver =
                    new DestinationKeyResolver();

            for (DocumentRecord record : manifestReader) {

                try {
                    if (coordinator == null) {
                        migrateDocument(
                                source,
                                writer,
                                keyResolver,
                                record,
                                policy
                        );
                        succeeded++;
                        continue;
                    }

                    DocumentMigrationCoordinator.Result result =
                            coordinator.execute(
                                    record,
                                    () -> migrateDocument(
                                            source,
                                            writer,
                                            keyResolver,
                                            record,
                                            policy
                                    )
                            );

                    switch (result) {
                        case COMPLETED -> succeeded++;
                        case ALREADY_COMPLETED -> {
                            skipped++;
                            System.out.println(
                                    "SKIPPED already completed: "
                                            + record.idempotencyKey()
                            );
                        }
                        case NOT_CLAIMED -> {
                            skipped++;
                            System.out.println(
                                    "SKIPPED could not claim document: "
                                            + record.idempotencyKey()
                            );
                        }
                    }

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

        System.out.printf(
                "Migration run finished. succeeded=%d failed=%d skipped=%d%n",
                succeeded,
                failed,
                skipped
        );

        if (failed > 0) {
            throw new IllegalStateException(
                    "Migration completed with "
                            + failed
                            + " failed document(s)"
            );
        }
    }

    /**
     * Binds exact recovery execution settings to an already prepared job.
     * The manifest fingerprint is recalculated from S3 before the immutable bind.
     */
    private static void configureRecovery(
            DynamoDbClient dynamoDb,
            S3Client s3,
            SfnClient stepFunctions,
            Map<String, String> arguments,
            String region
    ) throws Exception {
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        String manifestS3Uri = required(arguments, "manifest-s3-uri");
        String sourceBucket = required(arguments, "source-bucket");
        String sourcePrefix = requiredPresent(arguments, "source-prefix");
        JobStateStore store = new DynamoDbJobStateStore(dynamoDb, jobStateTable);

        Path manifestPath = resolveManifestPath(s3, arguments);
        RecoveryWavePlanner.Plan verifiedPlan;
        try {
            verifiedPlan = new RecoveryWavePlanner(store).plan(
                    jobId, manifestPath, sourceBucket, sourcePrefix, Instant.now());
        } finally {
            Files.deleteIfExists(manifestPath);
        }

        String stateMachineArn = required(arguments, "state-machine-arn");
        var stateMachine = stepFunctions.describeStateMachine(
                DescribeStateMachineRequest.builder().stateMachineArn(stateMachineArn).build());
        if (stateMachine.type() != StateMachineType.STANDARD) {
            throw new IllegalArgumentException(
                    "Recovery requires a Standard Step Functions state machine");
        }

        RecoveryJobConfiguration configuration = new RecoveryJobConfiguration(
                verifiedPlan.manifestFingerprint(),
                sourceBucket,
                sourcePrefix,
                required(arguments, "destination-bucket"),
                manifestS3Uri,
                required(arguments, "state-table"),
                jobStateTable,
                stateMachineArn,
                VersionPolicy.valueOf(required(arguments, "version-policy")),
                region,
                parsePositiveInt(required(arguments, "lease-seconds"), "lease-seconds"),
                parsePositiveInt(required(arguments, "max-attempts"), "max-attempts"),
                parsePositiveInt(required(arguments, "max-concurrent-shards"), "max-concurrent-shards")
        );

        if (verifiedPlan.job().status() != MigrationJobStatus.READY) {
            throw new IllegalStateException(
                    "Recovery configuration can only be bound before the job starts running");
        }
        if (!store.bindRecoveryConfiguration(jobId, configuration)) {
            throw new IllegalStateException(
                    "Recovery configuration conflicts with the immutable configuration already bound to this job");
        }
        System.out.printf(
                "Immutable recovery configuration bound. job=%s manifestSha256=%s stateMachine=%s maxConcurrentShards=%d%n",
                jobId, verifiedPlan.manifestFingerprint(), stateMachineArn,
                configuration.maximumConcurrentShards());
    }

    /**
     * Prepares the durable job and registers its complete shard plan.
     * Run this once before launching the independent shard workers.
     */
    private static void prepareJob(
            DynamoDbClient dynamoDb,
            Map<String, String> arguments
    ) throws Exception {
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        Path manifestPath = Path.of(required(arguments, "manifest"));
        int totalShards = parsePositiveInt(
                required(arguments, "total-shards"),
                "total-shards"
        );

        String fingerprint = ManifestFingerprint.calculate(manifestPath);
        JobStateStore jobStateStore =
                new DynamoDbJobStateStore(dynamoDb, jobStateTable);

        MigrationJobState prepared = new JobPreparationCoordinator(jobStateStore)
                .prepare(jobId, totalShards, Instant.now(), fingerprint);

        System.out.printf(
                "Migration job prepared. job=%s status=%s totalShards=%d manifestSha256=%s%n",
                jobId,
                prepared.status(),
                prepared.totalShards(),
                fingerprint
        );
    }

    /**
     * Prints a read-only recovery assessment for every persisted shard.
     * No leases are claimed and no DynamoDB records are changed.
     */
    private static void inspectJob(
            DynamoDbClient dynamoDb,
            Map<String, String> arguments
    ) {
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        JobStateStore store = new DynamoDbJobStateStore(dynamoDb, jobStateTable);
        MigrationJobState job = store.getJob(jobId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Migration job was not found: " + jobId
                ));

        Instant inspectedAt = Instant.now();
        System.out.printf(
                "Read-only shard inspection. job=%s jobStatus=%s inspectedAt=%s "
                        + "registeredShards=%d expectedShards=%d%n",
                jobId, job.status(), inspectedAt, store.listShards(jobId).size(),
                job.totalShards()
        );
        System.out.println("shard,status,attempts,worker,leaseExpiry,assessment,lastError");

        store.listShards(jobId).stream()
                .sorted(java.util.Comparator.comparingInt(
                        io.github.awsdocmigration.engine.MigrationShardState::shardNumber
                ))
                .forEach(shard -> {
                    ShardRecoveryInspector.Disposition assessment =
                            ShardRecoveryInspector.assess(shard, inspectedAt);
                    String expiry = shard.leaseExpiresAtEpochMillis() == null
                            ? "unknown"
                            : Instant.ofEpochMilli(shard.leaseExpiresAtEpochMillis()).toString();
                    System.out.printf("%d,%s,%d,%s,%s,%s,%s%n",
                            shard.shardNumber(),
                            shard.status(),
                            shard.attemptCount(),
                            csv(shard.workerId()),
                            expiry,
                            assessment,
                            csv(shard.lastError()));
                });

        System.out.println("Inspection only: no shard was claimed or modified.");
    }

    /**
     * Prints a read-only recovery plan. The operation reads the exact S3
     * manifest and consistent DynamoDB job/shard state but does not acquire a
     * recovery wave or call Step Functions.
     */
    private static void planRecovery(
            DynamoDbClient dynamoDb,
            S3Client s3,
            Map<String, String> arguments
    ) throws Exception {
        RecoveryWavePlanner.Plan plan = loadRecoveryPlan(dynamoDb, s3, arguments);
        printRecoveryPlan(plan);
        System.out.println("Plan only: no recovery wave was acquired and no execution was started.");
    }

    /**
     * Starts one explicitly confirmed recovery wave. All validation completes
     * before the durable wave slot is acquired or Step Functions is called.
     */
    private static void dispatchRecovery(
            DynamoDbClient dynamoDb,
            S3Client s3,
            SfnClient stepFunctions,
            Map<String, String> arguments,
            String region
    ) throws Exception {
        validateRecoveryConfirmation(arguments);
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        JobStateStore jobStateStore = new DynamoDbJobStateStore(dynamoDb, jobStateTable);
        RecoveryJobConfiguration configuration = jobStateStore.getRecoveryConfiguration(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "No immutable recovery configuration is bound to job " + jobId
                                + ". Run --mode configure-recovery before dispatch."));
        validateRecoveryConfiguration(configuration, jobStateTable, region);

        RecoveryWavePlanner.Plan plan = loadRecoveryPlan(
                jobStateStore, s3, jobId, configuration);
        printRecoveryPlan(plan);
        if (plan.selectedShardNumbers().isEmpty()) {
            throw new IllegalStateException("No shards are currently eligible for recovery; nothing was dispatched.");
        }

        var stateMachine = stepFunctions.describeStateMachine(
                DescribeStateMachineRequest.builder()
                        .stateMachineArn(configuration.stateMachineArn()).build());
        if (stateMachine.type() != StateMachineType.STANDARD) {
            throw new IllegalStateException(
                    "Recovery dispatch requires a Standard Step Functions state machine.");
        }
        RecoveryWaveExecutionGuard.requireNoRunningExecutionForJob(
                stepFunctions, configuration.stateMachineArn(), plan.job().jobId());

        String waveId = UUID.randomUUID().toString();
        String ownerToken = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String exactInput = RecoveryWaveInputBuilder.build(
                plan.job().jobId(),
                configuration.sourceBucket(),
                configuration.sourcePrefix(),
                configuration.destinationBucket(),
                configuration.manifestS3Uri(),
                plan.job().totalShards(),
                plan.selectedShardNumbers(),
                configuration.documentStateTable(),
                configuration.jobStateTable(),
                configuration.versionPolicy(),
                configuration.region(),
                configuration.leaseSeconds(),
                configuration.maximumDocumentAttempts(),
                waveId
        );

        DynamoDbRecoveryWaveStore waveStore =
                new DynamoDbRecoveryWaveStore(dynamoDb, configuration.jobStateTable());
        var lease = waveStore.acquire(
                plan.job().jobId(),
                waveId,
                ownerToken,
                plan.selectedShardNumbers(),
                "recovery-" + plan.job().jobId(),
                now
        ).orElseThrow(() -> new IllegalStateException(
                "A recovery wave is already active for this job. No new execution was started."));

        StepFunctionsRecoveryWaveDispatcher.Result result =
                new StepFunctionsRecoveryWaveDispatcher(stepFunctions, waveStore)
                        .dispatch(lease, configuration.stateMachineArn(), exactInput, Instant.now());

        System.out.printf(
                "Recovery dispatch result. job=%s wave=%s generation=%d outcome=%s "
                        + "executionStatus=%s executionArn=%s%n",
                lease.jobId(), lease.waveId(), lease.generation(), result.outcome(),
                result.executionStatus(), result.executionArn() == null ? "unknown" : result.executionArn()
        );

        if (result.outcome() == StepFunctionsRecoveryWaveDispatcher.Outcome.UNKNOWN
                || result.outcome() == StepFunctionsRecoveryWaveDispatcher.Outcome.INPUT_MISMATCH
                || result.outcome() == StepFunctionsRecoveryWaveDispatcher.Outcome.STALE_OWNER) {
            throw new IllegalStateException(
                    "Recovery execution status could not be safely confirmed. The wave remains locked; "
                            + "inspect its Step Functions execution and DynamoDB recovery-wave record before retrying.");
        }
    }

    /**
     * Re-reads the latest recovery execution and durable shard records.
     * Finalization is attempted only after Step Functions and the durable
     * recovery-wave record agree on the same terminal execution status.
     */
    /**
     * Runs a finite, explicitly confirmed recovery controller. Every wave is
     * dispatched through the existing guarded path, observed by exact ARN,
     * reconciled with its immutable audit, and followed by a fresh plan.
     */
    private static void autoRecover(
            DynamoDbClient dynamoDb,
            S3Client s3,
            SfnClient stepFunctions,
            Map<String, String> arguments,
            String region
    ) throws Exception {
        validateRecoveryConfirmation(arguments);
        int maximumWaves = boundedInt(arguments, "max-waves", 1, 10);
        int pollSeconds = boundedInt(arguments, "poll-interval-seconds", 1, 30);
        int runtimeSeconds = boundedInt(arguments, "max-runtime-seconds", 30, 3600);
        long deadline = System.nanoTime() + Duration.ofSeconds(runtimeSeconds).toNanos();
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        JobStateStore jobStateStore = new DynamoDbJobStateStore(dynamoDb, jobStateTable);
        DynamoDbRecoveryWaveStore waveStore = new DynamoDbRecoveryWaveStore(dynamoDb, jobStateTable);
        int wavesDispatched = 0;

        while (wavesDispatched < maximumWaves) {
            requireControllerTime(deadline);
            MigrationJobState job = jobStateStore.getJob(jobId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown migration job: " + jobId));
            if (job.status() == MigrationJobStatus.COMPLETED
                    || job.status() == MigrationJobStatus.COMPLETED_WITH_ERRORS) {
                System.out.printf("Automatic recovery stopped: job is terminal (%s).%n", job.status());
                break;
            }

            RecoveryJobConfiguration configuration = jobStateStore.getRecoveryConfiguration(jobId)
                    .orElseThrow(() -> new IllegalStateException(
                            "No immutable recovery configuration is bound to job " + jobId));
            validateRecoveryConfiguration(configuration, jobStateTable, region);
            RecoveryWavePlanner.Plan plan = loadRecoveryPlan(
                    jobStateStore, s3, jobId, configuration);
            printRecoveryPlan(plan);

            long active = plan.assessments().values().stream()
                    .filter(value -> value == ShardRecoveryInspector.Disposition.WAIT_FOR_ACTIVE_LEASE)
                    .count();
            long unknown = plan.assessments().values().stream()
                    .filter(value -> value == ShardRecoveryInspector.Disposition.LEASE_EXPIRY_UNKNOWN)
                    .count();
            if (active > 0 || unknown > 0) {
                System.out.printf(
                        "Automatic recovery stopped: activeLeases=%d unknownLeaseExpiry=%d. "
                                + "No replacement wave will be dispatched.%n", active, unknown);
                break;
            }
            if (plan.selectedShardNumbers().isEmpty()) {
                System.out.println(
                        "Automatic recovery stopped: no eligible shards remain. "
                                + "Exhausted shards require operator review.");
                break;
            }

            dispatchRecovery(dynamoDb, s3, stepFunctions, arguments, region);
            wavesDispatched++;
            awaitAndPersistRecoveryExecution(
                    stepFunctions, waveStore, jobId, pollSeconds, deadline);
            reconcileRecovery(dynamoDb, stepFunctions, arguments);
        }

        System.out.printf(
                "Automatic recovery controller stopped. job=%s wavesDispatched=%d "
                        + "maximumWaves=%d runtimeLimitSeconds=%d%n",
                jobId, wavesDispatched, maximumWaves, runtimeSeconds);
        if (wavesDispatched == maximumWaves) {
            System.out.println("Wave ceiling reached. Run inspect-job before requesting another recovery pass.");
        }
    }

    private static void awaitAndPersistRecoveryExecution(
            SfnClient stepFunctions,
            DynamoDbRecoveryWaveStore waveStore,
            String jobId,
            int pollSeconds,
            long deadline
    ) throws InterruptedException {
        while (true) {
            requireControllerTime(deadline);
            RecoveryWaveSnapshot snapshot = waveStore.get(jobId)
                    .orElseThrow(() -> new IllegalStateException("Recovery wave record disappeared"));
            if ("TERMINAL".equals(snapshot.state())) {
                return;
            }
            if (!"ACTIVE".equals(snapshot.state())
                    || snapshot.executionArn() == null
                    || snapshot.stateMachineArn() == null) {
                throw new IllegalStateException(
                        "Recovery execution identity or state is unknown. The wave remains locked; "
                                + "automatic recovery stopped without reconciliation.");
            }

            var execution = stepFunctions.describeExecution(
                    DescribeExecutionRequest.builder().executionArn(snapshot.executionArn()).build());
            if (!snapshot.executionArn().equals(execution.executionArn())
                    || !snapshot.stateMachineArn().equals(execution.stateMachineArn())) {
                throw new IllegalStateException(
                        "Observed execution identity does not match the persisted wave. The wave remains locked.");
            }
            String observedStatus = execution.status().toString();
            if (!List.of("RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT", "ABORTED")
                    .contains(observedStatus)) {
                throw new IllegalStateException(
                        "Unknown Step Functions status " + observedStatus + "; wave remains locked.");
            }
            if (!waveStore.recordObservedExecutionStatus(
                    snapshot, execution.executionArn(), observedStatus, Instant.now())) {
                throw new IllegalStateException(
                        "Recovery wave changed while observing its execution; controller stopped.");
            }
            if (!"RUNNING".equals(observedStatus)) {
                return;
            }
            requireControllerTime(deadline);
            Thread.sleep(Duration.ofSeconds(pollSeconds).toMillis());
        }
    }

    static int boundedInt(
            Map<String, String> arguments,
            String name,
            int minimum,
            int maximum
    ) {
        int value = parsePositiveInt(required(arguments, name), name);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    "--" + name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static void requireControllerTime(long deadline) {
        if (System.nanoTime() >= deadline) {
            throw new IllegalStateException(
                    "Automatic recovery runtime limit reached. Any active wave remains locked; "
                            + "inspect its exact Step Functions execution and reconcile manually.");
        }
    }

    private static void reconcileRecovery(
            DynamoDbClient dynamoDb,
            SfnClient stepFunctions,
            Map<String, String> arguments
    ) {
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        JobStateStore jobStateStore =
                new DynamoDbJobStateStore(dynamoDb, jobStateTable);
        DynamoDbRecoveryWaveStore waveStore =
                new DynamoDbRecoveryWaveStore(dynamoDb, jobStateTable);

        RecoveryWaveReconciliationCoordinator.Result result =
                new RecoveryWaveReconciliationCoordinator(
                        stepFunctions, waveStore, jobStateStore
                ).reconcile(jobId, Instant.now());

        System.out.printf(
                "Recovery reconciliation. job=%s wave=%s generation=%d "
                        + "executionStatus=%s registeredShards=%d completed=%d "
                        + "retryable=%d active=%d blocked=%d exhausted=%d "
                        + "jobFinalization=%s%n",
                result.jobId(), result.waveId(), result.generation(),
                result.executionStatus(), result.registeredShards(),
                result.completedShards(), result.retryableShards(),
                result.activeShards(), result.blockedShards(),
                result.exhaustedShards(), result.finalizationStatus()
        );
        System.out.println(
                "A durable reconciliation audit record was committed atomically with the "
                        + "recovery-wave unlock. It includes the shard disposition counts "
                        + "and finalization result.");
    }

    private static RecoveryWavePlanner.Plan loadRecoveryPlan(
            JobStateStore jobStateStore,
            S3Client s3,
            String jobId,
            RecoveryJobConfiguration configuration
    ) throws Exception {
        Path manifestPath = resolveManifestPath(
                s3, Map.of("manifest-s3-uri", configuration.manifestS3Uri()));
        try {
            RecoveryWavePlanner.Plan plan = new RecoveryWavePlanner(jobStateStore).plan(
                    jobId,
                    manifestPath,
                    configuration.sourceBucket(),
                    configuration.sourcePrefix(),
                    Instant.now());
            if (!configuration.manifestFingerprint().equalsIgnoreCase(plan.manifestFingerprint())) {
                throw new IllegalStateException(
                        "Persisted recovery configuration fingerprint does not match the prepared manifest");
            }
            return plan;
        } finally {
            Files.deleteIfExists(manifestPath);
        }
    }

    static void validateRecoveryConfiguration(
            RecoveryJobConfiguration configuration,
            String jobStateTable,
            String region
    ) {
        if (configuration == null) {
            throw new IllegalArgumentException("Immutable recovery configuration is required");
        }
        if (!jobStateTable.equals(configuration.jobStateTable())) {
            throw new IllegalArgumentException(
                    "The supplied --job-state-table does not match the immutable recovery configuration");
        }
        if (!region.equals(configuration.region())) {
            throw new IllegalArgumentException(
                    "The runtime AWS Region does not match the immutable recovery configuration Region");
        }
    }

    private static RecoveryWavePlanner.Plan loadRecoveryPlan(
            DynamoDbClient dynamoDb,
            S3Client s3,
            Map<String, String> arguments
    ) throws Exception {
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        String manifestS3Uri = required(arguments, "manifest-s3-uri");
        String sourceBucket = required(arguments, "source-bucket");
        String sourcePrefix = requiredPresent(arguments, "source-prefix");
        Path manifestPath = resolveManifestPath(
                s3, Map.of("manifest-s3-uri", manifestS3Uri));
        try {
            JobStateStore jobStateStore = new DynamoDbJobStateStore(dynamoDb, jobStateTable);
            return new RecoveryWavePlanner(jobStateStore).plan(
                    jobId, manifestPath, sourceBucket, sourcePrefix, Instant.now());
        } finally {
            Files.deleteIfExists(manifestPath);
        }
    }

    private static void printRecoveryPlan(RecoveryWavePlanner.Plan plan) {
        System.out.printf(
                "Recovery plan. job=%s status=%s totalShards=%d manifestSha256=%s "
                        + "eligibleShards=%s inspectedAt=%s%n",
                plan.job().jobId(), plan.job().status(), plan.job().totalShards(),
                plan.manifestFingerprint(), plan.selectedShardNumbers(), plan.inspectedAt());
        System.out.println("shard,status,attempts,assessment");
        for (var shard : plan.shards()) {
            System.out.printf("%d,%s,%d,%s%n",
                    shard.shardNumber(), shard.status(), shard.attemptCount(),
                    plan.assessments().get(shard.shardNumber()));
        }
    }

    static void validateRecoveryConfirmation(Map<String, String> arguments) {
        if (!"true".equalsIgnoreCase(arguments.get("confirm-recovery"))) {
            throw new IllegalArgumentException(
                    "Dispatch requires explicit --confirm-recovery true. Run --mode plan-recovery first.");
        }
        if (!"true".equalsIgnoreCase(arguments.get("confirm-prior-execution-terminal"))) {
            throw new IllegalArgumentException(
                    "Dispatch requires --confirm-prior-execution-terminal true after verifying the prior job execution is terminal.");
        }
    }

    private static String requiredPresent(Map<String, String> arguments, String key) {
        if (!arguments.containsKey(key) || arguments.get(key) == null) {
            throw new IllegalArgumentException(
                    "Missing --" + key + " (use an empty value when the S3 bucket root is intended)");
        }
        return arguments.get(key);
    }

    private static String csv(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Workers verify the immutable prepared manifest and complete shard plan
     * instead of racing to prepare the same job concurrently.
     */
    private static void verifyPreparedJob(
            JobStateStore jobStateStore,
            String jobId,
            int totalShards,
            String manifestFingerprint
    ) {
        MigrationJobState job = jobStateStore.getJob(jobId)
                .orElseThrow(() -> new IllegalStateException(
                        "Migration job has not been prepared: " + jobId
                ));

        if (job.status() != MigrationJobStatus.READY
                && job.status() != MigrationJobStatus.RUNNING) {
            throw new IllegalStateException(
                    "Migration job must be READY or RUNNING before workers start; current status="
                            + job.status()
            );
        }

        if (job.totalShards() != totalShards) {
            throw new IllegalStateException(
                    "Prepared job has " + job.totalShards()
                            + " shards, but worker requested " + totalShards
            );
        }

        if (job.manifestFingerprint() == null
                || !manifestFingerprint.equalsIgnoreCase(job.manifestFingerprint())) {
            throw new IllegalStateException(
                    "Manifest fingerprint does not match the prepared job"
            );
        }

        Map<Integer, Boolean> registered = new HashMap<>();
        jobStateStore.listShards(jobId).forEach(
                shard -> registered.put(shard.shardNumber(), Boolean.TRUE)
        );

        if (registered.size() != totalShards) {
            throw new IllegalStateException(
                    "Prepared job has " + registered.size()
                            + " registered shards; expected " + totalShards
            );
        }

        for (int shardNumber = 0; shardNumber < totalShards; shardNumber++) {
            if (!registered.containsKey(shardNumber)) {
                throw new IllegalStateException(
                        "Prepared job is missing shard " + shardNumber
                );
            }
        }
    }

    /**
     * Executes one shard of a prepared, fingerprint-bound migration job.
     *
     * The job/shard lifecycle table and document lease table are separate,
     * matching the Terraform reference architecture.
     */
    private static void migrateSharded(
            DocumentSource source,
            S3Client s3,
            DynamoDbClient dynamoDb,
            Map<String, String> arguments
    ) throws Exception {

        String destinationBucket = required(arguments, "destination-bucket");
        String jobId = required(arguments, "job-id");
        String jobStateTable = required(arguments, "job-state-table");
        String documentStateTable = required(arguments, "state-table");
        Path manifestPath = resolveManifestPath(s3, arguments);

        int totalShards = parsePositiveInt(
                required(arguments, "total-shards"),
                "total-shards"
        );
        int shardNumber = parseNonNegativeInt(
                required(arguments, "shard-number"),
                "shard-number"
        );
        if (shardNumber >= totalShards) {
            throw new IllegalArgumentException(
                    "--shard-number must be less than --total-shards"
            );
        }

        int leaseSeconds = parsePositiveInt(
                arguments.getOrDefault("lease-seconds", "300"),
                "lease-seconds"
        );
        if (leaseSeconds < 3) {
            throw new IllegalArgumentException(
                    "--lease-seconds must be at least 3 for shard lease renewal"
            );
        }

        int maximumAttempts = parsePositiveInt(
                arguments.getOrDefault("max-attempts", "5"),
                "max-attempts"
        );
        String workerId = arguments.getOrDefault(
                "worker-id",
                "worker-" + UUID.randomUUID()
        );
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("--worker-id cannot be blank");
        }

        VersionPolicy policy = VersionPolicy.valueOf(
                arguments.getOrDefault("version-policy", "ALL_VERSIONS")
        );

        try (source) {
            source.validateConnection();

            String fingerprint = ManifestFingerprint.calculate(manifestPath);

            JobStateStore jobStateStore =
                    new DynamoDbJobStateStore(dynamoDb, jobStateTable);
            DocumentStateStore documentStateStore =
                    new DynamoDbDocumentStateStore(dynamoDb, documentStateTable);

            verifyPreparedJob(
                    jobStateStore,
                    jobId,
                    totalShards,
                    fingerprint
            );

            DestinationWriter writer =
                    new S3DestinationWriter(s3, destinationBucket);
            DestinationKeyResolver keyResolver =
                    new DestinationKeyResolver();

            ShardExecutionCoordinator execution =
                    new ShardExecutionCoordinator(
                            jobStateStore,
                            documentStateStore,
                            jobId,
                            shardNumber,
                            workerId,
                            Duration.ofSeconds(leaseSeconds),
                            Duration.ofSeconds(leaseSeconds),
                            maximumAttempts
                    );

            ShardExecutionCoordinator.Result result;
            try {
                result = execution.execute(
                        manifestPath,
                        record -> migrateDocument(
                                source,
                                writer,
                                keyResolver,
                                record,
                                policy
                        )
                );
            } catch (Exception executionFailure) {
                try {
                    reportJobFinalization(jobStateStore, jobId);
                } catch (Exception finalizationFailure) {
                    executionFailure.addSuppressed(finalizationFailure);
                }
                throw executionFailure;
            }

            reportJobFinalization(jobStateStore, jobId);

            System.out.printf(
                    "Shard execution completed. job=%s shard=%d assigned=%d "
                            + "migrated=%d alreadyCompleted=%d worker=%s%n",
                    jobId,
                    result.shardNumber(),
                    result.assigned(),
                    result.migrated(),
                    result.alreadyCompleted(),
                    workerId
            );
        }
    }

    /** Downloads an immutable S3 manifest for an ephemeral ECS worker. */
    static Path resolveManifestPath(S3Client s3, Map<String, String> arguments)
            throws IOException {
        String manifestUri = arguments.get("manifest-s3-uri");
        if (manifestUri == null || manifestUri.isBlank()) {
            return Path.of(required(arguments, "manifest"));
        }
        if (arguments.containsKey("manifest")) {
            throw new IllegalArgumentException(
                    "Specify either --manifest or --manifest-s3-uri, not both"
            );
        }
        if (!manifestUri.startsWith("s3://")) {
            throw new IllegalArgumentException(
                    "--manifest-s3-uri must use the s3://bucket/key format"
            );
        }
        String location = manifestUri.substring("s3://".length());
        int separator = location.indexOf('/');
        if (separator < 1 || separator == location.length() - 1) {
            throw new IllegalArgumentException(
                    "--manifest-s3-uri must include both bucket and object key"
            );
        }
        String bucket = location.substring(0, separator);
        String key = location.substring(separator + 1);
        Path downloaded = Files.createTempFile("migration-manifest-", ".jsonl");
        try {
            s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build(),
                    ResponseTransformer.toFile(downloaded)
            );
            downloaded.toFile().deleteOnExit();
            return downloaded;
        } catch (RuntimeException failure) {
            Files.deleteIfExists(downloaded);
            throw failure;
        }
    }

    private static void reportJobFinalization(
            JobStateStore jobStateStore,
            String jobId
    ) {
        Optional<MigrationJobState> finalized =
                new JobFinalizationCoordinator(jobStateStore)
                        .finalizeIfReady(jobId, Instant.now());

        System.out.printf(
                "Job finalization status. job=%s status=%s%n",
                jobId,
                finalized.map(state -> state.status().name())
                        .orElse("WAITING_FOR_SHARDS")
        );
    }

    /**
     * Validates the opt-in sharded CLI configuration without changing the
     * existing stateless and document-state-only command forms.
     */
    static void validateShardedExecutionArguments(
            Map<String, String> arguments
    ) {
        boolean sharded = hasText(arguments.get("job-state-table"));

        if (!sharded) {
            if (arguments.containsKey("total-shards")) {
                throw new IllegalArgumentException(
                        "--total-shards requires --job-state-table"
                );
            }
            return;
        }

        required(arguments, "state-table");
        required(arguments, "job-id");
        required(arguments, "total-shards");
        required(arguments, "shard-number");

        int totalShards = parsePositiveInt(
                arguments.get("total-shards"),
                "total-shards"
        );
        int shardNumber = parseNonNegativeInt(
                arguments.get("shard-number"),
                "shard-number"
        );
        if (shardNumber >= totalShards) {
            throw new IllegalArgumentException(
                    "--shard-number must be less than --total-shards"
            );
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Creates the configured source connector.
     */
    private static DocumentSource createSource(
            String sourceType,
            Map<String, String> arguments,
            S3Client s3
    ) {

        return switch (sourceType) {

            case "filesystem" -> new FilesystemSource(
                    Path.of(required(arguments, "source-root"))
            );

            case "s3" -> new S3Source(
                    s3,
                    required(arguments, "source-bucket"),
                    arguments.getOrDefault("source-prefix", "")
            );

            default -> throw new IllegalArgumentException(
                    "Unsupported source-type: " + sourceType
            );
        };
    }

    /**
     * Downloads, verifies and uploads one document, returning the
     * destination identity needed for durable completion state.
     */
    static DocumentMigrationCoordinator.MigrationOutcome migrateDocument(
            DocumentSource source,
            DestinationWriter writer,
            DestinationKeyResolver keyResolver,
            DocumentRecord record,
            VersionPolicy policy
    ) throws Exception {

        Path temporaryFile = null;

        try (InputStream input = source.openStream(record)) {

            temporaryFile = Files.createTempFile("migration-", ".bin");

            Files.copy(
                    input,
                    temporaryFile,
                    StandardCopyOption.REPLACE_EXISTING
            );

            long actualSize = Files.size(temporaryFile);

            if (actualSize != record.sizeBytes()) {
                throw new IllegalStateException(
                        "Downloaded size mismatch for "
                                + record.idempotencyKey()
                                + ": expected=" + record.sizeBytes()
                                + ", actual=" + actualSize
                );
            }

            String digest;

            try (InputStream verificationStream =
                         Files.newInputStream(temporaryFile)) {
                digest = Checksums.sha256(verificationStream);
            }

            String expectedChecksum = record.expectedSha256();

            if (expectedChecksum != null
                    && !expectedChecksum.equalsIgnoreCase(digest)) {
                throw new IllegalStateException(
                        "SHA-256 mismatch for " + record.idempotencyKey()
                );
            }

            String destinationKey = keyResolver.resolve(record, policy);

            try (InputStream uploadStream =
                         Files.newInputStream(temporaryFile)) {
                writer.write(record, destinationKey, uploadStream, digest);
            }

            return new DocumentMigrationCoordinator.MigrationOutcome(
                    destinationKey,
                    digest
            );

        } finally {
            if (temporaryFile != null) {
                Files.deleteIfExists(temporaryFile);
            }
        }
    }

    private static int parsePositiveInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new IllegalArgumentException(
                        "--" + name + " must be at least 1"
                );
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + name + " must be a positive integer",
                    e
            );
        }
    }

    private static int parseNonNegativeInt(String value, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(
                        "--" + name + " cannot be negative"
                );
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "--" + name + " must be a non-negative integer",
                    e
            );
        }
    }

    /**
     * Parses command-line arguments in --name value format.
     */
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

    /**
     * Returns a required non-empty command-line argument.
     */
    private static String required(
            Map<String, String> arguments,
            String key
    ) {

        String value = arguments.get(key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing --" + key);
        }

        return value;
    }
}
