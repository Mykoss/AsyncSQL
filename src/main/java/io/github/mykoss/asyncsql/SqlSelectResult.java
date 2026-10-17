package io.github.mykoss.asyncsql;

import java.util.List;

/** SELECT rows and JDBC column metadata, including for empty result sets. */
public record SqlSelectResult(List<SqlColumnInfo> columnInfo, List<SqlRow> rows) implements SqlResult {
    public SqlSelectResult {
        columnInfo = List.copyOf(columnInfo);
        rows = List.copyOf(rows);
    }
    public List<SqlColumnInfo> getColumnInfo() { return columnInfo; }
    public List<SqlRow> getRows() { return rows; }
}
