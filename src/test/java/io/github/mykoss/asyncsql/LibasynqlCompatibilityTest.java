package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LibasynqlCompatibilityTest {
    @TempDir Path temp;

    @Test void configFactoryCallbacksMetadataMultiAndReloadWork() throws Exception {
        try (ExecutorService main = Executors.newSingleThreadExecutor(
                task -> new Thread(task, "nukkit-main-test"))) {
            DataConnector db = Libasynql.create(temp, getClass(),
                    Map.of("type", "sqlite", "sqlite", Map.of("file", "compat.db"),
                            "worker-limit", 1),
                    Map.of("sqlite", "compat/sqlite.sql"), main, Logger.getLogger("compat"));
            try (db) {
                db.setLoggingQueries(true);
                assertTrue(db.isLoggingQueries());
                db.asyncGeneric("compat.schema", Map.of()).get(10, TimeUnit.SECONDS);
                CompletableFuture<String> callbackThread = new CompletableFuture<>();
                db.executeInsert("compat.add", Map.of("id", "alpha"), (id, count) -> {
                    assertEquals(1, count);
                    callbackThread.complete(Thread.currentThread().getName());
                }, callbackThread::completeExceptionally);
                assertEquals("nukkit-main-test", callbackThread.get(10, TimeUnit.SECONDS));

                SqlSelectResult info = db.asyncSelectWithInfo("compat.find",
                        Map.of("id", "alpha")).get(10, TimeUnit.SECONDS);
                assertEquals(1, info.getRows().size());
                assertEquals(3, info.getRows().get(0).getLong("score"));
                assertEquals(2, info.getColumnInfo().size());
                assertEquals("id", info.getColumnInfo().get(0).getName());

                CompletableFuture<List<Object>> multi = new CompletableFuture<>();
                db.executeMulti("compat.two", Map.of("id", "alpha"),
                        DataConnector.MODE_SELECT, multi::complete, multi::completeExceptionally);
                List<Object> outputs = multi.get(10, TimeUnit.SECONDS);
                assertEquals(2, outputs.size());
                assertNull(outputs.get(0));
                @SuppressWarnings("unchecked")
                List<SqlRow> rows = (List<SqlRow>) outputs.get(1);
                assertEquals(4, rows.get(0).getLong("score"));

                CompletableFuture<List<Object>> raw = new CompletableFuture<>();
                db.executeImplRaw(List.of("UPDATE compat SET score=score+1 WHERE id=:id",
                                "SELECT score FROM compat WHERE id=:id"),
                        List.of(Map.of("id", "alpha"), Map.of("id", "alpha")),
                        List.of(DataConnector.MODE_CHANGE, DataConnector.MODE_SELECT),
                        raw::complete, raw::completeExceptionally);
                assertEquals(2, raw.get(10, TimeUnit.SECONDS).size());

                db.loadQuery("compat.raw", "SELECT score FROM compat WHERE id=:id");
                assertEquals(5L, db.asyncSelect("compat.raw", Map.of("id", "alpha"))
                        .get(10, TimeUnit.SECONDS).get(0).getLong("score"));
                assertThrows(IllegalArgumentException.class,
                        () -> db.loadQuery("compat.raw", "SELECT 1"));
                try (var in = getClass().getResourceAsStream("/compat/additional.sql")) {
                    db.loadQueryFile(in);
                }
                assertEquals(5L, db.asyncSelect("compat.legacy", Map.of("id", "alpha"))
                        .get(10, TimeUnit.SECONDS).get(0).getLong("score"));
                db.awaitPending().get(10, TimeUnit.SECONDS);

                CompletableFuture<SqlError> sqlError = new CompletableFuture<>();
                db.executeSelect("compat.missing", Map.of(), ignored -> fail("should fail"),
                        sqlError::complete);
                assertEquals(SqlError.STAGE_EXECUTE, sqlError.get(10, TimeUnit.SECONDS).getStage());
                assertEquals("compat.missing", sqlError.get(10, TimeUnit.SECONDS).getQuery());
                assertNotNull(sqlError.get(10, TimeUnit.SECONDS).getCause());
            }
        }
    }

    @Test void unknownDialectAndMissingResourceFailFast() {
        assertThrows(IllegalArgumentException.class, () -> Libasynql.create(
                temp, getClass(), Map.of("type", "unsupported"),
                Map.of("sqlite", "compat/sqlite.sql"), Runnable::run, null));
        assertThrows(IllegalArgumentException.class, () -> Libasynql.create(
                temp, getClass(), Map.of("type", "sqlite"),
                Map.of("sqlite", "missing.sql"), Runnable::run, null));
    }
}
