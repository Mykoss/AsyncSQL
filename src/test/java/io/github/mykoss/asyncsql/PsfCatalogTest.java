package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PsfCatalogTest {
    @TempDir Path temporary;

    private static final String PSF = """
        -- #!sqlite
        -- #{ schema
        -- #{ init
        CREATE TABLE IF NOT EXISTS psf_data (id TEXT PRIMARY KEY, coins INTEGER NOT NULL, note TEXT);
        -- #&
        CREATE INDEX IF NOT EXISTS psf_data_coins ON psf_data (coins);
        -- #}
        -- #}
        -- #{ wallet
        -- #{ add
        -- # :id string
        -- # :coins int 5
        -- # :note ?string
        INSERT INTO psf_data (id, coins, note) VALUES (:id, :coins, :note);
        -- #}
        -- #{ increase
        -- # :ids list:string
        -- # :amount int
        UPDATE psf_data SET coins = coins + :amount WHERE id IN :ids;
        -- #}
        -- #{ emptyIds
        -- # :ids list?string
        SELECT id FROM psf_data WHERE id IN :ids;
        -- #}
        -- #{ read
        -- # :id string
        SELECT id, coins, note FROM psf_data WHERE id = :id;
        -- #}
        -- #}
        """;

    @Test void psfGroupsDefaultsNullsListsAndMultipleStatementsWork() throws Exception {
        SqlCatalog catalog = SqlCatalog.parsePsf(PSF, "sqlite");
        assertTrue(catalog.asMap().containsKey("wallet.increase"));
        assertTrue(catalog.asMap().containsKey("schema.init"));
        try (AsyncSql sql = AsyncSql.builder("jdbc:sqlite:" + temporary.resolve("data.db"))
                .catalog(catalog).workerLimit(1).build()) {
            sql.executeGeneric("schema.init", Map.of()).get(10, TimeUnit.SECONDS);
            sql.executeInsert("wallet.add", Map.of("id", "a", "note", "sample"))
                    .get(10, TimeUnit.SECONDS);
            sql.executeInsert("wallet.add", Map.of("id", "b", "coins", 10, "note", "sample"))
                    .get(10, TimeUnit.SECONDS);
            assertEquals(2, sql.executeChange("wallet.increase",
                    Map.of("ids", List.of("a", "b"), "amount", 2)).get(10, TimeUnit.SECONDS));
            assertEquals(7, sql.executeSelect("wallet.read", Map.of("id", "a"))
                    .get(10, TimeUnit.SECONDS).get(0).getLong("coins"));
            assertEquals(12, sql.executeSelect("wallet.read", Map.of("id", "b"))
                    .get(10, TimeUnit.SECONDS).get(0).getLong("coins"));
            assertTrue(sql.executeSelect("wallet.emptyIds", Map.of("ids", List.of()))
                    .get(10, TimeUnit.SECONDS).isEmpty());
            assertThrows(CompletionException.class, () ->
                    sql.executeChange("wallet.increase",
                            Map.of("ids", List.of(), "amount", 1)).join());
            assertThrows(CompletionException.class, () ->
                    sql.executeChange("wallet.increase",
                            Map.of("ids", List.of("a"), "amount", "bad")).join());
            assertThrows(CompletionException.class, () ->
                    sql.executeInsert("wallet.add", Map.of("id", "bad", "coins", 1)).join());
            sql.awaitPending().get(10, TimeUnit.SECONDS);
            assertEquals(0, sql.pendingCount());
        }
    }

    @Test void psfUsesTransactionAndRollsBack() throws Exception {
        SqlCatalog catalog = SqlCatalog.parsePsf(PSF, "sqlite");
        try (AsyncSql sql = AsyncSql.builder("jdbc:sqlite:" + temporary.resolve("tx.db"))
                .catalog(catalog).workerLimit(1).build()) {
            sql.executeGeneric("schema.init", Map.of()).join();
            assertThrows(CompletionException.class, () -> sql.executeTransaction(tx -> {
                tx.executeInsert("wallet.add", Map.of("id", "rollback", "coins", 10, "note", "n"));
                throw new IllegalStateException("rollback");
            }).join());
            assertTrue(sql.executeSelect("wallet.read", Map.of("id", "rollback")).join().isEmpty());
        }
    }

    @Test void malformedFilesGiveActionableErrors() {
        assertThrows(IllegalArgumentException.class, () -> SqlCatalog.parsePsf(PSF, "mysql"));
        assertThrows(IllegalArgumentException.class, () -> SqlCatalog.parsePsf(
                "-- #!sqlite\n-- #{ query\nSELECT 1;\n", "sqlite"));
        assertThrows(IllegalArgumentException.class, () -> SqlCatalog.parsePsf(
                "-- #!sqlite\n-- #{ group\n-- #{ query\nSELECT 1;\n-- #}\n-- #}\n-- #{ group.query\nSELECT 2;\n-- #}\n", "sqlite"));
        assertThrows(IllegalArgumentException.class, () -> SqlCatalog.parsePsf(
                "-- #!sqlite\n-- #{ query\n-- # :missing string\nSELECT 1;\n-- #}\n", "sqlite"));
    }

    @Test void parserSkipsParametersInQuotesAndSqlComments() {
        SqlCatalog catalog = SqlCatalog.parsePsf("""
            -- #!mysql
            -- #{ demo
            -- # :id string
            SELECT ':fake', ":fake", id FROM demo WHERE id = :id /* :skip */;
            -- #}
            """, "mysql");
        assertTrue(catalog.asMap().containsKey("demo"));
        assertThrows(IllegalArgumentException.class, () ->
                SqlCatalog.parsePsf("-- #!mysql\n-- #{ demo\nSELECT :missing;\n-- #}\n", "mysql"));
    }
}
