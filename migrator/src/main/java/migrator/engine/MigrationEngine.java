package migrator.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import migrator.ClassMigrator;
import migrator.alert.MigrationAlertLogger;
import migrator.config.MigrationConfig;
import migrator.config.MigrationConfigLoader;
import migrator.commit.*;
import migrator.exceptions.MigrateException;
import migrator.load.*;
import migrator.heap.*;
import migrator.metrics.MigrationMetrics;
import migrator.metrics.MigrationMetrics.Phase;
import migrator.metrics.MigrationMetricsCollector;
import migrator.patch.*;
import migrator.phase.*;
import migrator.plan.*;
import migrator.registry.RegistryUpdater;
import migrator.scanner.*;
import migrator.smoke.SmokeTestReport;
import migrator.smoke.SmokeTestRunner;
import migrator.state.MigrationState;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Final MigrationEngine — orchestrates live migration end-to-end, including:
 *  - first pass (allocate & migrate)
 *  - signal before critical phase (app should quiesce)
 *  - second pass (patch references)
 *  - registry updates
 *  - signal after critical phase (app may resume)
 *  - smoke-tests
 *  - commit (delete checkpoint) OR rollback (restore checkpoint)
 *
 * Notes:
 *  - Engine does NOT perform pause/resume itself; it only signals via MigrationPhaseListener.
 *  - RollbackManager.restore may not return (platform-specific).
 */
public final class MigrationEngine {

    private static final Logger log = LoggerFactory.getLogger(MigrationEngine.class);

    private final MigrationPlan plan;
    private final HeapWalker heapWalker;
    private final ForwardingTable forwarding;
    private final ReferencePatcher referencePatcher;
    private final RegistryUpdater registryUpdater;
    private final MigrationPhaseListener phaseListener;

    private final SmokeTestRunner smokeRunner;
    private final CommitManager commitManager;
    private final RollbackManager rollbackManager;

    // migration id generator
    private static final AtomicLong MIGRATION_COUNTER = new AtomicLong(1L);

    // cache of migrate method per migrator class to avoid reflection cost
    private final ConcurrentMap<Class<?>, Method> migrateMethodCache = new ConcurrentHashMap<>();

    // Configuration: true = full heap walk, false = filtered heap walk for specified classes only
    private boolean fullHeapWalk = true;

    // Metrics collection
    private final MigrationMetricsCollector metricsCollector = new MigrationMetricsCollector();
    private static volatile MigrationMetrics lastMetrics;

    // Timeout configuration for migration operations
    private MigrationTimeoutConfig timeoutConfig = MigrationTimeoutConfig.DEFAULTS;

    public MigrationEngine() throws MigrateException {
        this(null, null);
    }

    public MigrationEngine(ClassLoader classLoader) throws MigrateException {
        this(classLoader, null);
    }

    public MigrationEngine(ClassLoader classLoader, String jarPath) throws MigrateException {
        AnnotationScanResult scan = AnnotationScanner.scan(classLoader, jarPath);
        ComponentResolver resolver = new ComponentResolver();

        try {
            MigratorDescriptor descriptor = new MigratorDescriptor(resolver.resolveMigrator(scan.migrator()));
            plan = MigrationPlan.build(List.of(descriptor));
        } catch (MigrateException e) {
            throw new MigrateException("Failed to build migration plan", e);
        }

        heapWalker = new NativeHeapWalker();
        forwarding = new ForwardingTable();
        referencePatcher = new ReflectionReferencePatcher(forwarding);
        registryUpdater = new RegistryUpdater(forwarding, referencePatcher);
        smokeRunner = resolver.resolveSmokeTestRunner(scan.smokeTests());
        commitManager = resolver.resolveCommitManager(scan.commitManager());
        rollbackManager = resolver.resolveRollbackManager(scan.rollbackManager());

        MigrationPhaseListener pl = resolver.resolvePhaseListener(scan.phaseListener());
        phaseListener = pl == null ? NoopPhaseListener.INSTANCE : pl;
    }

    /**
     * Set heap walk mode.
     * @param fullHeapWalk true for full heap walk (default), false for filtered heap walk
     * @return this engine for method chaining
     */
    public MigrationEngine setFullHeapWalk(boolean fullHeapWalk) {
        this.fullHeapWalk = fullHeapWalk;
        return this;
    }

    /**
     * Apply migration configuration.
     */
    public MigrationEngine applyConfig(MigrationConfig config) {
        if (config == null) return this;

        this.fullHeapWalk = config.isFullHeapWalk();
        this.timeoutConfig = MigrationTimeoutConfig.builder()
                .heapWalkTimeout(config.heapWalkTimeout())
                .heapSnapshotTimeout(config.heapSnapshotTimeout())
                .criticalPhaseTimeout(config.criticalPhaseTimeout())
                .smokeTestTimeout(config.smokeTestTimeout())
                .build();

        MigrationState.getInstance().setMaxHistorySize(config.historySize());
        MigrationAlertLogger.setAlertLevel(config.alertLevel());

        log.debug("Applied config: {}", config);
        return this;
    }

    /**
     * Load configuration from the default classpath resource and apply it.
     *
     * @return this engine for method chaining
     * @see MigrationConfigLoader#load()
     */
    public MigrationEngine loadAndApplyConfig() {
        return applyConfig(MigrationConfigLoader.load());
    }

    /**
     * Validate heap size is within configured limits.
     */
    public static void validateHeapSize(MigrationConfig config) throws MigrateException {
        if (config == null) return;

        long MB = 1024 * 1024;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / MB;
        long maxMb = rt.maxMemory() / MB;

        if (config.minHeapSizeMb() > 0 && maxMb < config.minHeapSizeMb()) {
            throw new MigrateException(
                    "Heap " + maxMb + " MB below minimum " + config.minHeapSizeMb() + " MB");
        }

        if (config.maxHeapSizeMb() > 0 && usedMb > config.maxHeapSizeMb()) {
            throw new MigrateException(
                    "Used heap " + usedMb + " MB exceeds max " + config.maxHeapSizeMb() + " MB");
        }
    }

    /**
     * @return true if full heap walk is enabled, false for filtered heap walk
     */
    public boolean isFullHeapWalk() {
        return fullHeapWalk;
    }

    /**
     * Set timeout configuration for migration operations.
     *
     * <p>Timeouts can be configured for:
     * <ul>
     *   <li>Heap walk operations</li>
     *   <li>Heap snapshot creation</li>
     *   <li>Critical phase callbacks</li>
     *   <li>Smoke test execution</li>
     * </ul>
     *
     * <h2>Example:</h2>
     * <pre>
     * engine.setTimeoutConfig(MigrationTimeoutConfig.builder()
     *     .heapWalkTimeoutSeconds(60)
     *     .criticalPhaseTimeoutSeconds(30)
     *     .smokeTestTimeoutSeconds(10)
     *     .build());
     * </pre>
     *
     * @param config the timeout configuration, or null to use defaults (no timeouts)
     * @return this engine for method chaining
     * @see MigrationTimeoutConfig
     */
    public MigrationEngine setTimeoutConfig(MigrationTimeoutConfig config) {
        this.timeoutConfig = config != null ? config : MigrationTimeoutConfig.DEFAULTS;
        log.debug("Timeout configuration set: {}", this.timeoutConfig);
        return this;
    }

    /**
     * Get the current timeout configuration.
     *
     * @return the current timeout configuration
     */
    public MigrationTimeoutConfig getTimeoutConfig() {
        return timeoutConfig;
    }

    /**
     * Get the metrics from the last migration execution.
     *
     * @return the last migration metrics, or null if no migration has run
     */
    public static MigrationMetrics getLastMetrics() {
        return lastMetrics;
    }

    /**
     * Convenience method to set all timeouts to the same value.
     *
     * @param timeout the timeout duration for all operations
     * @return this engine for method chaining
     */
    public MigrationEngine setAllTimeouts(Duration timeout) {
        return setTimeoutConfig(MigrationTimeoutConfig.builder()
                .allTimeouts(timeout)
                .build());
    }

    /**
     * Convenience method to set all timeouts to the same value in seconds.
     *
     * @param seconds the timeout in seconds for all operations, or 0 to disable
     * @return this engine for method chaining
     */
    public MigrationEngine setAllTimeoutsSeconds(long seconds) {
        return setTimeoutConfig(MigrationTimeoutConfig.builder()
                .allTimeoutsSeconds(seconds)
                .build());
    }

    /**
     * Factory method that creates a MigrationEngine and immediately runs migration.
     *
     * <p>Configuration (heap walk mode, timeouts, etc.) is loaded from classpath
     * (migration.properties or migration.yml).
     *
     * <h2>Example:</h2>
     * <pre>
     * MigrationEngine.createAndMigrate(Set.of(MyService.class));
     * </pre>
     *
     * @param classesToScan classes to scan for registry updates
     * @return the MigrationEngine after migration completes
     * @throws MigrateException if migration fails or times out
     */
    public static MigrationEngine createAndMigrate(
            Collection<Class<?>> classesToScan) throws MigrateException {
        return createAndMigrate(classesToScan, null, null, null, null);
    }

    /**
     * Factory method that creates a MigrationEngine and immediately runs migration.
     *
     * <p>Configuration (heap walk mode, timeouts, etc.) is loaded from classpath
     * (migration.properties or migration.yml).
     *
     * <h2>Example:</h2>
     * <pre>
     * // With agent classloader
     * MigrationEngine.createAndMigrate(Set.of(MyService.class), agentClassLoader, jarPath);
     * </pre>
     *
     * @param classesToScan classes to scan for registry updates
     * @param classLoader optional classloader to scan for annotated classes (null for default)
     * @param jarPath optional path to JAR file containing migration classes (null for default)
     * @return the MigrationEngine after migration completes
     * @throws MigrateException if migration fails or times out
     */
    public static MigrationEngine createAndMigrate(
            Collection<Class<?>> classesToScan,
            ClassLoader classLoader,
            String jarPath) throws MigrateException {
        return createAndMigrate(classesToScan, classLoader, jarPath, null, null);
    }

    /**
     * Factory method that creates a MigrationEngine, runs migration, and updates generic containers.
     *
     * <p>Configuration (heap walk mode, timeouts, etc.) is loaded from classpath
     * (migration.properties or migration.yml).
     *
     * <p>This overload allows updating generic containers (like {@code List<User>}, {@code Map<String, User>})
     * where elements implement a common interface. After migration completes, the specified containers
     * are scanned and any elements matching the interface type are replaced with their migrated versions.
     *
     * <h2>Example:</h2>
     * <pre>
     * List&lt;User&gt; userCache = ...;
     * Map&lt;String, User&gt; userRegistry = ...;
     *
     * MigrationEngine.createAndMigrate(
     *     Set.of(MyService.class),
     *     null,
     *     null,
     *     List.of(userCache, userRegistry),
     *     User.class
     * );
     * </pre>
     *
     * @param classesToScan classes to scan for registry updates
     * @param classLoader optional classloader to scan for annotated classes (null for default)
     * @param jarPath optional path to JAR file containing migration classes (null for default)
     * @param genericContainers containers holding elements of the interface type to update (null if not needed)
     * @param interfaceType the common interface type of elements in the containers (null if not needed)
     * @return the MigrationEngine after migration completes
     * @throws MigrateException if migration fails or times out
     */
    public static MigrationEngine createAndMigrate(
            Collection<Class<?>> classesToScan,
            ClassLoader classLoader,
            String jarPath,
            Collection<?> genericContainers,
            Class<?> interfaceType) throws MigrateException {
        MigrationConfig config = MigrationConfigLoader.load();
        validateHeapSize(config);

        MigrationEngine engine = new MigrationEngine(classLoader, jarPath);
        engine.applyConfig(config);

        Duration timeout = config.heapWalkTimeout();
        if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
            engine.migrateWithTimeout(classesToScan, genericContainers, interfaceType, timeout);
        } else {
            engine.migrate(classesToScan, genericContainers, interfaceType);
        }
        return engine;
    }

    /**
     * Attach to a running JVM and load the migration agent.
     *
     * <p>This method uses the Java Attach API to connect to a running JVM process
     * and dynamically load the migration agent JAR. The agent's {@code agentmain}
     * method is then invoked to trigger the migration.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(String pid, String agentJarPath) throws MigrateException {
        return attachAndLoad(pid, agentJarPath, null);
    }

    /**
     * Attach to a running JVM and load the migration agent with custom arguments.
     *
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @param agentArgs additional arguments to pass to the agent
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(String pid, String agentJarPath, String agentArgs) throws MigrateException {
        if (agentArgs == null)
            return VirtualMachineAgentLoader.attachAndLoad(pid, agentJarPath);
        else
            return VirtualMachineAgentLoader.attachAndLoad(pid, agentJarPath, agentArgs);
    }

    /**
     * Attach to a running JVM and load the migration agent using a custom loader.
     *
     * @param loader the agent loader to use
     * @param pid the process ID of the target JVM
     * @param agentJarPath the path to the migration agent JAR file
     * @return result of the loading operation
     * @throws MigrateException if attachment or loading fails
     */
    public static LoaderResult attachAndLoad(AgentLoader loader, String pid, String agentJarPath) throws MigrateException {
        return loader.load(pid, agentJarPath);
    }

    public MigrationEngine(
            Class<? extends ClassMigrator<?, ?>> migrator,
            MigrationPhaseListener phaseListener,
            SmokeTestRunner smokeRunner,
            CommitManager commitManager,
            RollbackManager rollbackManager
    ) throws MigrateException {
        try {
            MigratorDescriptor descriptor = new MigratorDescriptor(migrator);
            this.plan = MigrationPlan.build(List.of(descriptor));
        } catch (MigrateException e) {
            throw new MigrateException("Failed to build migration plan", e);
        }

        heapWalker = new NativeHeapWalker();
        forwarding = new ForwardingTable();
        referencePatcher = new ReflectionReferencePatcher(forwarding);
        registryUpdater = new RegistryUpdater(forwarding, referencePatcher);
        this.phaseListener = phaseListener == null ? NoopPhaseListener.INSTANCE : phaseListener;
        this.smokeRunner = Objects.requireNonNull(smokeRunner, "smokeRunner");
        this.commitManager = Objects.requireNonNull(commitManager, "commitManager");
        this.rollbackManager = Objects.requireNonNull(rollbackManager, "rollbackManager");
    }

    /**
     * Run migration with generic container updates. classesToScan are typically target classes and are used by RegistryUpdater.
     */
    public void migrate(Collection<Class<?>> classesToScan, Collection<?> genericContainers, Class<?> interfaceType) throws MigrateException {
        doMigrate(classesToScan, genericContainers, interfaceType);
    }

    /**
     * Run migration with a total timeout. If the timeout is exceeded, rollback is triggered.
     *
     * @param classesToScan classes to scan for registry updates
     * @param genericContainers optional containers to update
     * @param interfaceType optional interface type for generic containers
     * @param timeout maximum duration for the entire migration
     * @throws MigrateException if migration fails or times out
     */
    public void migrateWithTimeout(
            Collection<Class<?>> classesToScan,
            Collection<?> genericContainers,
            Class<?> interfaceType,
            Duration timeout) throws MigrateException {
        Objects.requireNonNull(timeout, "timeout");

        log.info("Starting migration with timeout of {}", timeout);

        try {
            TimeoutExecutor.executeWithTimeoutChecked(
                    "migration",
                    timeout,
                    () -> doMigrate(classesToScan, genericContainers, interfaceType)
            );
        } catch (MigrateException me) {
            // Already handled, re-throw
            throw me;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                log.error("Migration timed out after {}, triggering rollback", timeout);
                long migrationId = MigrationState.getInstance().getCurrentMigrationId();
                MigrationAlertLogger.migrationTimeout(migrationId, timeout.toMillis(), MigrationState.getInstance().getCurrentPhase());
                MigrationAlertLogger.rollbackTriggered(migrationId, "timeout");
                try {
                    rollbackManager.rollback();
                    MigrationAlertLogger.rollbackCompleted(migrationId, true);
                } catch (Exception rollbackEx) {
                    MigrationAlertLogger.rollbackCompleted(migrationId, false);
                    MigrationState.getInstance().migrationFailed(e, lastMetrics);
                    throw new MigrateException("Migration timed out and rollback failed: " + rollbackEx.getMessage(), e);
                }
                MigrationState.getInstance().migrationFailed(e, lastMetrics);
                throw new MigrateException("Migration timed out after " + timeout.toMillis() + "ms", e);
            }
            throw new MigrateException("Migration failed: " + e.getMessage(), e);
        }
    }

    private void doMigrate(Collection<Class<?>> classesToScan, Collection<?> genericContainers, Class<?> interfaceType) throws MigrateException {
        Objects.requireNonNull(classesToScan, "classesToScan");
        if (plan.orderedMigrators().isEmpty()) return;

        final long migrationId = MIGRATION_COUNTER.getAndIncrement();
        final MigrationContext ctx = new MigrationContext(plan, migrationId);
        final Set<Object> allResolvedOldObjects = new LinkedHashSet<>();
        final Map<MigratorDescriptor, List<Object>> createdPerMigrator = new LinkedHashMap<>();
        final int[] patchedCount = {0};

        // Track migration state and log start
        MigrationState.getInstance().migrationStarted(migrationId);
        MigrationAlertLogger.migrationStarted(migrationId);

        metricsCollector.start(migrationId).migratorCount(plan.orderedMigrators().size());
        boolean beforeCriticalCalled = false;

        try {
            // FIRST PASS
            MigrationState.getInstance().setCurrentPhase(Phase.FIRST_PASS);
            MigrationAlertLogger.phaseStarted(migrationId, Phase.FIRST_PASS);
            long phaseStart = System.currentTimeMillis();
            metricsCollector.timed(Phase.FIRST_PASS, () ->
                    firstPassAllocateAndMigrate(allResolvedOldObjects, createdPerMigrator));
            MigrationAlertLogger.phaseCompleted(migrationId, Phase.FIRST_PASS, System.currentTimeMillis() - phaseStart);

            int totalMigrated = createdPerMigrator.values().stream().mapToInt(List::size).sum();
            metricsCollector.objectsMigrated(totalMigrated);

            Set<Object> pass2Objects = new LinkedHashSet<>(allResolvedOldObjects);
            createdPerMigrator.values().forEach(pass2Objects::addAll);

            // CRITICAL PHASE
            MigrationState.getInstance().setCurrentPhase(Phase.CRITICAL_PHASE);
            MigrationAlertLogger.phaseStarted(migrationId, Phase.CRITICAL_PHASE);
            long criticalPhaseStart = System.currentTimeMillis();
            metricsCollector.timed(Phase.CRITICAL_PHASE, () -> {
                signalBeforeCriticalPhase(ctx);

                // SECOND PASS
                metricsCollector.timed(Phase.SECOND_PASS, () ->
                        patchedCount[0] = secondPassPatchReferencesWithCount(pass2Objects, classesToScan));

                safeAutoPatchStaticFields(classesToScan, pass2Objects);

                // REGISTRY UPDATE
                metricsCollector.timed(Phase.REGISTRY_UPDATE, () -> {
                    registryUpdater.updateAnnotatedRegistries(classesToScan, pass2Objects);
                    updateGenericContainersIfPresent(genericContainers, interfaceType);
                    updateGenericFields(classesToScan, pass2Objects, interfaceType);
                });

                signalAfterCriticalPhase(ctx);
            });
            MigrationAlertLogger.phaseCompleted(migrationId, Phase.CRITICAL_PHASE, System.currentTimeMillis() - criticalPhaseStart);
            beforeCriticalCalled = false;

            metricsCollector.objectsPatched(patchedCount[0]);

            // SMOKE TESTS
            MigrationState.getInstance().setCurrentPhase(Phase.SMOKE_TEST);
            MigrationAlertLogger.phaseStarted(migrationId, Phase.SMOKE_TEST);
            long smokeTestStart = System.currentTimeMillis();
            SmokeTestReport report = metricsCollector.timed(Phase.SMOKE_TEST,
                    () -> runSmokeTestsWithTimeout(createdPerMigrator));
            MigrationAlertLogger.phaseCompleted(migrationId, Phase.SMOKE_TEST, System.currentTimeMillis() - smokeTestStart);

            if (!report.success()) {
                log.error("Smoke tests failed for migration id={}", migrationId);
                MigrationAlertLogger.rollbackTriggered(migrationId, "smoke test failure");
                rollbackManager.rollback();
                MigrationState.getInstance().migrationFailed(new MigrateException("Smoke tests failed"), lastMetrics);
                MigrationAlertLogger.migrationFailed(migrationId, new MigrateException("Smoke tests failed"), Phase.SMOKE_TEST, lastMetrics);
                throw new MigrateException("Smoke tests failed for migration id=" + migrationId);
            }

            commitWithRollback();
            migratorAdvanceEpoch();

            lastMetrics = metricsCollector.finish();
            log.info("Migration metrics: {}", lastMetrics.summary());

            // Track successful completion
            MigrationState.getInstance().setCurrentPhase(null);
            MigrationState.getInstance().migrationCompleted(lastMetrics);
            MigrationAlertLogger.migrationCompleted(migrationId, lastMetrics);

        } catch (MigrateException me) {
            finishMetricsOnError();
            MigrationState.getInstance().migrationFailed(me, lastMetrics);
            MigrationAlertLogger.migrationFailed(migrationId, me, MigrationState.getInstance().getCurrentPhase(), lastMetrics);
            throw me;
        } catch (Exception e) {
            finishMetricsOnError();
            MigrationState.getInstance().migrationFailed(e, lastMetrics);
            MigrationAlertLogger.migrationFailed(migrationId, e, MigrationState.getInstance().getCurrentPhase(), lastMetrics);
            cleanupAndRollback(allResolvedOldObjects, e);
        } finally {
            if (beforeCriticalCalled) {
                safeAfterCriticalPhase(ctx, migrationId);
            }
        }
    }

    private void updateGenericContainersIfPresent(Collection<?> containers, Class<?> interfaceType) {
        if (containers != null && interfaceType != null) {
            log.debug("Updating {} generic containers", containers.size());
            registryUpdater.updateGenericContainers(containers, interfaceType);
        }
    }

    private void updateGenericFields(Collection<Class<?>> classesToScan, Set<Object> pass2Objects, Class<?> interfaceType) {
        Set<Class<?>> types = extractMigratedInterfaceTypes();
        if (interfaceType != null) types.add(interfaceType);
        if (!types.isEmpty()) {
            registryUpdater.updateGenericFieldsInClasses(classesToScan, pass2Objects, types);
        }
    }

    private void finishMetricsOnError() {
        lastMetrics = metricsCollector.finish();
        log.warn("Migration failed - metrics: {}", lastMetrics.summary());
    }

    private void safeAfterCriticalPhase(MigrationContext ctx, long migrationId) {
        try {
            phaseListener.onAfterCriticalPhase(ctx);
        } catch (Exception e) {
            log.warn("onAfterCriticalPhase threw during finally (migration id={})", migrationId, e);
        }
    }

    /* ---------------- private helper methods ---------------- */

    /**
     * Extracts interface types from the migration plan.
     *
     * <p>This method collects all "from" types being migrated, as well as any common
     * interfaces they implement. These are used to update generic fields that hold
     * elements of these types.
     *
     * @return set of interface types to look for in generic fields
     */
    private Set<Class<?>> extractMigratedInterfaceTypes() {
        Set<Class<?>> interfaceTypes = new LinkedHashSet<>();

        for (MigratorDescriptor desc : plan.orderedMigrators()) {
            Class<?> fromClass = desc.from();
            Class<?> toClass = desc.to();

            if (fromClass != null) {
                // Add the from class itself
                interfaceTypes.add(fromClass);

                // Add all interfaces implemented by the from class
                for (Class<?> iface : fromClass.getInterfaces()) {
                    interfaceTypes.add(iface);
                }

                // Add superclass if it's not Object
                Class<?> superclass = fromClass.getSuperclass();
                if (superclass != null && superclass != Object.class) {
                    interfaceTypes.add(superclass);
                }
            }

            if (toClass != null) {
                // Add interfaces from the to class as well (they might share common interfaces)
                for (Class<?> iface : toClass.getInterfaces()) {
                    interfaceTypes.add(iface);
                }
            }
        }

        log.debug("Extracted {} migrated interface types from migration plan", interfaceTypes.size());
        return interfaceTypes;
    }

    private void firstPassAllocateAndMigrate(
        Set<Object> allResolvedOldObjects,
        Map<MigratorDescriptor, List<Object>> createdPerMigrator
    ) throws MigrateException {
        for (MigratorDescriptor desc : plan.orderedMigrators()) {
            processMigrator(desc, allResolvedOldObjects, createdPerMigrator);
        }
    }

    private void signalBeforeCriticalPhase(MigrationContext ctx) throws MigrateException {
        try {
            TimeoutExecutor.executeWithTimeoutChecked(
                    "onBeforeCriticalPhase",
                    timeoutConfig.criticalPhaseTimeout(),
                    () -> phaseListener.onBeforeCriticalPhase(ctx)
            );
        } catch (MigrateException me) {
            throw me;
        } catch (Exception e) {
            throw new MigrateException("Application refused to enter critical phase: " + e.getMessage(), e);
        }
    }


    private void processMigrator(
            MigratorDescriptor desc,
            Set<Object> allResolvedOldObjects,
            Map<MigratorDescriptor, List<Object>> createdPerMigrator
    ) throws MigrateException {
        Class<?> from = desc.from();
        Object migrator = desc.migrator();

        HeapSnapshot snapshot = TimeoutExecutor.executeWithTimeout(
                "heapSnapshot(" + from.getSimpleName() + ")",
                timeoutConfig.heapSnapshotTimeout(),
                () -> heapWalker.snapshot(from)
        );
        if (snapshot == null || snapshot.entries().isEmpty()) return;

        List<Object> createdForThisMigrator = new ArrayList<>();
        createdPerMigrator.put(desc, createdForThisMigrator);

        for (HeapSnapshot.Entry entry : snapshot.entries()) {
            processHeapEntry(entry, migrator, allResolvedOldObjects, createdForThisMigrator);
        }
    }

    private void processHeapEntry(
            HeapSnapshot.Entry entry,
            Object migrator,
            Set<Object> allResolvedOldObjects,
            List<Object> createdForThisMigrator
    ) throws MigrateException {
        Object oldObj = heapWalker.resolve(entry.tag());
        if (oldObj == null || forwarding.contains(oldObj)) {
            if (oldObj != null) allResolvedOldObjects.add(oldObj);
            return;
        }

        Object newObj = invokeMigrate(migrator, oldObj);
        if (newObj == null) {
            throw new MigrateException("Migrator returned null for " + oldObj.getClass().getName());
        }

        forwarding.put(oldObj, newObj);
        allResolvedOldObjects.add(oldObj);
        createdForThisMigrator.add(newObj);
    }

    private int secondPassPatchReferencesWithCount(Set<Object> pass2Objects, Collection<Class<?>> classesToScan) {
        Set<Object> objectsToPatch = null;
        int patchedCount = 0;

        try {
            if (fullHeapWalk) {
                // Full heap walk - patch all objects on the heap
                log.debug("Using full heap walk");
                objectsToPatch = TimeoutExecutor.executeWithTimeoutChecked(
                        "heapWalkFull",
                        timeoutConfig.heapWalkTimeout(),
                        () -> heapWalker.walkHeap()
                );
            } else {
                // Filtered heap walk - only walk objects of specified classes
                log.debug("Using filtered heap walk for {} classes", classesToScan.size());
                Set<Class<?>> classesToWalk = collectClassesToPatch(classesToScan, pass2Objects);
                objectsToPatch = TimeoutExecutor.executeWithTimeoutChecked(
                        "heapWalkFiltered",
                        timeoutConfig.heapWalkTimeout(),
                        () -> heapWalker.walkHeap(classesToWalk)
                );
            }

            if (objectsToPatch != null && !objectsToPatch.isEmpty()) {
                for (Object obj : objectsToPatch) {
                    referencePatcher.patchObject(obj);
                    patchedCount++;
                }
                return patchedCount;
            }
        } catch (Exception e) {
            // if heapWalker fails, fallback to patch pass2Objects
            log.debug("heapWalker failed: {}, falling back to pass2Objects", e.toString());
        }

        // Fallback: patch only pass2Objects
        for (Object obj : pass2Objects) {
            referencePatcher.patchObject(obj);
            patchedCount++;
        }
        return patchedCount;
    }

    private void safeAutoPatchStaticFields(Collection<Class<?>> classesToScan, Collection<Object> pass2Objects) {
        try {
            autoPatchStaticFields(classesToScan, pass2Objects);
        } catch (Exception e) {
            log.warn("autoPatchStaticFields failed: {}", e.toString());
        }
    }


    private void autoPatchStaticFields(Collection<Class<?>> classesToScan, Collection<Object> pass2Objects) {
        Set<Class<?>> classesToPatch = collectClassesToPatch(classesToScan, pass2Objects);

        classesToPatch.forEach(this::safePatchStaticFields);
    }

    private void signalAfterCriticalPhase(MigrationContext ctx) throws MigrateException {
        try {
            TimeoutExecutor.executeWithTimeoutChecked(
                    "onAfterCriticalPhase",
                    timeoutConfig.criticalPhaseTimeout(),
                    () -> phaseListener.onAfterCriticalPhase(ctx)
            );
        } catch (MigrateException me) {
            try {
                rollbackManager.rollback();
            } catch (Exception rbEx) {
                throw new MigrateException("onAfterCriticalPhase failed: " + me.getMessage()
                        + " and rollback failed: " + rbEx.getMessage(), rbEx);
            }
            throw me;
        } catch (Exception e) {
            try {
                rollbackManager.rollback();
            } catch (Exception rbEx) {
                throw new MigrateException("onAfterCriticalPhase failed: " + e.getMessage()
                        + " and rollback failed: " + rbEx.getMessage(), rbEx);
            }
            throw new MigrateException("PhaseListener.onAfterCriticalPhase failed: " + e.getMessage(), e);
        }
    }

    private SmokeTestReport runSmokeTestsWithTimeout(Map<MigratorDescriptor, List<Object>> createdPerMigrator) {
        return TimeoutExecutor.executeWithTimeout(
                "smokeTests",
                timeoutConfig.smokeTestTimeout(),
                () -> smokeRunner.runAll(createdPerMigrator)
        );
    }

    private void commitWithRollback() throws MigrateException {
        try {
            commitManager.commit();
        } catch (Exception commitEx) {
            try {
                rollbackManager.rollback();
            } catch (Exception rbEx) {
                throw new MigrateException("Commit failed: " + commitEx.getMessage()
                        + ", rollback also failed: " + rbEx.getMessage(), rbEx);
            }
            throw new MigrateException("Commit failed: " + commitEx.getMessage(), commitEx);
        }
    }

    private Set<Class<?>> collectClassesToPatch(Collection<Class<?>> classesToScan, Collection<Object> pass2Objects) {
        Set<Class<?>> classesToPatch = new LinkedHashSet<>();

        if (classesToScan != null) {
            for (Class<?> cls : classesToScan) {
                classesToPatch.addAll(getClassHierarchy(cls));
                // Include nested/inner classes
                classesToPatch.addAll(getNestedClasses(cls));
            }
        }

        if (pass2Objects != null) {
            for (Object o : pass2Objects) {
                if (o == null) continue;
                Class<?> cls = o.getClass();
                classesToPatch.addAll(getClassHierarchy(cls));
                classesToPatch.addAll(getNestedClasses(cls));
            }
        }

        return classesToPatch;
    }

    private Set<Class<?>> getNestedClasses(Class<?> cls) {
        Set<Class<?>> nested = new LinkedHashSet<>();
        try {
            for (Class<?> declared : cls.getDeclaredClasses()) {
                nested.add(declared);
                // Recursively get nested classes of nested classes
                nested.addAll(getNestedClasses(declared));
            }
        } catch (Exception e) {
            // best-effort
        }
        return nested;
    }

    private List<Class<?>> getClassHierarchy(Class<?> cls) {
        List<Class<?>> hierarchy = new ArrayList<>();
        while (cls != null && cls != Object.class) {
            hierarchy.add(cls);
            cls = cls.getSuperclass();
        }
        return hierarchy;
    }

    private void safePatchStaticFields(Class<?> cls) {
        try {
            referencePatcher.patchStaticFields(cls);
        } catch (Exception e) {
            log.warn("Failed to patch static fields for class {} : {}", cls != null ? cls.getName() : "null", e.toString());
        }
    }


    private void cleanupForwarding(Set<Object> allResolvedOldObjects) {
        int removed = 0;
        for (Object oldObj : allResolvedOldObjects) {
            try {
                forwarding.remove(oldObj);
                removed++;
            } catch (Exception ignore) {
                // best-effort
            }
        }
        if (removed > 0) {
            log.warn("Cleanup removed {} forwarding entries after unexpected error", removed);
        }
    }

    private Object invokeMigrate(Object migrator, Object oldObj) throws MigrateException {
        try {
            Method m = migrateMethodCache.get(migrator.getClass());
            if (m == null) {
                m = findMigrateMethod(migrator.getClass());
                migrateMethodCache.put(migrator.getClass(), m);
            }
            m.setAccessible(true);
            return m.invoke(migrator, oldObj);
        } catch (MigrateException me) {
            throw me;
        } catch (Exception ex) {
            throw new MigrateException("Failed to invoke migrate on " + migrator.getClass().getName(), ex);
        }
    }

    private Method findMigrateMethod(Class<?> cls) throws MigrateException {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals("migrate") && m.getParameterCount() == 1) {
                return m;
            }
        }

        for (Method m : cls.getDeclaredMethods()) {
            if (m.getName().equals("migrate") && m.getParameterCount() == 1) {
                m.setAccessible(true);
                return m;
            }
        }

        throw new MigrateException("Cannot find migrate(OldT) method on migrator " + cls.getName());
    }


    /**
     * Delegates to NativeHeapWalker.advanceEpoch() if available.
     * @throws MigrateException 
     */
    private void migratorAdvanceEpoch() throws MigrateException {
        try {
            Class<?> nhw = Class.forName("migrator.heap.NativeHeapWalker");
            Method adv = nhw.getMethod("advanceEpoch");
            adv.setAccessible(true);
            adv.invoke(null);
        } catch (ClassNotFoundException cnf) {
            // Native not present — OK
        } catch (Exception e) {
            throw new MigrateException("Failed to advance native epoch: " + e.getMessage(), e);
        }
    }

    private void cleanupAndRollback(Set<Object> allResolvedOldObjects, Exception original) throws MigrateException {
        long migrationId = MigrationState.getInstance().getCurrentMigrationId();
        MigrationAlertLogger.rollbackTriggered(migrationId, original.getMessage());
        try {
            cleanupForwarding(allResolvedOldObjects);
            rollbackManager.rollback();
            MigrationAlertLogger.rollbackCompleted(migrationId, true);
        } catch (Exception rbEx) {
            MigrationAlertLogger.rollbackCompleted(migrationId, false);
            throw new MigrateException("Migration failed: " + original.getMessage()
                    + " ; attempted rollback failed: " + rbEx.getMessage(), original);
        }
    }
}
