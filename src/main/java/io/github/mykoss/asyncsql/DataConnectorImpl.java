package io.github.mykoss.asyncsql;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** DataConnector compatibility facade; never runs plugin callbacks on JDBC workers. */
public final class DataConnectorImpl implements DataConnector {
    private final AsyncSql sql;
    private final String dialect;
    private final Executor callbackExecutor;
    private volatile Logger logger;
    private volatile boolean loggingQueries;

    public DataConnectorImpl(AsyncSql sql, String dialect, Executor callbackExecutor, Logger logger) {
        this.sql = Objects.requireNonNull(sql, "sql");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.logger = logger;
    }

    @Override public void setLoggingQueries(boolean enabled) { loggingQueries = enabled; }
    @Override public boolean isLoggingQueries() { return loggingQueries; }
    @Override public void setLogger(Logger logger) { this.logger = logger; }
    @Override public Logger getLogger() { return logger; }

    @Override public void loadQueryFile(InputStream resource) {
        Objects.requireNonNull(resource, "resource");
        try (resource) {
            sql.loadQueryFile(new String(resource.readAllBytes(), StandardCharsets.UTF_8), dialect);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read PSF resource", e);
        }
    }
    @Override public void loadQuery(String name, String statement) {
        sql.loadQuery(name, statement);
    }

    private void trace(String name) {
        Logger log = logger;
        if (loggingQueries && log != null) {
            log.fine(() -> "AsyncSQL queued query " + name); // Do not log passwords or parameters.
        }
    }

    private static String nameOf(int mode) {
        return switch (mode) {
            case MODE_GENERIC -> "GENERIC";
            case MODE_CHANGE -> "CHANGE";
            case MODE_INSERT -> "INSERT";
            case MODE_SELECT -> "SELECT";
            default -> throw new IllegalArgumentException("Unknown query mode: " + mode);
        };
    }

    private <T> CompletableFuture<T> invoke(String name, Map<String, ?> args,
                                             java.util.function.Supplier<CompletableFuture<T>> supplier) {
        trace(name);
        return supplier.get();
    }

    private <T> void handle(String name, Map<String, ?> args, CompletableFuture<T> future,
                            Consumer<T> success, Consumer<SqlError> onError) {
        future.whenComplete((value, failure) -> {
            try {
                callbackExecutor.execute(() -> {
                    try {
                        if (failure == null) {
                            if (success != null) success.accept(value);
                        } else {
                            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                    ? failure.getCause() : failure;
                            SqlError error = cause instanceof SqlError e ? e : new SqlError(
                                    cause instanceof java.sql.SQLTransientConnectionException
                                            ? SqlError.STAGE_CONNECT : SqlError.STAGE_EXECUTE,
                                    name, args, cause);
                            if (onError != null) onError.accept(error);
                            else if (logger != null) logger.log(Level.SEVERE, error.getMessage(), error);
                        }
                    } catch (Throwable callbackError) {
                        Logger log = logger;
                        if (log != null) log.log(Level.SEVERE, "SQL completion callback failed", callbackError);
                    }
                });
            } catch (RuntimeException schedulerError) {
                Logger log = logger;
                if (log != null) log.log(Level.SEVERE, "SQL callback could not be scheduled", schedulerError);
            }
        });
    }

    @Override public void executeGeneric(String name, Map<String, ?> args,
                                         Runnable success, Consumer<SqlError> onError) {
        handle(name, args, asyncGeneric(name, args), v -> { if (success != null) success.run(); }, onError);
    }
    @Override public void executeChange(String name, Map<String, ?> args,
                                        IntConsumer success, Consumer<SqlError> onError) {
        handle(name, args, asyncChange(name, args),
                v -> { if (success != null) success.accept(v); }, onError);
    }
    @Override public void executeInsert(String name, Map<String, ?> args,
                                        BiConsumer<Long, Integer> success, Consumer<SqlError> onError) {
        handle(name, args, asyncInsert(name, args),
                v -> { if (success != null) success.accept(v.generatedId(), v.affectedRows()); }, onError);
    }
    @Override public void executeSelect(String name, Map<String, ?> args,
                                        Consumer<List<SqlRow>> success, Consumer<SqlError> onError) {
        handle(name, args, asyncSelect(name, args), success, onError);
    }
    @Override public void executeSelectWithInfo(String name, Map<String, ?> args,
                                                Consumer<SqlSelectResult> success, Consumer<SqlError> onError) {
        handle(name, args, asyncSelectWithInfo(name, args), success, onError);
    }
    @Override public void executeMulti(String name, Map<String, ?> args, int mode,
                                       Consumer<List<Object>> handler, Consumer<SqlError> onError) {
        trace(name);
        handle(name, args, sql.executeMulti(name, args, List.of(nameOf(mode))), handler, onError);
    }
    @Override public void executeImplRaw(List<String> statements, List<Map<String, ?>> args,
                                          List<Integer> modes, Consumer<List<Object>> handler,
                                          Consumer<SqlError> onError) {
        trace("<raw>");
        handle("<raw>", Map.of(), sql.executeImplRaw(statements, args,
                modes.stream().map(DataConnectorImpl::nameOf).toList()), handler, onError);
    }

    @Override public CompletableFuture<Void> asyncGeneric(String name, Map<String, ?> args) {
        return invoke(name, args, () -> sql.executeGeneric(name, args));
    }
    @Override public CompletableFuture<Integer> asyncChange(String name, Map<String, ?> args) {
        return invoke(name, args, () -> sql.executeChange(name, args));
    }
    @Override public CompletableFuture<InsertResult> asyncInsert(String name, Map<String, ?> args) {
        return invoke(name, args, () -> sql.executeInsert(name, args));
    }
    @Override public CompletableFuture<List<SqlRow>> asyncSelect(String name, Map<String, ?> args) {
        return invoke(name, args, () -> sql.executeSelect(name, args));
    }
    @Override public CompletableFuture<SqlSelectResult> asyncSelectWithInfo(
            String name, Map<String, ?> args) {
        return invoke(name, args, () -> sql.executeSelectWithInfo(name, args));
    }
    @Override public CompletableFuture<Void> awaitPending() { return sql.awaitPending(); }
    @Override public void waitAll() { sql.waitAll(); }
    @Override public void close() { sql.close(); }
}
