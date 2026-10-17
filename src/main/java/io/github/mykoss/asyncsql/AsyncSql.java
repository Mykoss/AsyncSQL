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
    private final Map<String, SqlQuery> queries = new ConcurrentHashMap<>();
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
        this.queries.putAll(builder.catalog.compiledQueries());

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

    /** Load additional PSF or simple query statements at runtime. Duplicate names fail. */
    public synchronized void loadQueries(SqlCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        if (closed.get()) throw new IllegalStateException("AsyncSQL is closed");
        Map<String, SqlQuery> extra = catalog.compiledQueries();
        for (String name : extra.keySet()) {
            if (queries.containsKey(name)) throw new IllegalArgumentException("Duplicate SQL query: " + name);
        }
        queries.putAll(extra);
    }

    public void loadQuery(String name, String sql) {
        loadQueries(new SqlCatalog().register(name, sql));
    }

    public void loadQueryFile(String psfContent, String dialect) {
        loadQueries(SqlCatalog.parsePsf(psfContent, dialect));
    }

    public CompletableFuture<SqlSelectResult> executeSelectWithInfo(String query, Map<String, ?> parameters) {
        return submit(query, parameters, SqlQuery.Mode.SELECT_INFO);
    }

    /** Execute every PSF fragment and retrieve all results in declaration order. */
    public CompletableFuture<List<Object>> executeMulti(String name, Map<String, ?> args,
                                                          List<String> modes) {
        SqlQuery query = queries.get(name);
        if (query == null) return CompletableFuture.failedFuture(
                new IllegalArgumentException("Unknown SQL query: " + name));
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("AsyncSQL is closed"));
        List<SqlQuery.Mode> parsed = modes.stream().map(mode ->
                SqlQuery.Mode.valueOf(mode.toUpperCase(java.util.Locale.ROOT))).toList();
        Map<String, ?> parameters = Collections.unmodifiableMap(
                new LinkedHashMap<>(Objects.requireNonNull(args, "args")));
        CompletableFuture<List<Object>> future = track(new CompletableFuture<>());
        try {
            workers.execute(() -> {
                try (Connection connection = source.getConnection()) {
                    future.complete(query.executeMulti(connection, parameters, parsed));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    /**
     * Run raw JDBC statements without adding them to the public catalog.
     * Mode list must match the number of statements. Each statement is a
     * parameterized single SQL operation, never string-concatenated values.
     */
    public CompletableFuture<List<Object>> executeImplRaw(List<String> sql, List<Map<String, ?>> params,
                                                            List<String> modes) {
        if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("AsyncSQL is closed"));
        if (sql.isEmpty() || sql.size() != params.size() || sql.size() != modes.size()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Expected matching non-empty SQL, parameter and mode lists"));
        }
        List<SqlQuery> queriesSnapshot = sql.stream()
                .map(s -> new SqlQuery(List.of(s), Map.of())).toList();
        List<SqlQuery.Mode> parsed = modes.stream()
                .map(m -> SqlQuery.Mode.valueOf(m.toUpperCase(java.util.Locale.ROOT))).toList();
        List<Map<String, ?>> values = new ArrayList<>();
        for (Map<String, ?> parameterSet : params) {
            values.add(Collections.unmodifiableMap(new LinkedHashMap<>(parameterSet)));
        }
        CompletableFuture<List<Object>> future = track(new CompletableFuture<>());
        try {
            workers.execute(() -> {
                try (Connection connection = source.getConnection()) {
                    List<Object> results = new ArrayList<>();
                    for (int i = 0; i < queriesSnapshot.size(); i++) {
                        results.add(queriesSnapshot.get(i).execute(connection, values.get(i), parsed.get(i)));
                    }
                    future.complete(Collections.unmodifiableList(results));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
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
