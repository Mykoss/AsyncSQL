import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Loads the shaded demo JAR with an isolated classloader, while keeping the
 * current thread's context classloader unchanged, just as plugin servers do.
 * Checks whether Hikari can connect to the shaded SQLite JDBC implementation.
 */
public class IsolatedSqliteSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: IsolatedSqliteSmoke <shaded-jar>");
        Path jar = Path.of(args[0]).toAbsolutePath();
        Path db = Files.createTempFile("asyncsql-isolated-", ".db");
        try (URLClassLoader pluginLoader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            // Do NOT alter the context classloader: the driver is not visible to DriverManager by default.
            Class.forName("org.sqlite.JDBC", true, pluginLoader);
            Class<?> catalogType = Class.forName(
                    "dev.mykoss.asyncsqlbeta.libs.asyncsql.SqlCatalog", true, pluginLoader);
            Object catalog = catalogType.getDeclaredConstructor().newInstance();
            catalogType.getMethod("register", String.class, String.class).invoke(
                    catalog, "create", "CREATE TABLE IF NOT EXISTS test_data (id INT PRIMARY KEY)");
            catalogType.getMethod("register", String.class, String.class).invoke(
                    catalog, "insert", "INSERT INTO test_data(id) VALUES (:id)");
            catalogType.getMethod("register", String.class, String.class).invoke(
                    catalog, "count", "SELECT count(*) AS count FROM test_data");
            Class<?> asyncSqlType = Class.forName(
                    "dev.mykoss.asyncsqlbeta.libs.asyncsql.AsyncSql", true, pluginLoader);
            Object builder = asyncSqlType.getMethod("builder", String.class)
                    .invoke(null, "jdbc:sqlite:" + db);
            builder.getClass().getMethod("driverClassName", String.class)
                    .invoke(builder, "org.sqlite.JDBC");
            builder.getClass().getMethod("catalog", catalogType)
                    .invoke(builder, catalog);
            Object sql = builder.getClass().getMethod("build").invoke(builder);
            try {
                future(asyncSqlType.getMethod("executeGeneric", String.class, Map.class)
                        .invoke(sql, "create", Map.of())).get(30, TimeUnit.SECONDS);
                future(asyncSqlType.getMethod("executeChange", String.class, Map.class)
                        .invoke(sql, "insert", Map.of("id", 1))).get(30, TimeUnit.SECONDS);
                Object rows = future(asyncSqlType.getMethod("executeSelect", String.class, Map.class)
                        .invoke(sql, "count", Map.of())).get(30, TimeUnit.SECONDS);
                Object row = ((java.util.List<?>) rows).get(0);
                long count = (Long) row.getClass().getMethod("getLong", String.class).invoke(row, "count");
                if (count != 1L) throw new AssertionError("Expected 1 record; found " + count);
                System.out.println("PASS: isolated classloader Hikari + SQLite JDBC + native driver");
            } finally {
                asyncSqlType.getMethod("close").invoke(sql);
            }
        } finally {
            Files.deleteIfExists(db);
        }
    }

    private static CompletableFuture<?> future(Object result) {
        return (CompletableFuture<?>) result;
    }
}
