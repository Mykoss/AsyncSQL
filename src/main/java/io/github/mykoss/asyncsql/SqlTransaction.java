package io.github.mykoss.asyncsql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Connection-bound, synchronous SQL operations inside one AsyncSql transaction.
 * Only valid during the worker's transaction callback; do not pass to other threads.
 */
public final class SqlTransaction {
    private final Connection connection;
    private final Map<String, SqlQuery> queries;
    private final Thread owner;
    private boolean valid = true;

    SqlTransaction(Connection connection, Map<String, SqlQuery> queries) {
        this.connection = connection;
        this.queries = queries;
        this.owner = Thread.currentThread();
    }

    @FunctionalInterface
    public interface Work<T> {
        T execute(SqlTransaction transaction) throws Exception;
    }

    public void executeGeneric(String name, Map<String, ?> params) throws SQLException {
        run(name, params, SqlQuery.Mode.GENERIC);
    }

    public int executeChange(String name, Map<String, ?> params) throws SQLException {
        return (Integer) run(name, params, SqlQuery.Mode.CHANGE);
    }

    public InsertResult executeInsert(String name, Map<String, ?> params) throws SQLException {
        return (InsertResult) run(name, params, SqlQuery.Mode.INSERT);
    }

    @SuppressWarnings("unchecked")
    public List<SqlRow> executeSelect(String name, Map<String, ?> params) throws SQLException {
        return (List<SqlRow>) run(name, params, SqlQuery.Mode.SELECT);
    }

    private Object run(String name, Map<String, ?> params, SqlQuery.Mode mode) throws SQLException {
        checkActive();
        SqlQuery query = queries.get(name);
        if (query == null) throw new IllegalArgumentException("Unknown SQL query: " + name);
        Objects.requireNonNull(params, "params");
        return query.execute(connection, params, mode);
    }

    private void checkActive() {
        if (!valid || Thread.currentThread() != owner) {
            throw new IllegalStateException("SQL transaction used outside its active worker callback");
        }
    }

    void invalidate() { valid = false; }
}
