package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AsyncSqlTest {
    @TempDir Path tmp;

    @Test void sqliteCrudRunsAsyncAndCloses() throws Exception {
        SqlCatalog queries = SqlCatalog.parse("""
            -- name: create
            CREATE TABLE test (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT);
            -- name: insert
            INSERT INTO test (name) VALUES (:name);
            -- name: select
            SELECT id, name FROM test WHERE name = :name;
            -- name: change
            UPDATE test SET name = :updated WHERE name = :original;
            """);
        AsyncSql db = AsyncSql.builder("jdbc:sqlite:" + tmp.resolve("test.sqlite"))
                .catalog(queries).workerLimit(2).build();
        try {
            assertNull(db.executeGeneric("create", Map.of()).get(10, TimeUnit.SECONDS));
            InsertResult inserted = db.executeInsert("insert", Map.of("name", "Alice")).get(10, TimeUnit.SECONDS);
            assertEquals(1, inserted.affectedRows());
            assertEquals("Alice", db.executeSelect("select", Map.of("name", "Alice"))
                    .get(10, TimeUnit.SECONDS).get(0).getString("name"));
            assertEquals(1, db.executeChange("change", Map.of("updated", "Bob", "original", "Alice"))
                    .get(10, TimeUnit.SECONDS));
            assertEquals(0, db.executeSelect("select", Map.of("name", "Alice"))
                    .get(10, TimeUnit.SECONDS).size());
            assertThrows(CompletionException.class,
                    () -> db.executeSelect("select", Map.of()).join());
        } finally {
            db.close();
        }
        assertTrue(db.executeSelect("select", Map.of("name", "Bob")).isCompletedExceptionally());
    }
}
