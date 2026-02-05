package migrator.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration for migration operation timeouts.
 *
 * <p>This class allows users to configure timeouts for various migration phases:
 * <ul>
 *   <li>Heap walk operations (full and filtered)</li>
 *   <li>Heap snapshot creation</li>
 *   <li>Critical phase callbacks (before/after)</li>
 *   <li>Smoke test execution</li>
 * </ul>
 *
 * <p>All timeouts default to {@link #NO_TIMEOUT} (disabled). Use the builder
 * or setter methods to configure specific timeouts.
 *
 * <h2>Example:</h2>
 * <pre>
 * MigrationTimeoutConfig config = MigrationTimeoutConfig.builder()
 *     .heapWalkTimeout(Duration.ofSeconds(60))
 *     .criticalPhaseTimeout(Duration.ofSeconds(30))
 *     .smokeTestTimeout(Duration.ofSeconds(10))
 *     .build();
 *
 * engine.setTimeoutConfig(config);
 * </pre>
 *
 * @see MigrationEngine#setTimeoutConfig(MigrationTimeoutConfig)
 */
public final class MigrationTimeoutConfig {

    /**
     * Special value indicating no timeout (operation can run indefinitely).
     */
    public static final Duration NO_TIMEOUT = Duration.ZERO;

    /**
     * Default configuration with all timeouts disabled.
     */
    public static final MigrationTimeoutConfig DEFAULTS = new MigrationTimeoutConfig(
            NO_TIMEOUT, NO_TIMEOUT, NO_TIMEOUT, NO_TIMEOUT
    );

    private final Duration heapWalkTimeout;
    private final Duration heapSnapshotTimeout;
    private final Duration criticalPhaseTimeout;
    private final Duration smokeTestTimeout;

    private MigrationTimeoutConfig(
            Duration heapWalkTimeout,
            Duration heapSnapshotTimeout,
            Duration criticalPhaseTimeout,
            Duration smokeTestTimeout
    ) {
        this.heapWalkTimeout = Objects.requireNonNull(heapWalkTimeout, "heapWalkTimeout");
        this.heapSnapshotTimeout = Objects.requireNonNull(heapSnapshotTimeout, "heapSnapshotTimeout");
        this.criticalPhaseTimeout = Objects.requireNonNull(criticalPhaseTimeout, "criticalPhaseTimeout");
        this.smokeTestTimeout = Objects.requireNonNull(smokeTestTimeout, "smokeTestTimeout");
    }

    /**
     * Creates a new builder for timeout configuration.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Timeout for heap walk operations (full and filtered).
     *
     * <p>This timeout applies to:
     * <ul>
     *   <li>{@code HeapWalker.walkHeap()}</li>
     *   <li>{@code HeapWalker.walkHeap(Collection)}</li>
     * </ul>
     *
     * @return the heap walk timeout, or {@link #NO_TIMEOUT} if disabled
     */
    public Duration heapWalkTimeout() {
        return heapWalkTimeout;
    }

    /**
     * Timeout for heap snapshot creation.
     *
     * <p>This timeout applies to {@code HeapWalker.snapshot(Class)}.
     *
     * @return the heap snapshot timeout, or {@link #NO_TIMEOUT} if disabled
     */
    public Duration heapSnapshotTimeout() {
        return heapSnapshotTimeout;
    }

    /**
     * Timeout for critical phase callbacks.
     *
     * <p>This timeout applies to:
     * <ul>
     *   <li>{@code MigrationPhaseListener.onBeforeCriticalPhase()}</li>
     *   <li>{@code MigrationPhaseListener.onAfterCriticalPhase()}</li>
     * </ul>
     *
     * @return the critical phase timeout, or {@link #NO_TIMEOUT} if disabled
     */
    public Duration criticalPhaseTimeout() {
        return criticalPhaseTimeout;
    }

    /**
     * Timeout for smoke test execution.
     *
     * <p>This timeout applies to {@code SmokeTestRunner.runAll()}.
     *
     * @return the smoke test timeout, or {@link #NO_TIMEOUT} if disabled
     */
    public Duration smokeTestTimeout() {
        return smokeTestTimeout;
    }

    /**
     * Checks if a timeout is enabled (not {@link #NO_TIMEOUT}).
     *
     * @param timeout the timeout to check
     * @return true if timeout is enabled
     */
    public static boolean isEnabled(Duration timeout) {
        return timeout != null && !timeout.isZero() && !timeout.isNegative();
    }

    @Override
    public String toString() {
        return "MigrationTimeoutConfig{" +
                "heapWalk=" + formatTimeout(heapWalkTimeout) +
                ", heapSnapshot=" + formatTimeout(heapSnapshotTimeout) +
                ", criticalPhase=" + formatTimeout(criticalPhaseTimeout) +
                ", smokeTest=" + formatTimeout(smokeTestTimeout) +
                '}';
    }

    private static String formatTimeout(Duration d) {
        return isEnabled(d) ? d.toMillis() + "ms" : "disabled";
    }

    /**
     * Builder for creating {@link MigrationTimeoutConfig} instances.
     */
    public static final class Builder {
        private Duration heapWalkTimeout = NO_TIMEOUT;
        private Duration heapSnapshotTimeout = NO_TIMEOUT;
        private Duration criticalPhaseTimeout = NO_TIMEOUT;
        private Duration smokeTestTimeout = NO_TIMEOUT;

        private Builder() {}

        /**
         * Sets the timeout for heap walk operations.
         *
         * @param timeout the timeout duration, or {@link #NO_TIMEOUT} to disable
         * @return this builder
         */
        public Builder heapWalkTimeout(Duration timeout) {
            this.heapWalkTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the timeout for heap walk operations in seconds.
         *
         * @param seconds the timeout in seconds, or 0 to disable
         * @return this builder
         */
        public Builder heapWalkTimeoutSeconds(long seconds) {
            return heapWalkTimeout(seconds <= 0 ? NO_TIMEOUT : Duration.ofSeconds(seconds));
        }

        /**
         * Sets the timeout for heap snapshot creation.
         *
         * @param timeout the timeout duration, or {@link #NO_TIMEOUT} to disable
         * @return this builder
         */
        public Builder heapSnapshotTimeout(Duration timeout) {
            this.heapSnapshotTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the timeout for heap snapshot creation in seconds.
         *
         * @param seconds the timeout in seconds, or 0 to disable
         * @return this builder
         */
        public Builder heapSnapshotTimeoutSeconds(long seconds) {
            return heapSnapshotTimeout(seconds <= 0 ? NO_TIMEOUT : Duration.ofSeconds(seconds));
        }

        /**
         * Sets the timeout for critical phase callbacks.
         *
         * @param timeout the timeout duration, or {@link #NO_TIMEOUT} to disable
         * @return this builder
         */
        public Builder criticalPhaseTimeout(Duration timeout) {
            this.criticalPhaseTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the timeout for critical phase callbacks in seconds.
         *
         * @param seconds the timeout in seconds, or 0 to disable
         * @return this builder
         */
        public Builder criticalPhaseTimeoutSeconds(long seconds) {
            return criticalPhaseTimeout(seconds <= 0 ? NO_TIMEOUT : Duration.ofSeconds(seconds));
        }

        /**
         * Sets the timeout for smoke test execution.
         *
         * @param timeout the timeout duration, or {@link #NO_TIMEOUT} to disable
         * @return this builder
         */
        public Builder smokeTestTimeout(Duration timeout) {
            this.smokeTestTimeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /**
         * Sets the timeout for smoke test execution in seconds.
         *
         * @param seconds the timeout in seconds, or 0 to disable
         * @return this builder
         */
        public Builder smokeTestTimeoutSeconds(long seconds) {
            return smokeTestTimeout(seconds <= 0 ? NO_TIMEOUT : Duration.ofSeconds(seconds));
        }

        /**
         * Sets all timeouts to the same value.
         *
         * @param timeout the timeout duration for all operations
         * @return this builder
         */
        public Builder allTimeouts(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            this.heapWalkTimeout = timeout;
            this.heapSnapshotTimeout = timeout;
            this.criticalPhaseTimeout = timeout;
            this.smokeTestTimeout = timeout;
            return this;
        }

        /**
         * Sets all timeouts to the same value in seconds.
         *
         * @param seconds the timeout in seconds for all operations
         * @return this builder
         */
        public Builder allTimeoutsSeconds(long seconds) {
            return allTimeouts(seconds <= 0 ? NO_TIMEOUT : Duration.ofSeconds(seconds));
        }

        /**
         * Builds the timeout configuration.
         *
         * @return the configured {@link MigrationTimeoutConfig}
         */
        public MigrationTimeoutConfig build() {
            return new MigrationTimeoutConfig(
                    heapWalkTimeout,
                    heapSnapshotTimeout,
                    criticalPhaseTimeout,
                    smokeTestTimeout
            );
        }
    }
}
