package io.github.mykoss.asyncsql;

/** Auto-increment ID and changed row count for INSERT operations. */
public record SqlInsertResult(long insertId, int affectedRows) implements SqlResult {
    public long getInsertId() { return insertId; }
    public int getAffectedRows() { return affectedRows; }
}
