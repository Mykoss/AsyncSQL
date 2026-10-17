package io.github.mykoss.asyncsql;

import java.util.Map;

/**
 * Structured SQL failure inspired by libasynql's SqlError.
 * The query is the logical statement name, not executable SQL text; values
 * are available for debugging but must never be logged with credentials.
 */
public final class SqlError extends RuntimeException {
    public static final String STAGE_CONNECT = "CONNECT";
    public static final String STAGE_PREPARE = "PREPARE";
    public static final String STAGE_EXECUTE = "EXECUTION";
    public static final String STAGE_RESPONSE = "RESPONSE";

    private final String stage;
    private final String query;
    private final Map<String, ?> args;

    public SqlError(String stage, String query, Map<String, ?> args, Throwable cause) {
        super("SQL " + stage + " error for " + query + ": " + cause.getMessage(), cause);
        this.stage = stage;
        this.query = query;
        this.args = args == null ? null : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(args));
    }

    public String getStage() { return stage; }
    public String getErrorMessage() { return getCause() == null ? getMessage() : getCause().getMessage(); }
    public String getQuery() { return query; }
    public Map<String, ?> getArgs() { return args; }
}
