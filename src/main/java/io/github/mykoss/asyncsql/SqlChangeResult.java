package io.github.mykoss.asyncsql;

/** Number of affected rows for CHANGE operations. */
public record SqlChangeResult(int affectedRows) implements SqlResult {
    public int getAffectedRows() { return affectedRows; }
}
