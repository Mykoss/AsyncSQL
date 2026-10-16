package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against MySQL and MariaDB services in GitHub Actions. */
class ExternalJdbcIntegrationTest {

    @Test void mysql() throws Exception { verify("MYSQL"); }
    @Test void mariaDb() throws Exception { verify("MARIADB"); }

    private void verify(String type) throws Exception {
        String prefix = "ASYNCSQL_TEST_" + type + "_";
        String url = System.getenv(prefix + "URL");
        assumeTrue(url != null && !url.isBlank(), "JDBC integration URL not supplied");
        String table = "asyncsql_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        SqlCatalog queries = new SqlCatalog()
                .register("create", "CREATE TABLE " + table
                        + " (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE)")
                .register("drop", "DROP TABLE IF EXISTS " + table)
                .register("insert", "INSERT INTO " + table + " (name) VALUES (:name)")
                .register("select", "SELECT id, name FROM " + table + " WHERE name = :name")
                .register("update", "UPDATE " + table + " SET name = :newName WHERE name = :oldName");
        try (AsyncSql db = AsyncSql.builder(url)
                .username(System.getenv(prefix + "USER"))
                .password(System.getenv(prefix + "PASSWORD"))
                .catalog(queries).workerLimit(2).build()) {
            db.executeGeneric("create", Map.of()).get(20, TimeUnit.SECONDS);
            try {
                InsertResult insert = db.executeInsert("insert", Map.of("name", "one"))
                        .get(20, TimeUnit.SECONDS);
                assertEquals(1, insert.affectedRows());
                assertTrue(insert.generatedId() > 0);
                List<SqlRow> rows = db.executeSelect("select", Map.of("name", "one"))
                        .get(20, TimeUnit.SECONDS);
                assertEquals(1, rows.size());
                assertEquals("one", rows.get(0).getString("name"));

                String committed = db.executeTransaction(tx -> {
                    tx.executeInsert("insert", Map.of("name", "two"));
                    tx.executeChange("update", Map.of("newName", "three", "oldName", "two"));
                    assertEquals(1, tx.executeSelect("select", Map.of("name", "three")).size());
                    return "ok";
                }).get(20, TimeUnit.SECONDS);
                assertEquals("ok", committed);
                assertEquals(1, db.executeSelect("select", Map.of("name", "three"))
                        .get(20, TimeUnit.SECONDS).size());

                assertThrows(CompletionException.class, () -> db.executeTransaction(tx -> {
                    tx.executeInsert("insert", Map.of("name", "four"));
                    tx.executeInsert("insert", Map.of("name", "four"));
                    return null;
                }).join());
                assertTrue(db.executeSelect("select", Map.of("name", "four"))
                        .get(20, TimeUnit.SECONDS).isEmpty());
            } finally {
                db.executeGeneric("drop", Map.of()).get(20, TimeUnit.SECONDS);
            }
        }
    }
}
