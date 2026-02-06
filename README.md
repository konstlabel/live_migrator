# Live Migrator

A framework for performing live object migrations in running JVM applications without downtime.

## Overview

Live Migrator enables you to migrate object instances from one class version to another in a running JVM, replacing all references seamlessly. This is useful for:

- **Zero-downtime deployments** - Migrate data structures without restarting the application
- **Schema evolution** - Update object models while the application continues to serve requests
- **Hot fixes** - Replace buggy class implementations with fixed versions at runtime

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Running JVM                                 │
│  ┌──────────────┐     ┌────────────────────────────────────────┐    │
│  │ service-demo │     │            migration-payload           │    │
│  │              │     │  ┌───────────────┐  ┌───────────────┐  │    │
│  │  OldUser ────┼─────┼──► UserMigrator  │  │   NewUser     │  │    │
│  │  instances   │     │  │  @Migrator    │  │   instances   │  │    │
│  │              │     │  └───────────────┘  └───────────────┘  │    │
│  └──────────────┘     └────────────────────────────────────────┘    │
│         ▲                         │                                 │
│         │                         ▼                                 │
│  ┌──────┴──────────────────────────────────────────────────────┐    │
│  │                    MigrationEngine (migrator)               │    │
│  │  1. Heap Walk (JVMTI)  →  Find all OldUser instances        │    │
│  │  2. Migrate            →  Create NewUser for each OldUser   │    │
│  │  3. Patch References   →  Replace all OldUser → NewUser     │    │
│  │  4. Update Registries  →  Update caches, maps, collections  │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
         ▲
         │ Java Attach API
         │
┌────────┴─────────┐
│ run-migration.sh │
│ (VirtualMachine  │
│  AgentLoader)    │
└──────────────────┘
```

## Project Structure

```
Migrator/
├── migrator/                 # Core migrator framework
│   └── src/main/java/migrator/
│       ├── ClassMigrator.java        # Interface for migrator implementations
│       ├── engine/
│       │   ├── MigrationEngine.java        # Main orchestrator
│       │   ├── ComponentResolver.java      # Resolves annotated components
│       │   ├── MigrationTimeoutConfig.java # Timeout configuration
│       │   └── TimeoutExecutor.java        # Timeout execution utility
│       ├── annotations/              # @Migrator, @CommitComponent, etc.
│       ├── load/                     # Agent loading (VirtualMachineAgentLoader)
│       ├── registry/                 # Registry update utilities
│       ├── heap/                     # Heap walking (JVMTI integration)
│       ├── patch/                    # Reference patching
│       ├── phase/                    # Migration phase listeners
│       ├── commit/                   # Commit/rollback management
│       ├── smoke/                    # Smoke testing after migration
│       ├── scanner/                  # Annotation scanning
│       ├── config/                   # Configuration (MigrationConfig, loader)
│       ├── metrics/                  # Metrics collection (MigrationMetrics)
│       ├── state/                    # Migration state tracking
│       ├── alert/                    # Structured logging (MigrationAlertLogger)
│       ├── crac/                     # CRaC checkpoint/restore
│       └── exceptions/               # Exception types
│
├── examples/                 # Example usage (not part of the library)
│   ├── service-demo/         # Example service to migrate
│   │   └── src/main/java/service/
│   │       ├── ServiceMain.java          # Service entry point
│   │       ├── UserFactory.java          # Abstract Factory for User
│   │       ├── JsonWriter.java           # JSON serialization utility
│   │       └── model/
│   │           ├── User.java             # Common interface
│   │           └── OldUser.java          # Original implementation
│   └── migration-payload/    # Example migration agent
│       └── src/main/java/migration/
│           ├── MigrationAgent.java       # Java agent entry point
│           ├── MigrationPhaseListenerImpl.java  # Phase listener implementation
│           ├── NewUserFactory.java       # Factory for NewUser
│           ├── NoopCommitManager.java    # Commit manager implementation
│           ├── NoopRollbackManager.java  # Rollback manager implementation
│           ├── NoopSmokeTest.java        # Smoke test implementation
│           ├── migrator/
│           │   └── UserMigrator.java     # OldUser → NewUser migrator
│           └── model/
│               └── NewUser.java          # New class version
│
├── agent/                    # Native JVMTI agent for heap walking
│   └── agent.c
│
└── pom.xml                   # Parent POM (mvn install builds the library)
```

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.8+
- GCC (for building native agent)
- Docker & Docker Compose (for Docker demo)

### Running the Demo (Local)

1. **Start the demo service:**
   ```bash
   ./examples/run-service.sh
   ```
   This builds and starts a service with `OldUser` instances.

2. **In another terminal, trigger the migration:**
   ```bash
   ./examples/run-migration.sh
   ```
   This attaches to the running JVM and migrates all `OldUser` → `NewUser`.

3. **Observe the migration:**
   - Service continues running without interruption
   - All `OldUser` instances are replaced with `NewUser`
   - New users created after migration are `NewUser` instances

### Running the Demo (Docker)

1. **Build and start the service:**
   ```bash
   docker compose -f examples/docker-compose.yml build
   docker compose -f examples/docker-compose.yml up -d service
   ```

2. **Wait for the service to become healthy:**
   ```bash
   docker compose -f examples/docker-compose.yml ps
   ```

3. **Run the migration:**
   ```bash
   docker compose -f examples/docker-compose.yml --profile migrate up migrator
   ```
   Or use the helper script:
   ```bash
   ./examples/docker-migrate.sh
   ```

4. **Check service logs to see migration results:**
   ```bash
   docker compose -f examples/docker-compose.yml logs service
   ```

5. **Clean up:**
   ```bash
   docker compose -f examples/docker-compose.yml --profile migrate down -v
   ```

## Core Concepts

### ClassMigrator

The `ClassMigrator` interface defines how to convert objects from one class to another:

```java
public interface ClassMigrator<OldT, NewT> {
    NewT migrate(OldT old) throws MigrateException;
    default void validate(NewT migrated) throws MigrateException {}
}
```

### @Migrator Annotation

Mark your migrator class with `@Migrator` for automatic discovery:

```java
@Migrator
public class UserMigrator implements ClassMigrator<OldUser, NewUser> {
    @Override
    public NewUser migrate(OldUser old) throws MigrateException {
        return new NewUser(old.id, old.name);
    }
}
```

### MigrationEngine

The `MigrationEngine` orchestrates the entire migration process:

```java
// Simple usage - scans for @Migrator annotated classes
MigrationEngine.createAndMigrate(Set.of(ServiceMain.class));

// With custom classloader and JAR path
MigrationEngine.createAndMigrate(
    classesToScan,
    fullHeapWalk,
    classLoader,
    jarPath
);

// With generic container updates
MigrationEngine.createAndMigrate(
    Set.of(ServiceMain.class),
    List.of(userCache, userRegistry),   // Containers to update
    User.class                          // Common interface type
);
```

### Attaching to Running JVM

Use `VirtualMachineAgentLoader` to attach to a running JVM:

```java
// Attach and load migration agent
LoaderResult result = MigrationEngine.attachAndLoad(pid, agentJarPath);

// Or use the loader directly
AgentLoader loader = new VirtualMachineAgentLoader();
LoaderResult result = loader.load(pid, agentJarPath);
```

## Annotations

### @Migrator

Marks a class as the primary migrator. Must implement `ClassMigrator<OldT, NewT>`.

```java
@Migrator
public class UserMigrator implements ClassMigrator<OldUser, NewUser> { ... }
```

### @CommitComponent

Marks a class that handles commit logic after successful migration.

```java
@CommitComponent
public class MyCommitManager extends CommitManager {
    public MyCommitManager() {
        super(new MyCracController());
    }
}
```

### @RollbackComponent

Marks a class that handles rollback if migration fails.

```java
@RollbackComponent
public class MyRollbackManager extends RollbackManager {
    public MyRollbackManager() {
        super(new MyCracController());
    }
}
```

### @PhaseListener

Marks a class that receives migration phase callbacks.

```java
@PhaseListener
public class MyPhaseListener implements MigrationPhaseListener {
    public void onBeforeCriticalPhase(MigrationContext ctx) { /* pause app */ }
    public void onAfterCriticalPhase(MigrationContext ctx) { /* resume app */ }
}
```

**MigrationContext provides:**
| Method | Description |
|--------|-------------|
| `migrationId()` | Current migration identifier |
| `plan()` | The migration plan being executed |
| `startedAtNanos()` | Timestamp when migration started (in nanos) |

### @SmokeTestComponent

Marks a class that performs smoke tests after migration. Multiple smoke test components can be annotated.

```java
@SmokeTestComponent
public class MySmokeTest implements SmokeTest {
    public SmokeTestResult run(Map<MigratorDescriptor, List<Object>> migrated) {
        // Verify migrated objects
        return SmokeTestResult.ok("my-smoke-test");
    }
}
```

**SmokeTestResult methods:**
| Method | Description |
|--------|-------------|
| `ok(name)` | Create a successful result with test name |
| `fail(name, message, error)` | Create a failed result with name, message, and optional exception |
| `isOk()` | Check if the test passed |
| `name()` | Get the test name |
| `message()` | Get failure message (if failed) |
| `error()` | Get cause exception (if any) |

### @UpdateRegistry

Marks fields (typically static) as registries/caches that should be updated.

**Attributes:**
| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `replaceKeys` | boolean | true | Replace keys in Map-type registries |
| `replaceValues` | boolean | true | Replace values in Map-type registries |
| `deep` | boolean | true | Recursively update nested containers |
| `reflective` | boolean | false | Use reflection for custom container types |

```java
public class UserCache {
    @UpdateRegistry(replaceKeys = true, replaceValues = true)
    private static Map<Integer, User> cache = new ConcurrentHashMap<>();

    @UpdateRegistry
    private List<User> recentUsers = new ArrayList<>();

    @UpdateRegistry(deep = true, reflective = true)
    private CustomRegistry<User> customRegistry;
}
```

## Migration Phases

The migration process consists of the following phases (tracked by `MigrationMetrics.Phase`):

1. **FIRST_PASS (Allocate & Migrate)**
   - Walk heap to find all instances of source class
   - Create new instances using the migrator
   - Build forwarding table (old → new mapping)

2. **CRITICAL_PHASE**
   - `onBeforeCriticalPhase()` - Application should quiesce
   - **SECOND_PASS** (sub-phase) - Patch all references in the heap
   - **REGISTRY_UPDATE** (sub-phase) - Update static fields and registries
   - Update generic containers (List<T>, Map<K,V>, etc.)
   - `onAfterCriticalPhase()` - Application may resume

3. **SMOKE_TEST (Validation)**
   - Run smoke tests on migrated objects
   - If tests fail, trigger rollback

4. **Commit**
   - Delete checkpoints
   - Advance native epoch

## Generic Container Updates

The framework automatically updates generic containers holding migrated objects:

```java
class UserService {
    private List<User> users;           // Auto-updated
    private Map<String, User> cache;    // Auto-updated
    private Set<User> activeUsers;      // Auto-updated
    private User[] userArray;           // Auto-updated
    private CustomRegistry<User> reg;   // Auto-updated (via reflection)
}
```

To explicitly update containers:

```java
// Update specific containers
MigrationEngine.createAndMigrate(
    classesToScan,
    List.of(container1, container2),
    User.class
);

// Or via RegistryUpdater directly
registryUpdater.updateGenericContainer(myList, User.class);
registryUpdater.updateGenericContainers(containers, User.class);
```

## Creating a Migration Payload

1. **Create the new class version:**
   ```java
   public class NewUser implements User {
       public final int id;
       public final String name;
       public final String email;  // New field

       public NewUser(int id, String name, String email) {
           this.id = id;
           this.name = name;
           this.email = email;
       }
   }
   ```

2. **Create the migrator:**
   ```java
   @Migrator
   public class UserMigrator implements ClassMigrator<OldUser, NewUser> {
       @Override
       public NewUser migrate(OldUser old) {
           return new NewUser(old.id, old.name, "unknown@example.com");
       }
   }
   ```

3. **Create the agent:**
   ```java
   public class MigrationAgent {
       public static void agentmain(String agentArgs, Instrumentation inst) {
           ClassLoader cl = MigrationAgent.class.getClassLoader();
           MigrationEngine.createAndMigrate(
               Set.of(ServiceMain.class),
               false,
               cl,
               agentArgs
           );
       }
   }
   ```

4. **Configure Maven (pom.xml):**
   ```xml
   <plugin>
       <groupId>org.apache.maven.plugins</groupId>
       <artifactId>maven-jar-plugin</artifactId>
       <configuration>
           <archive>
               <manifestEntries>
                   <Agent-Class>migration.MigrationAgent</Agent-Class>
                   <Can-Redefine-Classes>true</Can-Redefine-Classes>
                   <Can-Retransform-Classes>true</Can-Retransform-Classes>
               </manifestEntries>
           </archive>
       </configuration>
   </plugin>
   ```

5. **Trigger migration:**
   ```bash
   java --add-modules jdk.attach \
        -cp migration-payload.jar \
        migrator.load.VirtualMachineAgentLoader <PID> migration-payload.jar
   ```

## API Reference

### MigrationEngine

| Method | Description |
|--------|-------------|
| `createAndMigrate(classesToScan)` | Create engine and run migration |
| `createAndMigrate(classesToScan, classLoader, jarPath)` | With custom loader |
| `createAndMigrate(classesToScan, classLoader, jarPath, genericContainers, interfaceType)` | With container updates |
| `attachAndLoad(pid, agentJarPath)` | Attach to JVM and load agent |
| `attachAndLoad(pid, agentJarPath, agentArgs)` | Attach with custom arguments |
| `applyConfig(config)` | Apply migration configuration |
| `loadAndApplyConfig()` | Load config from classpath and apply |
| `setTimeoutConfig(config)` | Set timeout configuration |
| `setAllTimeoutsSeconds(seconds)` | Set all timeouts to same value |
| `setFullHeapWalk(boolean)` | Enable/disable full heap walk |
| `isFullHeapWalk()` | Check if full heap walk is enabled |
| `getTimeoutConfig()` | Get current timeout configuration |
| `migrate(classesToScan, containers, interfaceType)` | Run migration with container updates |
| `migrateWithTimeout(classesToScan, containers, interfaceType, timeout)` | Run migration with overall timeout |
| `getLastMetrics()` | Get metrics from last migration |
| `validateHeapSize(config)` | Validate heap against config limits |

### MigrationConfig

| Method | Description |
|--------|-------------|
| `builder()` | Create a new configuration builder |
| `heapWalkMode()` | Get heap walk mode (FULL/SPEC) |
| `isFullHeapWalk()` | Check if full heap walk is enabled |
| `heapWalkTimeout()` | Get heap walk timeout |
| `criticalPhaseTimeout()` | Get critical phase timeout |
| `smokeTestTimeout()` | Get smoke test timeout |
| `minHeapSizeMb()` | Get minimum heap size requirement |
| `maxHeapSizeMb()` | Get maximum heap size limit |
| `historySize()` | Get history size limit |
| `alertLevel()` | Get alert logging level |

**Builder Methods:**

| Method | Description |
|--------|-------------|
| `heapWalkMode(HeapWalkMode)` | Set heap walk mode |
| `heapWalkTimeout(Duration)` | Set heap walk timeout |
| `heapWalkTimeoutSeconds(long)` | Set heap walk timeout in seconds |
| `heapSnapshotTimeout(Duration)` | Set heap snapshot timeout |
| `heapSnapshotTimeoutSeconds(long)` | Set heap snapshot timeout in seconds |
| `criticalPhaseTimeout(Duration)` | Set critical phase timeout |
| `criticalPhaseTimeoutSeconds(long)` | Set critical phase timeout in seconds |
| `smokeTestTimeout(Duration)` | Set smoke test timeout |
| `smokeTestTimeoutSeconds(long)` | Set smoke test timeout in seconds |
| `allTimeoutsSeconds(long)` | Set all timeouts to the same value |
| `minHeapSizeMb(long)` | Set minimum heap size requirement |
| `maxHeapSizeMb(long)` | Set maximum heap size limit |
| `historySize(int)` | Set history size limit |
| `alertLevel(AlertLevel)` | Set alert logging level |
| `build()` | Build the configuration |

### MigrationConfigLoader

| Method | Description |
|--------|-------------|
| `load()` | Load config from classpath |
| `loadFromFile(path)` | Load config from external file |

### MigrationTimeoutConfig

| Method | Description |
|--------|-------------|
| `builder()` | Create a new timeout configuration builder |
| `heapWalkTimeout()` | Get heap walk timeout |
| `heapSnapshotTimeout()` | Get heap snapshot timeout |
| `criticalPhaseTimeout()` | Get critical phase timeout |
| `smokeTestTimeout()` | Get smoke test timeout |

**Builder Methods:**

| Method | Description |
|--------|-------------|
| `heapWalkTimeout(Duration)` | Set heap walk timeout |
| `heapWalkTimeoutSeconds(long)` | Set heap walk timeout in seconds |
| `heapSnapshotTimeout(Duration)` | Set heap snapshot timeout |
| `heapSnapshotTimeoutSeconds(long)` | Set heap snapshot timeout in seconds |
| `criticalPhaseTimeout(Duration)` | Set critical phase timeout |
| `criticalPhaseTimeoutSeconds(long)` | Set critical phase timeout in seconds |
| `smokeTestTimeout(Duration)` | Set smoke test timeout |
| `smokeTestTimeoutSeconds(long)` | Set smoke test timeout in seconds |
| `allTimeoutsSeconds(long)` | Set all timeouts to the same value |
| `build()` | Build the configuration |

### MigrationMetrics

| Method | Description |
|--------|-------------|
| `migrationId()` | Get migration identifier |
| `totalDurationMs()` | Get total duration in milliseconds |
| `totalDuration()` | Get total duration as Duration |
| `phaseDuration(phase)` | Get duration for specific phase |
| `objectsMigrated()` | Get count of migrated objects |
| `objectsPatched()` | Get count of patched objects |
| `migratorCount()` | Get number of migrators executed |
| `startTime()` | Get migration start timestamp |
| `endTime()` | Get migration end timestamp |
| `heapDelta()` | Get heap memory change in bytes |
| `memoryBefore()` / `memoryAfter()` | Get memory snapshots (MemoryMetrics) |
| `cpu()` | Get CPU metrics (CpuMetrics) |
| `summary()` | Get human-readable summary |
| `toMap()` | Convert to Map for JSON |

**MemoryMetrics:**
| Method | Description |
|--------|-------------|
| `heapUsed()` | Heap memory used in bytes |
| `heapCommitted()` | Heap memory committed in bytes |
| `heapMax()` | Maximum heap size in bytes |
| `nonHeapUsed()` | Non-heap memory used in bytes |
| `heapSummary()` | Human-readable heap summary |

**CpuMetrics:**
| Method | Description |
|--------|-------------|
| `before()` | CPU usage before migration (0.0-1.0) |
| `after()` | CPU usage after migration (0.0-1.0) |
| `peak()` | Peak CPU usage during migration (0.0-1.0) |
| `processors()` | Number of available processors |
| `summary()` | Human-readable CPU summary |

### MigrationState

| Method | Description |
|--------|-------------|
| `getInstance()` | Get singleton instance |
| `getStatus()` | Get current status (IDLE/IN_PROGRESS/SUCCESS/FAILED) |
| `getCurrentPhase()` | Get current migration phase |
| `getCurrentMigrationId()` | Get current migration ID |
| `getLastMetrics()` | Get last migration metrics |
| `getLastError()` | Get last error message |
| `getHistory()` | Get migration history |
| `toMap()` | Convert to Map for JSON |

### MigrationHistoryEntry

| Method | Description |
|--------|-------------|
| `migrationId()` | Get migration identifier |
| `status()` | Get final status (SUCCESS/FAILED) |
| `timestamp()` | Get completion timestamp |
| `metrics()` | Get migration metrics (contains duration, object counts, etc.) |
| `errorMessage()` | Get error message (if failed) |

### RegistryUpdater

| Method | Description |
|--------|-------------|
| `updateAnnotatedRegistries(classes, heapObjects)` | Update @UpdateRegistry fields |
| `updateGenericContainer(container, interfaceType)` | Update single container |
| `updateGenericContainers(containers, interfaceType)` | Update multiple containers |
| `updateGenericFieldsInClasses(classes, heapObjects, interfaceTypes)` | Scan and update generic fields |

### MigratorDescriptor

Describes a migrator class and its type mappings (used in smoke tests and metrics).

| Method | Description |
|--------|-------------|
| `migrator()` | Get the migrator implementation instance |
| `from()` | Get the source (old) class type being migrated |
| `to()` | Get the target (new) class type after migration |
| `commonInterface()` | Get the common interface shared by source and target |

### AgentLoader

| Method | Description |
|--------|-------------|
| `load(pid, agentJarPath)` | Load agent into target JVM |
| `load(pid, agentJarPath, agentArgs)` | Load with custom arguments |

### Enums

**HeapWalkMode:**
| Value | Description |
|-------|-------------|
| `FULL` | Walk entire heap to find all instances |
| `SPEC` | Use specification-based targeted heap walk |

**AlertLevel:**
| Value | Description |
|-------|-------------|
| `DEBUG` | All events (started, phases, completed) |
| `WARNING` | Warnings and errors only |
| `ERROR` | Errors only (migration failed, rollback failed) |

**MigrationState.Status:**
| Value | Description |
|-------|-------------|
| `IDLE` | No migration in progress |
| `IN_PROGRESS` | Migration currently running |
| `SUCCESS` | Last migration completed successfully |
| `FAILED` | Last migration failed |

**MigrationMetrics.Phase:**
| Value | Description |
|-------|-------------|
| `FIRST_PASS` | Object allocation and migration |
| `CRITICAL_PHASE` | Reference patching and registry updates |
| `SECOND_PASS` | Patching remaining references (within critical phase) |
| `REGISTRY_UPDATE` | Registry update phase (within critical phase) |
| `SMOKE_TEST` | Smoke test execution |

## Configuration

Migration behavior can be configured via `migration.properties` or `migration.yml` on the classpath.

### Configuration Properties

| Property | Description | Default |
|----------|-------------|---------|
| `migration.heap.walk.mode` | Heap walk strategy: `FULL` or `SPEC` | `FULL` |
| `migration.timeout.heap.walk` | Heap walk timeout in seconds | 0 (disabled) |
| `migration.timeout.heap.snapshot` | Heap snapshot timeout in seconds | 0 (disabled) |
| `migration.timeout.critical.phase` | Critical phase timeout in seconds | 0 (disabled) |
| `migration.timeout.smoke.test` | Smoke test timeout in seconds | 0 (disabled) |
| `migration.heap.size.min` | Minimum required heap size in MB | 0 (disabled) |
| `migration.heap.size.max` | Maximum allowed used heap in MB | 0 (disabled) |
| `migration.history.size` | Number of migration history entries | 10 |
| `migration.alert.level` | Logging level: `DEBUG`, `WARNING`, `ERROR` | `WARNING` |

### Example Configuration

**migration.properties:**
```properties
migration.heap.walk.mode=FULL
migration.timeout.heap.walk=60
migration.timeout.critical.phase=30
migration.timeout.smoke.test=10
migration.heap.size.min=512
migration.alert.level=DEBUG
migration.history.size=20
```

**migration.yml:**
```yaml
migration:
  heap:
    walk:
      mode: FULL
    size:
      min: 512
  timeout:
    heap:
      walk: 60
    critical:
      phase: 30
    smoke:
      test: 10
  alert:
    level: DEBUG
  history:
    size: 20
```

### Programmatic Configuration

```java
MigrationConfig config = MigrationConfig.builder()
    .heapWalkMode(HeapWalkMode.FULL)
    .heapWalkTimeoutSeconds(60)
    .criticalPhaseTimeoutSeconds(30)
    .smokeTestTimeoutSeconds(10)
    .minHeapSizeMb(512)
    .alertLevel(AlertLevel.DEBUG)
    .historySize(20)
    .build();

MigrationEngine engine = new MigrationEngine(classLoader, jarPath);
engine.applyConfig(config);
```

## Metrics and Monitoring

### Migration Metrics

The framework collects comprehensive metrics during migration:

```java
MigrationMetrics metrics = MigrationEngine.getLastMetrics();

// Timing
long totalMs = metrics.totalDurationMs();
long firstPassMs = metrics.phaseDuration(Phase.FIRST_PASS);
long criticalPhaseMs = metrics.phaseDuration(Phase.CRITICAL_PHASE);

// Object counts
int migrated = metrics.objectsMigrated();
int patched = metrics.objectsPatched();

// Memory
long heapDelta = metrics.heapDelta();
String heapSummary = metrics.memoryAfter().heapSummary();

// CPU
String cpuSummary = metrics.cpu().summary();

// Human-readable summary
System.out.println(metrics.summary());
// Output: Migration #1 in 234ms | Heap: 128.5MB / 256.0MB (max 4.0GB) (delta: 12.3MB) | CPU: 15.2% -> 8.1% (peak: 45.3%) | Objects: 1000 migrated, 5000 patched

// JSON serialization
Map<String, Object> metricsMap = metrics.toMap();
```

### Migration State

Track migration status across the JVM:

```java
MigrationState state = MigrationState.getInstance();

// Current status
MigrationState.Status status = state.getStatus();  // IDLE, IN_PROGRESS, SUCCESS, FAILED

// During migration
Phase currentPhase = state.getCurrentPhase();
long migrationId = state.getCurrentMigrationId();

// After migration
String lastError = state.getLastError();
MigrationMetrics lastMetrics = state.getLastMetrics();

// History
List<MigrationHistoryEntry> history = state.getHistory();
for (MigrationHistoryEntry entry : history) {
    System.out.printf("Migration %d: %s at %s%n",
        entry.migrationId(), entry.status(), entry.timestamp());
}

// JSON for monitoring endpoints
Map<String, Object> stateMap = state.toMap();
```

### Alert Logging

Migration events are logged with structured format for log aggregators:

```
12:00:00.000 INFO  migration - MIGRATION_STARTED id=42
12:00:00.100 INFO  migration - PHASE_STARTED id=42 phase=FIRST_PASS
12:00:00.500 INFO  migration - PHASE_COMPLETED id=42 phase=FIRST_PASS duration_ms=400
12:00:01.000 INFO  migration - MIGRATION_COMPLETED id=42 duration_ms=1000 objects_migrated=500
```

Configure alert level to control verbosity:
- `DEBUG` - All events (started, phases, completed)
- `WARNING` - Warnings and errors only (rollback triggered)
- `ERROR` - Errors only (migration failed, rollback failed)

## Timeouts

Configure timeouts to prevent migrations from hanging:

```java
// Via configuration
MigrationConfig config = MigrationConfig.builder()
    .heapWalkTimeoutSeconds(60)      // Heap traversal
    .heapSnapshotTimeoutSeconds(30)  // Per-class snapshots
    .criticalPhaseTimeoutSeconds(30) // Phase listener callbacks
    .smokeTestTimeoutSeconds(10)     // Smoke test execution
    .build();

// Or via MigrationTimeoutConfig
MigrationTimeoutConfig timeouts = MigrationTimeoutConfig.builder()
    .allTimeoutsSeconds(60)  // Set all timeouts at once
    .build();

engine.setTimeoutConfig(timeouts);

// Convenience methods
engine.setAllTimeoutsSeconds(60);
engine.setAllTimeouts(Duration.ofMinutes(1));
```

When a timeout is exceeded:
1. A `MigrationTimeoutException` is thrown
2. Rollback is triggered automatically
3. The error is logged with `MIGRATION_TIMEOUT` marker

## JVM Requirements

The target JVM must be started with the native agent for heap walking:

```bash
java -agentpath:/path/to/libagent.so \
     --add-opens java.base/java.lang=ALL-UNNAMED \
     --add-opens java.base/java.lang.reflect=ALL-UNNAMED \
     --add-opens java.base/java.util=ALL-UNNAMED \
     -jar your-application.jar
```

## Building

```bash
# Build the library only
mvn clean install

# Build everything including examples
mvn clean install -Pexamples

# Build native agent
gcc -fPIC \
    -I"${JAVA_HOME}/include" \
    -I"${JAVA_HOME}/include/linux" \
    -shared \
    -o agent/libagent.so \
    agent/agent.c

# Build Docker images
docker compose -f examples/docker-compose.yml build
```
