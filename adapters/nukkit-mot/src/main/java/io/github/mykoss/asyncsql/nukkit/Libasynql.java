package io.github.mykoss.asyncsql.nukkit;

import cn.nukkit.plugin.PluginBase;
import io.github.mykoss.asyncsql.DataConnector;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Nukkit-MOT equivalent of PHP libasynql::create(plugin, config, sqlMap).
 * All callback handlers are dispatched through the server main-thread scheduler.
 * The caller owns the returned connector and MUST close it in onDisable().
 */
public final class Libasynql {
    private Libasynql() {}

    public static DataConnector create(PluginBase plugin, Map<String, ?> config,
                                       Map<String, ?> sqlFiles) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sqlFiles, "sqlFiles");
        Executor mainThread = task -> plugin.getServer().getScheduler().scheduleTask(plugin, task);
        Logger logger = Logger.getLogger("AsyncSQL/" + plugin.getName());
        return io.github.mykoss.asyncsql.Libasynql.create(
                plugin.getDataFolder().toPath(), plugin.getClass(), config, sqlFiles,
                mainThread, logger);
    }
}
