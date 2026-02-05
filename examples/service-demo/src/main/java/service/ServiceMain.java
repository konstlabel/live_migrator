package service;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import migrator.metrics.MigrationMetrics;
import migrator.state.MigrationHistoryEntry;
import migrator.state.MigrationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import service.model.User;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple long-running HTTP service that manages Users.
 *
 * <p>This service demonstrates live migration capabilities:
 * <ul>
 *   <li>The service is completely unaware of migration - it only knows about the User interface</li>
 *   <li>Users are created via {@link UserFactory} which can be replaced during migration</li>
 *   <li>The migration agent can be loaded into this running JVM to migrate OldUser → NewUser</li>
 * </ul>
 *
 * <h2>HTTP Endpoints:</h2>
 * <ul>
 *   <li>GET / - Health check</li>
 *   <li>GET /users - List all users with their implementation class</li>
 *   <li>POST /users - Create a new user (body: name=value)</li>
 * </ul>
 *
 * <h2>Usage:</h2>
 * <pre>
 * # Start the service
 * ./run-service.sh
 *
 * # In another terminal, trigger migration
 * ./run-migration.sh
 * </pre>
 *
 * @see UserFactory
 * @see service.model.User
 */
public class ServiceMain {

    private static final Logger log = LoggerFactory.getLogger(ServiceMain.class);
    public static final List<User> users = new ArrayList<>();
    private static final AtomicInteger idCounter = new AtomicInteger(1);
    private static final long startTimeMs = System.currentTimeMillis();

    public static void main(String[] args) throws Exception {
        // Pre-populate some users using the factory
        UserFactory factory = UserFactory.getInstance();
        users.add(factory.createUser(idCounter.getAndIncrement(), "Alice"));
        users.add(factory.createUser(idCounter.getAndIncrement(), "Bob"));
        users.add(factory.createUser(idCounter.getAndIncrement(), "Charlie"));

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));

        // GET /users - list all users
        server.createContext("/users", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                handleGetUsers(exchange);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                handlePostUser(exchange);
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        // GET / - simple health check (legacy)
        server.createContext("/", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 200, "Service is running\n");
            } else {
                sendResponse(exchange, 405, "Method Not Allowed");
            }
        });

        // GET /health - comprehensive health check with JVM metrics
        server.createContext("/health", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            if ("/health".equals(path)) {
                handleHealthCheck(exchange);
            } else if ("/health/migration".equals(path)) {
                handleMigrationStatus(exchange);
            } else if ("/health/migration/history".equals(path)) {
                handleMigrationHistory(exchange);
            } else {
                sendResponse(exchange, 404, "Not Found");
            }
        });

        server.start();
        log.info("Service started on http://localhost:8080");
        log.info("Endpoints:");
        log.info("  GET  /users                    - List all users");
        log.info("  POST /users                    - Create user (body: name=<name>)");
        log.info("  GET  /health                   - JVM health status");
        log.info("  GET  /health/migration         - Migration status");
        log.info("  GET  /health/migration/history - Migration history");

        // Keep the application running
        Thread.currentThread().join();
    }

    private static void handleGetUsers(HttpExchange exchange) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Users (").append(users.size()).append(" total):\n");
        sb.append("-----------------------------------\n");
        for (User user : users) {
            sb.append(String.format("  [%d] %s - %s%n",
                user.getId(),
                user.getName(),
                user.getClass().getSimpleName()));
        }
        sendResponse(exchange, 200, sb.toString());
    }

    private static void handlePostUser(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String name = "User" + idCounter.get();

        // Simple parsing: name=value
        if (body.contains("name=")) {
            name = body.split("name=")[1].split("&")[0].trim();
        }

        // Use factory to create user - after migration this will create NewUser
        User newUser = UserFactory.getInstance().createUser(idCounter.getAndIncrement(), name);
        users.add(newUser);

        log.info("Created user: {}", newUser);
        sendResponse(exchange, 201, "Created: " + newUser + " (" + newUser.getClass().getSimpleName() + ")\n");
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void handleHealthCheck(HttpExchange exchange) throws IOException {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("heap_used", memoryBean.getHeapMemoryUsage().getUsed());
        jvm.put("heap_max", memoryBean.getHeapMemoryUsage().getMax());
        jvm.put("cpu_load", osBean.getSystemLoadAverage());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("timestamp", Instant.now().toString());
        response.put("uptime_ms", System.currentTimeMillis() - startTimeMs);
        response.put("jvm", jvm);

        sendJsonResponse(exchange, 200, JsonWriter.toJson(response));
    }

    private static void handleMigrationStatus(HttpExchange exchange) throws IOException {
        MigrationState state = MigrationState.getInstance();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", state.getStatus().name());
        response.put("current_phase", state.getCurrentPhase() != null ? state.getCurrentPhase().name() : null);

        MigrationMetrics lastMetrics = state.getLastMetrics();
        if (lastMetrics != null) {
            Map<String, Object> lastMigration = new LinkedHashMap<>();
            lastMigration.put("id", lastMetrics.migrationId());
            lastMigration.put("timestamp", lastMetrics.endTime() != null ? lastMetrics.endTime().toString() : null);
            lastMigration.put("duration_ms", lastMetrics.totalDurationMs());
            lastMigration.put("objects_migrated", lastMetrics.objectsMigrated());
            lastMigration.put("objects_patched", lastMetrics.objectsPatched());
            response.put("last_migration", lastMigration);
        } else {
            response.put("last_migration", null);
        }

        if (state.getLastError() != null) {
            response.put("last_error", state.getLastError());
        }

        sendJsonResponse(exchange, 200, JsonWriter.toJson(response));
    }

    private static void handleMigrationHistory(HttpExchange exchange) throws IOException {
        List<MigrationHistoryEntry> history = MigrationState.getInstance().getHistory();

        List<Map<String, Object>> historyList = new ArrayList<>();
        for (MigrationHistoryEntry entry : history) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.migrationId());
            item.put("timestamp", entry.timestamp().toString());
            item.put("status", entry.status().name());

            if (entry.metrics() != null) {
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("duration_ms", entry.metrics().totalDurationMs());
                metrics.put("objects_migrated", entry.metrics().objectsMigrated());
                metrics.put("objects_patched", entry.metrics().objectsPatched());
                metrics.put("heap_delta", entry.metrics().heapDelta());
                item.put("metrics", metrics);
            }

            if (entry.errorMessage() != null) {
                item.put("error", entry.errorMessage());
            }

            historyList.add(item);
        }

        sendJsonResponse(exchange, 200, JsonWriter.toJson(historyList));
    }
}
