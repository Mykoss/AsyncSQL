package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SqlTransactionTest {
    @TempDir Path temporary;

    private AsyncSql open() {
        SqlCatalog queries = SqlCatalog.parse("""
            -- name: create
            CREATE TABLE wallets (uuid TEXT PRIMARY KEY, balance INTEGER NOT NULL);
            -- name: insert
            INSERT INTO wallets (uuid, balance) VALUES (:uuid, :balance);
            -- name: find
            SELECT uuid, balance FROM wallets WHERE uuid = :uuid;
            -- name: change
            UPDATE wallets SET balance = :balance WHERE uuid = :uuid;
            """);
        return AsyncSql.builder("jdbc:sqlite:" + temporary.resolve("transaction.sqlite"))
                .catalog(queries).workerLimit(1).build();
    }

    @Test void commitsMultipleOperationsAsOneUnit() throws Exception {
        try (AsyncSql db = open()) {
            db.executeGeneric("create", Map.of()).get(10, TimeUnit.SECONDS);
            String result = db.executeTransaction(tx -> {
                assertEquals(1, tx.executeInsert("insert", Map.of("uuid", "one", "balance", 10)).affectedRows());
                assertEquals(1, tx.executeChange("change", Map.of("uuid", "one", "balance", 42)));
                assertEquals(42, tx.executeSelect("find", Map.of("uuid", "one")).get(0).getLong("balance"));
                return "committed";
            }).get(10, TimeUnit.SECONDS);
            assertEquals("committed", result);
            assertEquals(42, db.executeSelect("find", Map.of("uuid", "one"))
                    .get(10, TimeUnit.SECONDS).get(0).getLong("balance"));
        }
    }

    @Test void rollsBackAllStatementsWhenCallbackThrows() throws Exception {
        try (AsyncSql db = open()) {
            db.executeGeneric("create", Map.of()).get(10, TimeUnit.SECONDS);
            CompletionException failure = assertThrows(CompletionException.class, () ->
                    db.executeTransaction(tx -> {
                        tx.executeInsert("insert", Map.of("uuid", "rollback", "balance", 100));
                        throw new IllegalArgumentException("abort");
                    }).join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertTrue(db.executeSelect("find", Map.of("uuid", "rollback"))
                    .get(10, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test void transactionIsNotUsableAfterCallback() throws Exception {
        AtomicReference<SqlTransaction> retained = new AtomicReference<>();
        try (AsyncSql db = open()) {
            db.executeGeneric("create", Map.of()).get(10, TimeUnit.SECONDS);
            db.executeTransaction(tx -> {
                retained.set(tx);
                return null;
            }).get(10, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () ->
                    retained.get().executeSelect("find", Map.of("uuid", "x")));
        }
    }

    @Test void rollsBackWhenJdbcFails() throws Exception {
        try (AsyncSql db = open()) {
            db.executeGeneric("create", Map.of()).get(10, TimeUnit.SECONDS);
            assertThrows(CompletionException.class, () ->
                    db.executeTransaction(tx -> {
                        tx.executeInsert("insert", Map.of("uuid", "same", "balance", 10));
                        tx.executeInsert("insert", Map.of("uuid", "same", "balance", 20));
                        return null;
                    }).join());
            assertTrue(db.executeSelect("find", Map.of("uuid", "same"))
                    .get(10, TimeUnit.SECONDS).isEmpty());
        }
    }
}
