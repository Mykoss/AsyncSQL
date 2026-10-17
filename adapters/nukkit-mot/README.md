# AsyncSQL – adaptador Nukkit-MOT

API familiar para desarrolladores que conocen `poggit/libasynql`. El paquete es Java 21 y usa la dependencia principal `io.github.mykoss:asyncsql`.

Ejemplo en un plugin:

```java
import io.github.mykoss.asyncsql.DataConnector;
import io.github.mykoss.asyncsql.nukkit.Libasynql;

private DataConnector database;

@Override
public void onEnable() {
    database = Libasynql.create(this, Map.of(
        "type", "sqlite",
        "sqlite", Map.of("file", "data.sqlite"),
        "worker-limit", 1
    ), Map.of("sqlite", "db/sqlite.sql"));

    database.executeSelect("player.get", Map.of("name", "Steve"), rows -> {
        // Esta función se ejecuta en el hilo principal de Nukkit-MOT.
        getLogger().info("Rows: " + rows.size());
    }, error -> getLogger().error("Query failed", error));
}

@Override
public void onDisable() {
    if (database != null) database.close();
}
```

Los PSF `db/sqlite.sql` se leen de los recursos del JAR del plugin.
El adaptador requiere la dependencia Java `io.github.mykoss:asyncsql-nukkit-mot`.
Usa Shadow/Maven Shade para empaquetar ambos módulos y el driver JDBC en tu plugin. No copies librerías manualmente a `plugin.yml`.

Esta integración se compila y publica **localmente en GitHub Actions** durante la fase beta. No afirmamos que ya esté disponible desde Maven Central hasta que su publicación se verifique.
