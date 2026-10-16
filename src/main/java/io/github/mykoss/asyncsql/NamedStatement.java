package io.github.mykoss.asyncsql;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compiles named parameters while skipping quoted strings and SQL comments. */
final class NamedStatement {
    private final String sql;
    private final List<String> keys;
    private final List<Integer> offsets;

    NamedStatement(String source) {
        Objects.requireNonNull(source, "source");
        StringBuilder result = new StringBuilder();
        List<String> parameters = new ArrayList<>();
        List<Integer> positions = new ArrayList<>();
        int state = 0; // 0 SQL, 1 single quote, 2 double quote, 3 backtick, 4 line comment, 5 block comment
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (state == 0) {
                if (ch == '\'' || ch == '"' || ch == '`') {
                    state = ch == '\'' ? 1 : ch == '"' ? 2 : 3;
                    result.append(ch);
                } else if (ch == '-' && next == '-') {
                    state = 4;
                    result.append("--");
                    i++;
                } else if (ch == '/' && next == '*') {
                    state = 5;
                    result.append("/*");
                    i++;
                } else if (ch == ':' && next == ':') {
                    result.append("::");
                    i++;
                } else if (ch == ':' && isStart(next)) {
                    int end = i + 2;
                    while (end < source.length() && isPart(source.charAt(end))) end++;
                    parameters.add(source.substring(i + 1, end));
                    positions.add(result.length());
                    result.append('?');
                    i = end - 1;
                } else {
                    result.append(ch);
                }
            } else if (state == 4) {
                result.append(ch);
                if (ch == '\n' || ch == '\r') state = 0;
            } else if (state == 5) {
                if (ch == '*' && next == '/') {
                    result.append("*/");
                    i++;
                    state = 0;
                } else result.append(ch);
            } else {
                result.append(ch);
                char quote = state == 1 ? '\'' : state == 2 ? '"' : '`';
                if (ch == '\\' && i + 1 < source.length()) {
                    result.append(source.charAt(++i));
                } else if (ch == quote && next == quote) {
                    result.append(source.charAt(++i));
                } else if (ch == quote) {
                    state = 0;
                }
            }
        }
        this.sql = result.toString();
        this.keys = List.copyOf(parameters);
        this.offsets = List.copyOf(positions);
    }

    String sql() { return sql; }
    List<String> keys() { return keys; }

    void bind(PreparedStatement statement, Map<String, ?> params) throws SQLException {
        Objects.requireNonNull(params, "params");
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (!params.containsKey(key)) {
                throw new IllegalArgumentException("Missing SQL parameter: " + key);
            }
            Object value = params.get(key);
            if (value instanceof java.util.UUID uuid) value = uuid.toString();
            statement.setObject(i + 1, value);
        }
    }


    /** Expands typed PSF lists into safely bound positional JDBC arguments. */
    Plan plan(Map<String, ?> params, Map<String, PsfVariable> declarations) {
        Objects.requireNonNull(params, "params");
        StringBuilder expanded = new StringBuilder();
        List<Object> values = new ArrayList<>();
        int prev = 0;
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            PsfVariable variable = declarations.get(key);
            Object value;
            if (params.containsKey(key)) value = params.get(key);
            else if (variable != null && variable.optional) value = variable.defaultValue;
            else throw new IllegalArgumentException("Missing SQL parameter: " + key);
            expanded.append(sql, prev, offsets.get(i));
            if (variable != null && variable.list) {
                if (!(value instanceof Collection<?> items)) {
                    throw new IllegalArgumentException("Expected a Collection for PSF list :" + key);
                }
                if (items.isEmpty() && !variable.emptyAllowed) {
                    throw new IllegalArgumentException("Empty PSF list not allowed: " + key);
                }
                expanded.append('(');
                if (items.isEmpty()) expanded.append("NULL"); // IN (NULL) matches no rows
                int pos = 0;
                for (Object item : items) {
                    if (pos++ > 0) expanded.append(',');
                    expanded.append('?');
                    values.add(variable.normalize(item));
                }
                expanded.append(')');
            } else {
                expanded.append('?');
                Object converted = variable == null ? value : variable.normalize(value);
                if (converted instanceof java.util.UUID uuid) converted = uuid.toString();
                values.add(converted);
            }
            prev = offsets.get(i) + 1;
        }
        expanded.append(sql, prev, sql.length());
        return new Plan(expanded.toString(), Collections.unmodifiableList(values));
    }

    record Plan(String sql, List<Object> arguments) {}

    private static boolean isStart(char ch) {
        return ch == '_' || ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z';
    }
    private static boolean isPart(char ch) {
        return isStart(ch) || ch >= '0' && ch <= '9';
    }
}
