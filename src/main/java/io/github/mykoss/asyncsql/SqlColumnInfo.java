package io.github.mykoss.asyncsql;

/** Column name and portable type labels from the JDBC result metadata. */
public record SqlColumnInfo(String name, String type) {
    public static final String TYPE_STRING = "string";
    public static final String TYPE_INT = "int";
    public static final String TYPE_FLOAT = "float";
    public static final String TYPE_TIMESTAMP = "timestamp";
    public static final String TYPE_BOOL = "bool";
    public static final String TYPE_NULL = "null";
    public static final String TYPE_OTHER = "unknown";
    public String getName() { return name; }
    public String getType() { return type; }
}
