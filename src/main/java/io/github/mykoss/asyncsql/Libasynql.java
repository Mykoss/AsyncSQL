package io.github.mykoss.asyncsql;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/** Config/resource-style factory mirroring libasynql::create for Java plugins. */
public final class Libasynql {
    private Libasynql() {}

    /**
     * @param dataFolder Plugin data directory, as returned by getDataFolder().toPath().
     * @param resources Class in the plugin JAR, for loading /sqlite.sql and /mysql.sql.
     * @param config Map equivalent to libasynql's "database" config entry.
     * @param sqlFiles Map from dialect ("sqlite"/"mysql"/"mariadb") to a
     *                 resource name or List of resource names.
     * @param callbackExecutor Executor that posts to the Nukkit-MOT main thread.
     */
    public static DataConnector create(Path dataFolder, Class<?> resources,
                                       Map<String, ?> config, Map<String, ?> sqlFiles,
                                       Executor callbackExecutor, Logger logger) {
        Objects.requireNonNull(dataFolder, "dataFolder");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sqlFiles, "sqlFiles");
        Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        String dialect = String.valueOf(or(config, "type", "sqlite")).toLowerCase(Locale.ROOT);
        if (dialect.equals("sqlite3") || dialect.equals("sq3")) dialect = "sqlite";
        if (dialect.equals("mysqli")) dialect = "mysql";
        if (!List.of("sqlite", "mysql", "mariadb").contains(dialect)) {
            throw new IllegalArgumentException("Unsupported database type: " + dialect);
        }
        Object selected = sqlFiles.get(dialect);
        if (selected == null) throw new IllegalArgumentException("Missing PSF resources for " + dialect);
        Map<?, ?> settings = config.get(dialect) instanceof Map<?, ?> map ? map : Map.of();
        String url;
        String driver;
        String username = null, password = null;
        if (dialect.equals("sqlite")) {
            String filename = String.valueOf(or(settings, "file", "data.sqlite"));
            Path db = Path.of(filename);
            if (!db.isAbsolute()) db = dataFolder.resolve(db);
            db = db.normalize();
            url = "jdbc:sqlite:" + db.toAbsolutePath();
            driver = "org.sqlite.JDBC";
        } else {
            String host = String.valueOf(or(settings, "host", "127.0.0.1"));
            String database = String.valueOf(or(settings, "schema", ""));
            if (database.isBlank() || host.isBlank() || !database.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid MySQL/MariaDB host or schema");
            }
            int port = number(settings.get("port"), 3306);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid database port");
            url = "jdbc:" + dialect + "://" + host + ":" + port + "/" + database;
            driver = dialect.equals("mysql") ? "com.mysql.cj.jdbc.Driver"
                    : "org.mariadb.jdbc.Driver";
            username = String.valueOf(or(settings, "username", ""));
            password = String.valueOf(or(settings, "password", ""));
        }

        String resourceDialect = dialect;
        SqlCatalog merged = new SqlCatalog();
        List<?> resourcesList = selected instanceof List<?> list ? list : List.of(selected);
        for (Object item : resourcesList) {
            String path = "/" + String.valueOf(item).replaceFirst("^/", "");
            try (InputStream stream = resources.getResourceAsStream(path)) {
                if (stream == null) throw new IllegalArgumentException("Missing SQL resource: " + path);
                SqlCatalog next = SqlCatalog.parsePsf(
                        new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                        resourceDialect);
                for (var entry : next.compiledQueries().entrySet()) {
                    // Merge queries preserving PSF types and fragments, not only SQL text.
                    merged.mergePsfQuery(entry.getKey(), entry.getValue(), next.asMap().get(entry.getKey()));
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to load PSF resource " + path, e);
            }
        }
        int workers = number(config.get("worker-limit"), 1);
        int queue = number(config.get("queue-limit"), 256);
        if (workers < 1 || queue < 1) throw new IllegalArgumentException("Invalid worker/queue limit");
        try {
            Class.forName(driver, true, resources.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("JDBC driver not bundled in plugin: " + driver, e);
        }
        AsyncSql.Builder builder = AsyncSql.builder(url).driverClassName(driver)
                .catalog(merged).workerLimit(workers).queueLimit(queue);
        if (username != null) builder.username(username).password(password);
        DataConnectorImpl connector = new DataConnectorImpl(builder.build(), dialect, callbackExecutor, logger);
        connector.setLoggingQueries(Boolean.TRUE.equals(config.get("log-queries")));
        return connector;
    }

    private static Object or(Map<?, ?> source, String key, Object fallback) {
        Object value = source.get(key);
        return value == null ? fallback : value;
    }

    private static int number(Object value, int fallback) {
        return value == null ? fallback : value instanceof Number n
                ? n.intValue() : Integer.parseInt(value.toString());
    }
}
