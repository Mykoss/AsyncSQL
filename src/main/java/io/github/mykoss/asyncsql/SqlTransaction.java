package io.github.mykoss.asyncsql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Connection-bound, synchronous SQL operations inside one AsyncSql transaction.
 * Only valid during the worker's transaction callback; do not pass to other threads.
 */
public final class SqlTransaction {
    private final Connection connection;
    private final Map<String, NamedStatement> queries;
    private final Thread owner;
    private boolean valid = true;

    SqlTransaction(Connection connection, Map<String, NamedStatement> queries) {
        this.connection = connection;
        this.queries = queries;
        this.owner = Thread.currentThread();
    }

    @FunctionalInterface
    public interface Work<T> {
        T execute(SqlTransaction transaction) throws Exception;
    }

    public void executeGeneric(String name, Map<String, ?> params) throws SQLException {
        run(name, params, false, statement -> {
            statement.execute();
            return null;
        });
    }

    public int executeChange(String name, Map<String, ?> params) throws SQLException {
        return run(name, params, false, PreparedStatement::executeUpdate);
    }

    public InsertResult executeInsert(String name, Map<String, ?> params) throws SQLException {
        return run(name, params, true, statement -> {
            int count = statement.executeUpdate();
            long key = -1;
            try (ResultSet generated = statement.getGeneratedKeys()) {
                if (generated != null && generated.next()) key = generated.getLong(1);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Not all JDBC drivers provide generated IDs.
            }
            return new InsertResult(key, count);
        });
    }

    public List<SqlRow> executeSelect(String name, Map<String, ?> params) throws SQLException {
        return run(name, params, false, statement -> {
            List<SqlRow> rows = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                ResultSetMetaData metadata = results.getMetaData();
                while (results.next()) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        values.put(metadata.getColumnLabel(i), results.getObject(i));
                    }
                    rows.add(new SqlRow(values));
                }
            }
            return List.copyOf(rows);
        });
    }

    private <T> T run(String name, Map<String, ?> params, boolean generatedKeys, Operation<T> op)
            throws SQLException {
        checkActive();
        NamedStatement query = queries.get(name);
        if (query == null) throw new IllegalArgumentException("Unknown SQL query: " + name);
        Objects.requireNonNull(params, "params");
        try (PreparedStatement statement = connection.prepareStatement(query.sql(),
                generatedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS)) {
            query.bind(statement, params);
            return op.execute(statement);
        }
    }

    private void checkActive() {
        if (!valid || Thread.currentThread() != owner) {
            throw new IllegalStateException("SQL transaction used outside its active worker callback");
        }
    }

    void invalidate() { valid = false; }

    @FunctionalInterface
    private interface Operation<T> {
        T execute(PreparedStatement statement) throws SQLException;
    }
}
