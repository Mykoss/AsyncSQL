package dev.mykoss.asyncsqlbeta;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import io.github.mykoss.asyncsql.AsyncSql;
import io.github.mykoss.asyncsql.DataConnector;
import io.github.mykoss.asyncsql.nukkit.Libasynql;
import io.github.mykoss.asyncsql.SqlCatalog;
import io.github.mykoss.asyncsql.SqlRow;

import java.io.File;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Test plugin for the AsyncSQL beta. Stores one wallet per player in SQLite.
 * All Nukkit calls from query completion handlers go through the server scheduler.
 */
public final class AsyncSqlBetaPlugin extends PluginBase {
    private AsyncSql database;
    private DataConnector compatible;

    @Override
    public void onEnable() {
        File dataFolder = getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs()) {
            getLogger().error("Could not create AsyncSQL demo data directory");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            // Java DriverManager looks in the context/app classloader by default,
            // not necessarily in the plugin's isolated classloader.
            // Force-load the JDBC implementation from this plugin's shaded JAR.
            Class.forName("org.sqlite.JDBC", true, getClass().getClassLoader());
            SqlCatalog queries = SqlCatalog.fromPsfResource(
                    AsyncSqlBetaPlugin.class, "/db/sqlite.sql", "sqlite");
            database = AsyncSql.builder("jdbc:sqlite:" + new File(dataFolder, "wallets.db").getAbsolutePath())
                    .driverClassName("org.sqlite.JDBC")
                    .catalog(queries).workerLimit(1).queueLimit(128).build();
            // Dedicated SQLite file to test the libasynql-style factory/callback API.
            // It cannot interfere with the existing transactional /asqlbeta wallet.
            compatible = Libasynql.create(this, Map.of(
                    "type", "sqlite", "sqlite", Map.of("file", "compat.sqlite"),
                    "worker-limit", 1
            ), Map.of("sqlite", "db/sqlite.sql"));
            compatible.executeGeneric("schema.init", Map.of(),
                    () -> getLogger().info("AsyncSQL compatibility connector initialized"),
                    error -> getLogger().error("Compatibility DB init failed", error));
            database.executeGeneric("schema.init", Map.of())
                    .whenCompleteAsync((ignored, error) -> {
                        if (error != null) getLogger().error("SQLite schema init failed", error);
                        else getLogger().info("AsyncSQL beta database initialized");
                    }, mainThread());
        } catch (Exception error) {
            getLogger().error("AsyncSQL initialization failed", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("asqlbeta")) return false;
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command must be executed in-game.");
            return true;
        }
        if (database == null) {
            player.sendMessage("AsyncSQL is unavailable.");
            return true;
        }
        String uuid = player.getUniqueId().toString();
        if (args.length > 0 && args[0].equalsIgnoreCase("compat")) {
            compatible.executeGeneric("wallet.ensure", Map.of("uuid", uuid), () ->
                    compatible.executeChange("wallet.increment", Map.of("uuid", uuid), count ->
                            compatible.executeSelectWithInfo("wallet.find", Map.of("uuid", uuid), info -> {
                                if (!player.isOnline()) return;
                                long coins = info.getRows().get(0).getLong("coins");
                                player.sendMessage("libasynql Java OK: coins=" + coins
                                        + ", columns=" + info.getColumnInfo().size());
                            }, error -> {
                                if (player.isOnline()) player.sendMessage("SQL error: " + error.getErrorMessage());
                            }), error -> {
                        if (player.isOnline()) player.sendMessage("SQL error: " + error.getErrorMessage());
                    }), error -> {
                if (player.isOnline()) player.sendMessage("SQL error: " + error.getErrorMessage());
            });
            return true;
        }
        player.sendMessage("Testing AsyncSQL asynchronously...");
        database.executeTransaction(tx -> {
            tx.executeGeneric("wallet.ensure", Map.of("uuid", uuid));
            tx.executeChange("wallet.increment", Map.of("uuid", uuid)); // PSF default amount = 1
            SqlRow row = tx.executeSelect("wallet.find", Map.of("uuid", uuid)).get(0);
            return row.getLong("coins");
        }).whenCompleteAsync((coins, error) -> {
            if (!player.isOnline()) return;
            if (error != null) player.sendMessage("AsyncSQL error: " + error.getMessage());
            else player.sendMessage("AsyncSQL OK. Your stored coins: " + coins);
        }, mainThread());
        return true;
    }

    private Executor mainThread() {
        return task -> getServer().getScheduler().scheduleTask(this, task);
    }

    @Override
    public void onDisable() {
        if (compatible != null) compatible.close();
        else if (database != null) database.close();
    }
}
