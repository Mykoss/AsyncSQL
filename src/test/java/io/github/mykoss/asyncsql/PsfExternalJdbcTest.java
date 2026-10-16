package io.github.mykoss.asyncsql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** PSF compatibility tests against the actual MySQL/MariaDB service in CI. */
class PsfExternalJdbcTest {
    @Test void mysql() throws Exception { verify("MYSQL", "mysql"); }
    @Test void mariadb() throws Exception { verify("MARIADB", "mariadb"); }

    private void verify(String vendor, String dialect) throws Exception {
        String prefix = "ASYNCSQL_TEST_" + vendor + "_";
        String jdbc = System.getenv(prefix + "URL");
        assumeTrue(jdbc != null, "No integration database configured");
        String table = "psf_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String psf = """
                -- #!DIALECT
                -- #{ create
                CREATE TABLE TABLE_NAME (id VARCHAR(64) PRIMARY KEY, points INT NOT NULL);
                -- #}
                -- #{ add
                -- # :id string
                -- # :points int 1
                INSERT INTO TABLE_NAME (id, points) VALUES (:id, :points);
                -- #}
                -- #{ many
                -- # :ids list:string
                -- # :delta int
                UPDATE TABLE_NAME SET points = points + :delta WHERE id IN :ids;
                -- #}
                -- #{ find
                -- # :id string
                SELECT points FROM TABLE_NAME WHERE id = :id;
                -- #}
                -- #{ drop
                DROP TABLE TABLE_NAME;
                -- #}
                """.replace("DIALECT", dialect).replace("TABLE_NAME", table);
        SqlCatalog catalog = SqlCatalog.parsePsf(psf, dialect);
        try (AsyncSql sql = AsyncSql.builder(jdbc).catalog(catalog)
                .username(System.getenv(prefix + "USER"))
                .password(System.getenv(prefix + "PASSWORD"))
                .workerLimit(2).build()) {
            sql.executeGeneric("create", Map.of()).get(20, TimeUnit.SECONDS);
            try {
                sql.executeInsert("add", Map.of("id", "alpha")).get(20, TimeUnit.SECONDS);
                sql.executeInsert("add", Map.of("id", "beta", "points", 10)).get(20, TimeUnit.SECONDS);
                assertEquals(2, sql.executeChange("many",
                        Map.of("ids", List.of("alpha", "beta"), "delta", 4))
                        .get(20, TimeUnit.SECONDS));
                assertEquals(5, sql.executeSelect("find", Map.of("id", "alpha"))
                        .get(20, TimeUnit.SECONDS).get(0).getLong("points"));
            } finally {
                sql.executeGeneric("drop", Map.of()).get(20, TimeUnit.SECONDS);
            }
        }
    }
}
