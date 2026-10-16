# AsyncSQL

Librería **Java** para ejecutar consultas SQL fuera del hilo principal en plugins de servidores Minecraft y otras aplicaciones. Inspirada en el flujo de trabajo de [libasynql](https://github.com/poggit/libasynql), pero implementada desde cero usando JDBC y `CompletableFuture`.

## Estado

**0.1.0-SNAPSHOT — desarrollo.** No se ha publicado en Maven Central ni se garantiza compatibilidad con todos los forks. El artefacto no incluye controladores JDBC; añádelos en tu plugin. El código núcleo no importa ninguna API de Nukkit.

## Funciones iniciales

- SQLite, MySQL y MariaDB mediante sus drivers JDBC.
- Pool de conexiones con HikariCP (un máximo de una conexión para SQLite).
- Cuatro modos de consulta: `executeGeneric`, `executeChange`, `executeInsert` y `executeSelect`.
- Archivo de consultas `-- name: identifier` y parámetros seguros como `:uuid`.
- Cola acotada y cantidad configurable de workers para no bloquear el hilo principal.
- Resultados con `CompletableFuture`; el desarrollador decide en qué hilo ejecutar los callbacks.
- `close()` para terminar los recursos en `onDisable()`.

## Compilación

JDK 17 o superior y Gradle 8.14.3:

```bash
gradle clean build
gradle publishToMavenLocal
```

Hasta su publicación puedes consumir el artefacto local usando `mavenLocal()` (Gradle) o Maven `~/.m2`.

### Gradle (plugin consumidor)

```kotlin
repositories {
    mavenLocal() // solo durante el desarrollo
    mavenCentral()
}

dependencies {
    implementation("io.github.mykoss:asyncsql:0.1.0-SNAPSHOT")
    runtimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    // Para MySQL: runtimeOnly("com.mysql:mysql-connector-j:9.2.0")
    // Para MariaDB: runtimeOnly("org.mariadb.jdbc:mariadb-java-client:<version>")
}
```

### Maven (plugin consumidor)

```xml
<dependency>
  <groupId>io.github.mykoss</groupId>
  <artifactId>asyncsql</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
  <groupId>org.xerial</groupId>
  <artifactId>sqlite-jdbc</artifactId>
  <version>3.49.1.0</version>
  <scope>runtime</scope>
</dependency>
```

## Uso

Archivo `src/main/resources/players.sql`:

```sql
-- name: create
CREATE TABLE IF NOT EXISTS players (uuid TEXT PRIMARY KEY, coins INTEGER NOT NULL DEFAULT 0);

-- name: find
SELECT uuid, coins FROM players WHERE uuid = :uuid;

-- name: add
UPDATE players SET coins = coins + :amount WHERE uuid = :uuid;
```

Código Java:

```java
SqlCatalog queries = SqlCatalog.fromResource(MyPlugin.class, "/players.sql");
AsyncSql db = AsyncSql.builder("jdbc:sqlite:plugins/MyPlugin/players.db")
        .catalog(queries)
        .workerLimit(1)
        .build();

db.executeGeneric("create", Map.of())
        .exceptionally(error -> { error.printStackTrace(); return null; });

db.executeSelect("find", Map.of("uuid", player.getUniqueId().toString()))
        .thenAcceptAsync(rows -> {
            // El acceso a jugadores / mundos debe ocurrir en el hilo principal.
            player.sendMessage("Filas: " + rows.size());
        }, command -> server.getScheduler().scheduleTask(plugin, command));

// En el método onDisable del plugin:
db.close();
```

En Nukkit-MOT, adapta la referencia `server` y `plugin` a tus objetos de servidor/plugin. En otros forks, proporciona el `Executor` adecuado para el scheduler del servidor.

## Distribución en servidores

**Gradle/Maven resuelven dependencias durante la compilación, pero no las instalan automáticamente en el servidor.** En Nukkit-MOT puede usarse su cargador de librerías Maven de `plugin.yml` si sus reglas de resolución y aislamiento son adecuadas. Para forks sin cargador utiliza un JAR sombreado con Shadow (relocalizando paquetes cuando corresponda). Añade asimismo el driver JDBC necesario. Antes de publicar en Maven Central, el artefacto mostrado arriba es solo una coordenada de desarrollo.

## Limitaciones de esta fase

No implementa todavía el formato PSF completo de libasynql (declaración de dialectos/grupos/tipos), transacciones compuestas ni adaptadores específicos para forks. El formato admitido es únicamente `-- name:`. Las pruebas unitarias usan SQLite; falta validar MySQL/MariaDB con un servidor real.

Licencia: [Apache-2.0](LICENSE).
