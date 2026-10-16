-- #!sqlite
-- #{ schema
-- #{ init
CREATE TABLE IF NOT EXISTS asyncsql_beta (
    uuid TEXT PRIMARY KEY,
    coins INTEGER NOT NULL DEFAULT 0
);
-- #}
-- #}
-- #{ wallet
-- #{ ensure
-- # :uuid string
INSERT OR IGNORE INTO asyncsql_beta (uuid, coins) VALUES (:uuid, 0);
-- #}
-- #{ increment
-- # :uuid string
-- # :amount int 1
UPDATE asyncsql_beta SET coins = coins + :amount WHERE uuid = :uuid;
-- #}
-- #{ find
-- # :uuid string
SELECT coins FROM asyncsql_beta WHERE uuid = :uuid;
-- #}
-- #}
