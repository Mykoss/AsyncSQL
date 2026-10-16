package io.github.mykoss.asyncsql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe async JDBC query runner. Completion stages run on worker threads
 * unless consumers explicitly dispatch with thenAcceptAsync(..., mainThreadExecutor).
 */
public final class AsyncSql implements AutoCloseable {
    private final HikariDataSource source;
    private final ExecutorService workers;
    private final Map<String, NamedStatement> queries;
    private final AtomicBoolean closed = new AtomicBoolean();

    private AsyncSql(Builder builder) {
        if (builder.jdbcUrl == null || builder.jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl is required");
        }
        if (builder.catalog == null || builder.catalog.asMap().isEmpty()) {
            throw new IllegalArgumentException("Register at least one SQL query");
        }
        if (builder.workerLimit < 1 || builder.queueLimit < 1) {
            throw new IllegalArgumentException("Worker and queue limits must be positive");
        }
        Map<String, NamedStatement> compiled = new LinkedHashMap<>();
        builder.catalog.asMap().forEach((name, sql) -> compiled.put(name, new NamedStatement(sql)));
        this.queries = Collections.unmodifiableMap(compiled);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(builder.jdbcUrl);
        if (builder.username != null) config.setUsername(builder.username);
        if (builder.password != null) config.setPassword(builder.password);
        config.setMaximumPoolSize(builder.jdbcUrl.startsWith("jdbc:sqlite:") ? 1 : builder.workerLimit);
        config.setPoolName("AsyncSQL");
        config.setConnectionTimeout(10_000);
        this.source = new HikariDataSource(config);
        this.workers = new ThreadPoolExecutor(builder.workerLimit, builder.workerLimit,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(builder.queueLimit),
                task -> {
                    Thread t = new Thread(task, "AsyncSQL-worker");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static Builder builder(String jdbcUrl) { return new Builder().jdbcUrl(jdbcUrl); }

    public CompletableFuture<Void> executeGeneric(String query, Map<String, ?> parameters) {
        return submit(query, parameters, false, (statement) -> {
            statement.execute();
            return null;
        });
    }

    public CompletableFuture<Integer> executeChange(String query, Map<String, ?> parameters) {
        return submit(query, parameters, false, PreparedStatement::executeUpdate);
    }

    public CompletableFuture<InsertResult> executeInsert(String query, Map<String, ?> parameters) {
        return submit(query, parameters, true, statement -> {
            int changes = statement.executeUpdate();
            long id = -1;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys != null && keys.next()) id = keys.getLong(1);
            } catch (SQLFeatureNotSupportedException ignored) {
                // Some JDBC drivers do not support generated keys.
            }
            return new InsertResult(id, changes);
        });
    }

    public CompletableFuture<List<SqlRow>> executeSelect(String query, Map<String, ?> parameters) {
        return submit(query, parameters, false, statement -> {
            List<SqlRow> rows = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData metadata = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> columns = new LinkedHashMap<>();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columns.put(metadata.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(new SqlRow(columns));
                }
            }
            return List.copyOf(rows);
        });
    }

    /**
     * Performs a group of SQL operations atomically on a single JDBC connection.
     * The transaction callback runs on an AsyncSQL worker, never on the game thread.
     * Do not retain the transaction object or block inside it on other AsyncSQL futures.
     * Any exception (including failed COMMIT) triggers ROLLBACK.
     */
    public <T> CompletableFuture<T> executeTransaction(SqlTransaction.Work<T> work) {
        Objects.requireNonNull(work, "work");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("AsyncSQL is closed"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try (Connection connection = source.getConnection()) {
                    connection.setAutoCommit(false);
                    SqlTransaction transaction = new SqlTransaction(connection, queries);
                    T value;
                    try {
                        value = work.execute(transaction);
                        connection.commit();
                    } catch (Throwable error) {
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackError) {
                            error.addSuppressed(rollbackError);
                        }
                        future.completeExceptionally(error);
                        return;
                    } finally {
                        transaction.invalidate();
                    }
                    future.complete(value);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private <T> CompletableFuture<T> submit(String name, Map<String, ?> params,
                                             boolean generatedKeys, SqlOperation<T> operation) {
        NamedStatement query = queries.get(name);
        if (query == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown SQL query: " + name));
        if (closed.get()) return CompletableFuture.failedFuture(
                new IllegalStateException("AsyncSQL is closed"));
        Map<String, ?> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(params, "params")));
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            workers.execute(() -> {
                try (Connection connection = source.getConnection();
                     PreparedStatement statement = connection.prepareStatement(query.sql(),
                             generatedKeys ? Statement.RETURN_GENERATED_KEYS : Statement.NO_GENERATED_KEYS)) {
                    query.bind(statement, snapshot);
                    future.complete(operation.run(statement));
                } catch (Exception error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        workers.shutdown();
        try {
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
                // Running work may still finish exceptionally once the pool closes.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            source.close();
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T run(PreparedStatement statement) throws SQLException;
    }

    public static final class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private SqlCatalog catalog;
        private int workerLimit = 2;
        private int queueLimit = 256;

        private Builder() {}
        public Builder jdbcUrl(String value) { this.jdbcUrl = value; return this; }
        public Builder username(String value) { this.username = value; return this; }
        public Builder password(String value) { this.password = value; return this; }
        public Builder catalog(SqlCatalog value) { this.catalog = value; return this; }
        public Builder workerLimit(int value) { this.workerLimit = value; return this; }
        public Builder queueLimit(int value) { this.queueLimit = value; return this; }
        public AsyncSql build() { return new AsyncSql(this); }
    }
}
