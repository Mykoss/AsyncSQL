package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqlCatalogTest {
    @Test void parsesMultipleNamedQueries() {
        SqlCatalog catalog = SqlCatalog.parse("""
            -- name: players.create
            CREATE TABLE players (id TEXT);
            -- name: players.select
            SELECT id FROM players WHERE id = :id;
            """);
        assertEquals(2, catalog.asMap().size());
        assertTrue(catalog.asMap().get("players.select").contains(":id"));
    }

    @Test void rejectsDuplicateNames() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlCatalog.parse("-- name: q\nSELECT 1;\n-- name: q\nSELECT 2;"));
    }
}
