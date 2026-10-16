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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

/**
 * Thread-safe async JDBC query runner. Completion stages run on worker threads
 * unless consumers explicitly dispatch with thenAcceptAsync(..., mainThreadExecutor).
 */
public final class AsyncSql implements AutoCloseable {
    private final HikariDataSource source;
    private final ExecutorService workers;
    private final Map<String, SqlQuery> queries;
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
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
        this.queries = Map.copyOf(builder.catalog.compiledQueries());

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(builder.jdbcUrl);
        // DriverManager discovers JDBC drivers from the caller/context classloader.
        // Plugin classloaders (Nukkit, Bukkit, etc.) may not expose shaded drivers
        // through Java SPI. Explicit driver class bypasses that discovery path.
        if (builder.driverClassName != null && !builder.driverClassName.isBlank()) {
            config.setDriverClassName(builder.driverClassName);
        }
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
        return submit(query, parameters, SqlQuery.Mode.GENERIC);
    }

    public CompletableFuture<Integer> executeChange(String query, Map<String, ?> parameters) {
        return submit(query, parameters, SqlQuery.Mode.CHANGE);
    }

    public CompletableFuture<InsertResult> executeInsert(String query, Map<String, ?> parameters) {
        return submit(query, parameters, SqlQuery.Mode.INSERT);
    }

    public CompletableFuture<List<SqlRow>> executeSelect(String query, Map<String, ?> parameters) {
        return submit(query, parameters, SqlQuery.Mode.SELECT);
    }

    /** Snapshot of queries accepted but not yet completed, for diagnostics. */
    public int pendingCount() { return pending.size(); }

    /**
     * Returns a future for all work pending at the time this method is called.
     * New submissions after this call are not included.
     */
    public CompletableFuture<Void> awaitPending() {
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]));
    }

    /** Blocking equivalent of awaitPending(); do not call on the Minecraft main thread. */
    public void waitAll() {
        awaitPending().join();
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
        CompletableFuture<T> future = track(new CompletableFuture<>());
        try {
            workers.execute(() -> {
                try {
                    T value;
                    try (Connection connection = source.getConnection()) {
                    connection.setAutoCommit(false);
                    SqlTransaction transaction = new SqlTransaction(connection, queries);
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

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> submit(String name, Map<String, ?> params, SqlQuery.Mode mode) {
        SqlQuery query = queries.get(name);
        if (query == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown SQL query: " + name));
        if (closed.get()) return CompletableFuture.failedFuture(
                new IllegalStateException("AsyncSQL is closed"));
        Map<String, ?> snapshot = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(params, "params")));
        CompletableFuture<T> future = track(new CompletableFuture<>());
        try {
            workers.execute(() -> {
                try {
                    Object value;
                    try (Connection connection = source.getConnection()) {
                        value = query.execute(connection, snapshot, mode);
                    }
                    future.complete((T) value);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException error) {
            future.completeExceptionally(error);
        }
        return future;
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pending.add(future);
        future.whenComplete((value, error) -> pending.remove(future));
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
            if (!workers.isTerminated()) {
                workers.shutdownNow();
                pending.forEach(future -> future.completeExceptionally(
                        new IllegalStateException("AsyncSQL shut down before this query completed")));
            }
            source.close();
        }
    }

    public static final class Builder {
        private String jdbcUrl;
        private String driverClassName;
        private String username;
        private String password;
        private SqlCatalog catalog;
        private int workerLimit = 2;
        private int queueLimit = 256;

        private Builder() {}
        public Builder jdbcUrl(String value) { this.jdbcUrl = value; return this; }
        /** Optional driver class for shaded JDBC drivers in isolated plugin classloaders. */
        public Builder driverClassName(String value) { this.driverClassName = value; return this; }
        public Builder username(String value) { this.username = value; return this; }
        public Builder password(String value) { this.password = value; return this; }
        public Builder catalog(SqlCatalog value) { this.catalog = value; return this; }
        public Builder workerLimit(int value) { this.workerLimit = value; return this; }
        public Builder queueLimit(int value) { this.queueLimit = value; return this; }
        public AsyncSql build() { return new AsyncSql(this); }
    }
}
