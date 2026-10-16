package io.github.mykoss.asyncsql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Named SQL queries, declared using '-- name: query.id' markers. */
public final class SqlCatalog {
    private final Map<String, String> queries = new LinkedHashMap<>();

    public SqlCatalog register(String name, String sql) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sql, "sql");
        if (name.isBlank() || name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Query name must be nonempty and contain no spaces");
        }
        if (sql.isBlank() || queries.containsKey(name)) {
            throw new IllegalArgumentException("Empty or duplicate query: " + name);
        }
        queries.put(name, sql.trim());
        return this;
    }

    public Map<String, String> asMap() {
        return Collections.unmodifiableMap(queries);
    }

    public static SqlCatalog parse(String content) {
        Objects.requireNonNull(content, "content");
        SqlCatalog catalog = new SqlCatalog();
        String active = null;
        StringBuilder sql = new StringBuilder();
        for (String line : content.split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-- name:")) {
                if (active != null) catalog.register(active, sql.toString());
                active = trimmed.substring("-- name:".length()).trim();
                sql.setLength(0);
            } else if (active != null) {
                sql.append(line).append('\n');
            } else if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                throw new IllegalArgumentException("SQL found before first -- name: declaration");
            }
        }
        if (active != null) catalog.register(active, sql.toString());
        return catalog;
    }

    public static SqlCatalog fromResource(Class<?> type, String path) {
        Objects.requireNonNull(type, "type");
        try (InputStream stream = type.getResourceAsStream(path)) {
            if (stream == null) throw new IllegalArgumentException("SQL resource not found: " + path);
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read SQL resource: " + path, e);
        }
    }
}
