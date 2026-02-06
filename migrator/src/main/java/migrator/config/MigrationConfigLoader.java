package migrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Loads migration configuration from properties or YAML files.
 *
 * <p>Configuration is searched in the following order:
 * <ol>
 *   <li>{@code migration.properties} on the classpath</li>
 *   <li>{@code migration.yml} on the classpath</li>
 * </ol>
 *
 * <p>System properties can override file-based configuration. Use the
 * {@code migration.} prefix for property names (e.g., {@code -Dmigration.heap.walk.mode=SPEC}).
 *
 * <h2>Configuration Properties:</h2>
 * <ul>
 *   <li>{@code migration.heap.walk.mode} - FULL or SPEC</li>
 *   <li>{@code migration.timeout.heap.walk} - timeout in seconds</li>
 *   <li>{@code migration.timeout.heap.snapshot} - timeout in seconds</li>
 *   <li>{@code migration.timeout.critical.phase} - timeout in seconds</li>
 *   <li>{@code migration.timeout.smoke.test} - timeout in seconds</li>
 *   <li>{@code migration.heap.size.min} - minimum heap in MB</li>
 *   <li>{@code migration.heap.size.max} - maximum heap in MB</li>
 *   <li>{@code migration.history.size} - number of history entries</li>
 *   <li>{@code migration.alert.level} - DEBUG, WARNING, or ERROR</li>
 * </ul>
 *
 * @see MigrationConfig
 */
public final class MigrationConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(MigrationConfigLoader.class);

    private MigrationConfigLoader() {}

    /**
     * Load from classpath (migration.properties or migration.yml).
     * @throws MigrationConfigException if no config file found
     */
    public static MigrationConfig load() {
        InputStream is = getResource("migration.properties");
        if (is != null) {
            return loadProperties(is, "migration.properties");
        }

        is = getResource("migration.yml");
        if (is != null) {
            return loadYaml(is, "migration.yml");
        }

        throw new MigrationConfigException(
                "Config file required: migration.properties or migration.yml");
    }

    /**
     * Loads configuration from an external file.
     *
     * @param path path to the configuration file (.properties or .yml/.yaml)
     * @return the loaded configuration
     * @throws IOException if the file cannot be read
     * @throws MigrationConfigException if the configuration is invalid
     */
    public static MigrationConfig loadFromFile(Path path) throws IOException {
        String name = path.getFileName().toString();
        try (InputStream is = Files.newInputStream(path)) {
            if (name.endsWith(".yml") || name.endsWith(".yaml")) {
                return loadYaml(is, name);
            }
            return loadProperties(is, name);
        }
    }

    /**
     * Loads configuration from an external file path.
     *
     * @param path path to the configuration file
     * @return the loaded configuration
     * @throws IOException if the file cannot be read
     */
    public static MigrationConfig loadFromFile(String path) throws IOException {
        return loadFromFile(Path.of(path));
    }

    private static InputStream getResource(String name) {
        return MigrationConfigLoader.class.getClassLoader().getResourceAsStream(name);
    }

    private static MigrationConfig loadProperties(InputStream is, String source) {
        try {
            Properties props = new Properties();
            props.load(is);
            log.info("Loaded config from {}", source);
            return parse(props);
        } catch (IOException e) {
            throw new MigrationConfigException("Failed to load " + source, e);
        }
    }

    private static MigrationConfig loadYaml(InputStream is, String source) {
        Map<String, Object> root = new Yaml().load(is);
        if (root == null) {
            return MigrationConfig.DEFAULTS;
        }
        Properties props = new Properties();
        flatten("", root, props);
        log.info("Loaded config from {}", source);
        return parse(props);
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> map, Properties props) {
        for (var entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object val = entry.getValue();
            if (val instanceof Map) {
                flatten(key, (Map<String, Object>) val, props);
            } else if (val != null) {
                props.setProperty(key, val.toString());
            }
        }
    }

    private static MigrationConfig parse(Properties props) {
        MigrationConfig.Builder b = MigrationConfig.builder();

        getString(props, "migration.heap.walk.mode").ifPresent(v -> {
            try {
                b.heapWalkMode(HeapWalkMode.valueOf(v.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid heap.walk.mode: {}", v);
            }
        });

        getLong(props, "migration.timeout.heap.walk").ifPresent(b::heapWalkTimeoutSeconds);
        getLong(props, "migration.timeout.heap.snapshot").ifPresent(b::heapSnapshotTimeoutSeconds);
        getLong(props, "migration.timeout.critical.phase").ifPresent(b::criticalPhaseTimeoutSeconds);
        getLong(props, "migration.timeout.smoke.test").ifPresent(b::smokeTestTimeoutSeconds);
        getLong(props, "migration.heap.size.min").ifPresent(b::minHeapSizeMb);
        getLong(props, "migration.heap.size.max").ifPresent(b::maxHeapSizeMb);

        getInt(props, "migration.history.size").ifPresent(v -> {
            if (v > 0) b.historySize(v);
        });

        getString(props, "migration.alert.level").ifPresent(v -> {
            try {
                b.alertLevel(AlertLevel.valueOf(v.toUpperCase()));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid alert.level: {}", v);
            }
        });

        return b.build();
    }

    private static java.util.Optional<String> getString(Properties props, String key) {
        String val = System.getProperty(key);
        if (val == null) val = props.getProperty(key);
        return val != null ? java.util.Optional.of(val.trim()) : java.util.Optional.empty();
    }

    private static java.util.Optional<Long> getLong(Properties props, String key) {
        return getString(props, key).flatMap(v -> {
            try {
                return java.util.Optional.of(Long.parseLong(v));
            } catch (NumberFormatException e) {
                log.warn("Invalid number for {}: {}", key, v);
                return java.util.Optional.empty();
            }
        });
    }

    private static java.util.Optional<Integer> getInt(Properties props, String key) {
        return getString(props, key).flatMap(v -> {
            try {
                return java.util.Optional.of(Integer.parseInt(v));
            } catch (NumberFormatException e) {
                log.warn("Invalid number for {}: {}", key, v);
                return java.util.Optional.empty();
            }
        });
    }
}
