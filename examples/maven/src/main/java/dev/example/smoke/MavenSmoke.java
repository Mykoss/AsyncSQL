package dev.example.smoke;

import io.github.mykoss.asyncsql.AsyncSql;
import io.github.mykoss.asyncsql.SqlCatalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MavenSmoke {
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("asyncsql-smoke-", ".db");
        try {
            SqlCatalog catalog = new SqlCatalog()
                    .register("create", "CREATE TABLE smoke (id INTEGER PRIMARY KEY, value INTEGER)")
                    .register("insert", "INSERT INTO smoke (id, value) VALUES (:id, :value)")
                    .register("find", "SELECT value FROM smoke WHERE id = :id");

            try (AsyncSql sql = AsyncSql.builder("jdbc:sqlite:" + file)
                    .catalog(catalog).workerLimit(1).build()) {
                sql.executeGeneric("create", Map.of()).get(20, TimeUnit.SECONDS);
                sql.executeInsert("insert", Map.of("id", 1, "value", 73))
                        .get(20, TimeUnit.SECONDS);
                long value = sql.executeSelect("find", Map.of("id", 1))
                        .get(20, TimeUnit.SECONDS).get(0).getLong("value");
                if (value != 73) throw new IllegalStateException("JDBC smoke test failed: " + value);
            }
            System.out.println("MavenSmoke shaded JDBC smoke test passed");
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
