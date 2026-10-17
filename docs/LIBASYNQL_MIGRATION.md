# De libasynql PHP a AsyncSQL Java (Nukkit-MOT)

El objetivo es conservar los nombres y la semántica de las consultas, utilizando los mismos archivos PSF y callbacks en el hilo principal. Java requiere tipos propios y CompletableFuture en lugar de generadores PHP.

## Ejemplo de creación en el plugin

```java
import io.github.mykoss.asyncsql.DataConnector;
import io.github.mykoss.asyncsql.nukkit.Libasynql;

private DataConnector db;

@Override public void onEnable() {
    db = Libasynql.create(this, Map.of(
        "type", "sqlite", "sqlite", Map.of("file", "data.sqlite"),
        "worker-limit", 1
    ), Map.of("sqlite", "db/sqlite.sql", "mysql", "db/mysql.sql"));
}

void load(String name) {
    db.executeSelect("player.get", Map.of("name", name), rows -> {
        // Se ejecuta desde el scheduler del hilo principal de Nukkit-MOT.
        getLogger().info("Rows: " + rows.size());
    }, error -> getLogger().error("SQL error", error));
}

@Override public void onDisable() { if (db != null) db.close(); }
```

## Mapeo de API

| PHP libasynql | AsyncSQL Java |
|---|---|
| `libasynql::create(plugin, config, sqlMap)` | `io.github.mykoss.asyncsql.nukkit.Libasynql.create(plugin, config, sqlMap)` |
| `DataConnector` | `DataConnector` |
| `executeGeneric`, `executeChange`, `executeInsert`, `executeSelect` | Mismos nombres y callbacks equivalentes |
| `asyncGeneric/Change/Insert/Select` | Mismos nombres, devuelven `CompletableFuture` |
| `executeMulti`, `executeImplRaw` | Mismos nombres, modos GENERIC=0, CHANGE=1, INSERT=2, SELECT=3 |
| `asyncSelectWithInfo` | Devuelve `CompletableFuture<SqlSelectResult>` |
| `loadQueryFile`, `loadQuery` | Carga PSF y nombre+SQL en tiempo de ejecución |
| `setLoggingQueries`, `setLogger`, `waitAll`, `close` | Nombres conservados |
| `SqlError` | Incluye stage, query, args y causa |

## Prueba in-game

Ejecuta `/asqlbeta` para la transacción que ya validaste y `/asqlbeta compat` para la API nueva. El segundo comando utiliza una base `compat.sqlite` separada y callbacks equivalentes a libasynql; debe aumentar su contador después de cada ejecución y conservarlo tras un reinicio. Los mensajes salen desde el hilo principal.

## Diferencias que permanecen

- Java no puede consumir directamente un virion PHP ni sus generadores AwaitGenerator. Se utilizan interfaces Java y `CompletableFuture`.
- Los tipos devueltos provienen de JDBC, no de conversiones PHP/mysqli. Timestamp y algunos booleanos/metadatos pueden diferir.
- `loadQuery` recibe `(name, sql)` y no un objeto `GenericStatement` de PHP.
- `list?` vacío usa `(NULL)` con parámetros JDBC, una semántica ligeramente distinta.
- Esta API se ha probado automáticamente, pero debe superar las pruebas de aceptación Nukkit-MOT antes de declarar equivalencia funcional completa.
- El adaptador de Nukkit todavía no se ha publicado en Maven Central; la compilación está disponible en los artefactos de GitHub Actions.
