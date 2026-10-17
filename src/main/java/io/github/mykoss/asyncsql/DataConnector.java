package io.github.mykoss.asyncsql;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Logger;

/**
 * Java-facing analogue of the public libasynql DataConnector contract.
 *
 * Success/error callbacks run on the executor given to Libasynql.create.
 * For Nukkit-MOT, this MUST schedule tasks on the main server thread.
 * async* methods return CompletableFuture instead of PHP generators.
 */
public interface DataConnector extends AutoCloseable {
    int MODE_GENERIC = 0;
    int MODE_CHANGE = 1;
    int MODE_INSERT = 2;
    int MODE_SELECT = 3;

    void setLoggingQueries(boolean enabled);
    boolean isLoggingQueries();
    void setLogger(Logger logger);
    Logger getLogger();

    /** Load a PSF file. The stream is closed by this method. */
    void loadQueryFile(InputStream resource);
    /** Load an individual named statement while the connection is open. */
    void loadQuery(String name, String sql);

    void executeGeneric(String name, Map<String, ?> args,
                        Runnable onSuccess, Consumer<SqlError> onError);
    void executeChange(String name, Map<String, ?> args,
                       IntConsumer onSuccess, Consumer<SqlError> onError);
    void executeInsert(String name, Map<String, ?> args,
                       BiConsumer<Long, Integer> onInserted, Consumer<SqlError> onError);
    void executeSelect(String name, Map<String, ?> args,
                       Consumer<List<SqlRow>> onSuccess, Consumer<SqlError> onError);
    void executeSelectWithInfo(String name, Map<String, ?> args,
                               Consumer<SqlSelectResult> onSuccess, Consumer<SqlError> onError);

    /**
     * Execute PSF fragments and receive each result in declaration order.
     * mode is the mode of the final fragment; earlier fragments are GENERIC.
     */
    void executeMulti(String name, Map<String, ?> args, int mode,
                      Consumer<List<Object>> handler, Consumer<SqlError> onError);

    /** Internal-style raw API; parameterized SQL, one mode per statement. */
    void executeImplRaw(List<String> sql, List<Map<String, ?>> args, List<Integer> modes,
                        Consumer<List<Object>> handler, Consumer<SqlError> onError);

    CompletableFuture<Void> asyncGeneric(String name, Map<String, ?> args);
    CompletableFuture<Integer> asyncChange(String name, Map<String, ?> args);
    CompletableFuture<InsertResult> asyncInsert(String name, Map<String, ?> args);
    CompletableFuture<List<SqlRow>> asyncSelect(String name, Map<String, ?> args);
    CompletableFuture<SqlSelectResult> asyncSelectWithInfo(String name, Map<String, ?> args);

    CompletableFuture<Void> awaitPending();
    void waitAll();

    default void executeGeneric(String name, Map<String, ?> args) {
        executeGeneric(name, args, null, null);
    }
    default void executeChange(String name, Map<String, ?> args) {
        executeChange(name, args, null, null);
    }
    default void executeInsert(String name, Map<String, ?> args) {
        executeInsert(name, args, null, null);
    }
    default void executeSelect(String name, Map<String, ?> args) {
        executeSelect(name, args, null, null);
    }

    @Override void close();
}
