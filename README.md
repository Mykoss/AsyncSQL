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

## Integración directa en Gradle / Maven

**No declares AsyncSQL en `plugin.yml`.** En Nukkit-MOT y otros servidores, utiliza la dependencia en `build.gradle.kts` o `pom.xml` del plugin y **empaqueta su ejecución** con Shadow o Maven Shade. El artefacto de AsyncSQL no se encuentra todavía en Maven Central, por lo que en desarrollo debes ejecutar `gradle publishToMavenLocal` en la librería, o usar un repositorio Maven que publiques más adelante.

- [Guía completa Gradle + Shadow](docs/GRADLE.md).
- [Guía completa Maven + Shade](docs/MAVEN.md).

El `plugin.yml` conserva exclusivamente los metadatos normales del plugin: `name`, `main`, `version`, `api`, etc. **No agregues `libraries`** para AsyncSQL.

El plugin debe incluir los drivers JDBC que utilice. SQLite necesita preservar sus recursos nativos y registros ServiceLoader; el ejemplo evita relocalizar `org.sqlite` y combina los servicios con Shadow/Shade.

## Limitaciones de esta fase

No implementa todavía el formato PSF completo de libasynql (declaración de dialectos/grupos/tipos), transacciones compuestas ni adaptadores específicos para forks. El formato admitido es únicamente `-- name:`. Las pruebas unitarias usan SQLite; falta validar MySQL/MariaDB con un servidor real.

Licencia: [Apache-2.0](LICENSE).
