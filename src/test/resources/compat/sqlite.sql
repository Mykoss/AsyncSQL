-- #!sqlite
-- #{ compat
-- #{ schema
CREATE TABLE IF NOT EXISTS compat (id TEXT PRIMARY KEY, score INTEGER);
-- #}
-- #{ add
-- # :id string
-- # :score int 3
INSERT INTO compat(id, score) VALUES (:id, :score);
-- #}
-- #{ find
-- # :id string
SELECT id, score FROM compat WHERE id = :id;
-- #}
-- #{ change
-- # :id string
UPDATE compat SET score = score + 1 WHERE id = :id;
-- #}
-- #{ two
-- # :id string
UPDATE compat SET score = score + 1 WHERE id = :id;
-- #&
SELECT score FROM compat WHERE id = :id;
-- #}
-- #}
