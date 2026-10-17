package dev.mykoss.asyncsqlbeta;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import io.github.mykoss.asyncsql.AsyncSql;
import io.github.mykoss.asyncsql.DataConnector;
import io.github.mykoss.asyncsql.DataConnectorImpl;
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
            compatible = new DataConnectorImpl(database, "sqlite", mainThread(),
                    java.util.logging.Logger.getLogger("AsyncSQLBetaDemo"));
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
            compatible.executeSelectWithInfo("wallet.find", Map.of("uuid", uuid), info -> {
                if (!player.isOnline()) return;
                player.sendMessage("libasynql-style callback: rows=" + info.getRows().size()
                        + " columns=" + info.getColumnInfo().size()
                        + " on main server thread");
            }, error -> {
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
