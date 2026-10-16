package io.github.mykoss.asyncsql;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** PSF parameter declaration: scalar/list, optional defaults and strict types. */
final class PsfVariable {
    final String name;
    final String type;
    final boolean list;
    final boolean emptyAllowed;
    final boolean nullable;
    final boolean optional;
    final Object defaultValue;

    private PsfVariable(String name, String type, boolean list, boolean emptyAllowed,
                        boolean nullable, boolean optional, Object defaultValue) {
        this.name = name;
        this.type = type;
        this.list = list;
        this.emptyAllowed = emptyAllowed;
        this.nullable = nullable;
        this.optional = optional;
        this.defaultValue = defaultValue;
    }

    static PsfVariable parse(String name, String rawType, String rawDefault) {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z_][A-Za-z_0-9]*")) {
            throw new IllegalArgumentException("Invalid PSF variable name: " + name);
        }
        String type = Objects.requireNonNull(rawType, "type").toLowerCase(Locale.ROOT);
        boolean list = false, empty = false, nullable = false;
        if (type.startsWith("list:")) {
            list = true;
            type = type.substring(5);
        } else if (type.startsWith("list?")) {
            list = true;
            empty = true;
            type = type.substring(5);
        } else if (type.startsWith("?")) {
            nullable = true;
            type = type.substring(1);
        }
        if (!type.matches("string|int|float|bool|timestamp")) {
            throw new IllegalArgumentException("Unsupported PSF variable type: " + rawType);
        }
        if (list && rawDefault != null) {
            throw new IllegalArgumentException("List variables cannot declare defaults: " + name);
        }
        Object defaultValue = null;
        if (rawDefault != null) {
            String v = rawDefault.trim();
            switch (type) {
                case "string" -> defaultValue = decodeStringDefault(v);
                case "int" -> defaultValue = Long.parseLong(v);
                case "float" -> defaultValue = Double.parseDouble(v);
                case "bool" -> defaultValue = v.equalsIgnoreCase("true")
                        || v.equalsIgnoreCase("on") || v.equalsIgnoreCase("yes") || v.equals("1");
                case "timestamp" -> {
                    if (v.equalsIgnoreCase("NOW")) defaultValue = "NOW";
                    else if (v.equals("0")) defaultValue = 0L;
                    else throw new IllegalArgumentException("Timestamp defaults must be NOW or 0");
                }
                default -> throw new IllegalArgumentException("Invalid PSF type: " + type);
            }
        }
        return new PsfVariable(name, type, list, empty, nullable,
                rawDefault != null, defaultValue);
    }

    Object normalize(Object value) {
        if (value == null) {
            if (!nullable) throw new IllegalArgumentException("PSF variable :" + name + " is not nullable");
            return null;
        }
        return switch (type) {
            case "string" -> {
                if (!(value instanceof String)) throw invalid(value);
                yield value;
            }
            case "int" -> {
                if (!(value instanceof Byte || value instanceof Short || value instanceof Integer ||
                        value instanceof Long)) throw invalid(value);
                yield ((Number) value).longValue();
            }
            case "float" -> {
                if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw invalid(value);
                yield ((Number) value).doubleValue();
            }
            case "bool" -> {
                if (!(value instanceof Boolean b)) throw invalid(value);
                yield b;
            }
            case "timestamp" -> {
                if (value.equals("NOW")) yield Timestamp.from(Instant.now());
                if (!(value instanceof Number n)) throw invalid(value);
                yield Timestamp.from(Instant.ofEpochSecond(n.longValue()));
            }
            default -> throw new IllegalStateException(type);
        };
    }

    private IllegalArgumentException invalid(Object value) {
        return new IllegalArgumentException("Invalid value type for :" + name +
                " (" + type + "): " + value.getClass().getName());
    }

    private static String decodeStringDefault(String value) {
        if (!value.startsWith("\"")) return value;
        if (value.length() < 2 || !value.endsWith("\"")) {
            throw new IllegalArgumentException("Unterminated JSON string default");
        }
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < value.length() - 1; i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i + 1 >= value.length() - 1) throw new IllegalArgumentException("Invalid string escape");
            char next = value.charAt(++i);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 >= value.length() - 1) throw new IllegalArgumentException("Invalid Unicode escape");
                    out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("Unknown JSON string escape: " + next);
            }
        }
        return out.toString();
    }
}
