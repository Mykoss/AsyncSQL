package io.github.mykoss.asyncsql;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable row snapshot; SQL NULL values are retained. */
public final class SqlRow {
    private final Map<String, Object> values;

    SqlRow(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Object> values() { return values; }
    public Object get(String name) { return values.get(name); }
    public String getString(String name) {
        Object value = get(name);
        return value == null ? null : value.toString();
    }
    public long getLong(String name) {
        Object value = get(name);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("Column is not numeric: " + name);
        }
        return number.longValue();
    }
}
