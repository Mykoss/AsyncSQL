package io.github.mykoss.asyncsql;

/** generatedId is -1 when the JDBC driver does not provide generated keys. */
public record InsertResult(long generatedId, int affectedRows) {}
