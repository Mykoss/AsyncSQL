# AsyncSQL

Librería **Java** para ejecutar consultas SQL fuera del hilo principal en plugins de servidores Minecraft y otras aplicaciones. Inspirada en el flujo de trabajo de [libasynql](https://github.com/poggit/libasynql), pero implementada desde cero usando JDBC y `CompletableFuture`.

## Estado

**0.1.0-SNAPSHOT — desarrollo.** La publicación en Maven Central está preparada pero la primera versión pública todavía no se ha enviado; no se garantiza compatibilidad con todos los forks. El artefacto no incluye controladores JDBC; añádelos en tu plugin. El código núcleo no importa ninguna API de Nukkit.

## Funciones iniciales

- SQLite, MySQL y MariaDB mediante sus drivers JDBC.
- Pool de conexiones con HikariCP (un máximo de una conexión para SQLite).
- Cuatro modos de consulta: `executeGeneric`, `executeChange`, `executeInsert` y `executeSelect`.
- Consultas `-- name: identifier` y archivos PSF estilo libasynql: dialectos, grupos, variables tipadas, defaults, listas y bloques separados por `-- #&`.
- Parámetros seguros enlazados con JDBC (`PreparedStatement`); se rechazan valores de tipos incompatibles en PSF.
- Cola acotada y cantidad configurable de workers para no bloquear el hilo principal.
- Resultados con `CompletableFuture`; el desarrollador decide en qué hilo ejecutar los callbacks.
- `close()` para terminar los recursos en `onDisable()`.
- `executeTransaction()` con rollback ante errores, y `awaitPending()` para esperar las consultas aceptadas.

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


### Formato PSF para consultas SQLite/MySQL/MariaDB

Además de `-- name:`, AsyncSQL puede leer el estilo de archivos que utiliza libasynql.

Archivo `db/sqlite.sql` dentro de los recursos del plugin:

```sql
-- #!sqlite
-- #{ wallet
-- #{ ensure
-- # :uuid string
-- # :balance int 0
INSERT OR IGNORE INTO wallet (uuid, balance) VALUES (:uuid, :balance);
-- #}
-- #{ find
-- # :uuid string
SELECT balance FROM wallet WHERE uuid = :uuid;
-- #}
-- #}
```

Carga de las consultas:

```java
SqlCatalog catalog = SqlCatalog.fromPsfResource(
        MyPlugin.class, "/db/sqlite.sql", "sqlite");
AsyncSql db = AsyncSql.builder("jdbc:sqlite:plugins/MyPlugin/wallet.db")
        .catalog(catalog).workerLimit(1).build();
db.executeInsert("wallet.ensure", Map.of("uuid", uuid));
```

Para MySQL/MariaDB usa un PSF del dialecto correspondiente y selecciona la URL JDBC adecuada. Para llamadas que actualizan un jugador o mundo, despacha las completaciones al hilo principal. Consulta [el plan de beta y sus limitaciones](docs/BETA_TESTING.md).


## Integración directa en Gradle / Maven

**No declares AsyncSQL en `plugin.yml`.** En Nukkit-MOT y otros servidores, utiliza la dependencia en `build.gradle.kts` o `pom.xml` del plugin y **empaqueta su ejecución** con Shadow o Maven Shade. El artefacto de AsyncSQL no se encuentra todavía en Maven Central, por lo que en desarrollo debes ejecutar `gradle publishToMavenLocal` en la librería, o usar un repositorio Maven que publiques más adelante.

- [Guía completa Gradle + Shadow](docs/GRADLE.md).
- [Guía completa Maven + Shade](docs/MAVEN.md).

El `plugin.yml` conserva exclusivamente los metadatos normales del plugin: `name`, `main`, `version`, `api`, etc. **No agregues `libraries`** para AsyncSQL.

El plugin debe incluir los drivers JDBC que utilice. SQLite necesita preservar sus recursos nativos y registros ServiceLoader; el ejemplo evita relocalizar `org.sqlite` y combina los servicios con Shadow/Shade.

## Estado de compatibilidad

El formato PSF de la beta cubre sus funciones habituales, pero **no reproduce todas las APIs PHP de libasynql**. Faltan adaptaciones específicas de logging, algunas variantes avanzadas de resultados y pruebas reales dentro de Nukkit-MOT. Los tests de MySQL/MariaDB en CI usan instancias reales.

Revisa [la matriz de compatibilidad](docs/COMPATIBILITY.md) y [el plan de pruebas para testers](docs/BETA_TESTING.md). La primera publicación en Maven Central sigue pendiente.

Licencia: [Apache-2.0](LICENSE).


Para desarrolladores de PocketMine/libasynql: [guía de migración a Nukkit-MOT con DataConnector, PSF y callbacks](docs/LIBASYNQL_MIGRATION.md).
