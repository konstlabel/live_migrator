package migrator.smoke;

import java.util.Collections;
import java.util.List;

/**
 * Immutable report containing the results of all smoke tests and health checks.
 *
 * <p>A report is considered successful only if all contained {@link SmokeTestResult}s
 * indicate success. Use {@link #success()} to check overall status and
 * {@link #results()} to inspect individual test outcomes.
 *
 * @see SmokeTestRunner
 * @see SmokeTestResult
 */
public final class SmokeTestReport {
    private final boolean success;
    private final List<SmokeTestResult> results;

    /**
     * Creates a new smoke test report.
     *
     * @param success true if all tests passed
     * @param results the individual test results
     */
    public SmokeTestReport(boolean success, List<SmokeTestResult> results) {
        this.success = success;
        this.results = List.copyOf(results);
    }

    /**
     * Returns true if all tests passed.
     *
     * @return true if all tests passed, false if any test failed
     */
    public boolean success() { return success; }

    /**
     * Returns the list of individual test results.
     *
     * @return an immutable list of test results
     */
    public List<SmokeTestResult> results() { return Collections.unmodifiableList(results); }
}
