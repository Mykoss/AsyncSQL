# AsyncSQL con Gradle (sin `plugin.yml` libraries)

AsyncSQL se incluye **directamente en el JAR de tu plugin** por medio de Shadow. No requiere ningún cambio en el software Nukkit-MOT.

> Esta versión no está publicada en Maven Central. Instala AsyncSQL localmente con `gradle publishToMavenLocal`, desde el repositorio AsyncSQL, antes de compilar el plugin. `mavenLocal()` es solo para desarrollo; cuando se publique una release central bastará `mavenCentral()`.

## Ejemplo: `build.gradle.kts` de tu plugin

```kotlin
plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.example"
version = "1.0.0"

repositories {
    mavenLocal() // eliminar tras la publicación en Maven Central
    mavenCentral()
}

dependencies {
    compileOnly("com.nukkit-mot:nukkit-mot:1.26.50-R1")
    implementation("io.github.mykoss:asyncsql:0.1.0-SNAPSHOT")

    // Solo incluye el controlador que use tu plugin.
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    // implementación alternativa para MySQL: com.mysql:mysql-connector-j:9.2.0
    // implementación alternativa para MariaDB: org.mariadb.jdbc:mariadb-java-client:3.5.2
}

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles() // JDBC usa META-INF/services/java.sql.Driver
    relocate("io.github.mykoss.asyncsql", "dev.example.plugin.libs.asyncsql")
    relocate("com.zaxxer.hikari", "dev.example.plugin.libs.hikari")
    // NO relocalizar org.sqlite: incluye recursos nativos y el driver JDBC.
}
tasks.build {
    dependsOn(tasks.shadowJar)
}
```

Tu `plugin.yml` solo necesita la configuración normal:

```yaml
name: MyPlugin
main: dev.example.plugin.MyPlugin
version: "1.0.0"
api: ["1.0.0"]
```

Construye con `gradle clean shadowJar` y usa el JAR de `build/libs/`. Asegúrate de que el archivo generado es el de Shadow y no el JAR simple.

## Advertencias

- No uses `compileOnly` para AsyncSQL: eso no empaqueta la librería.
- No incluyas Nukkit-MOT dentro del JAR: mantenlo como `compileOnly`.
- Evita relocalizar `org.sqlite`, `com.mysql.cj` o `org.mariadb.jdbc` sin pruebas de JDBC, servicios y recursos.
- La librería utiliza SLF4J transitivamente vía HikariCP; evita añadir implementaciones de logger duplicadas si el servidor ya proporciona una.
- Para que el código del plugin interactúe con jugadores o mundos, devuelve el resultado al hilo principal usando `thenAcceptAsync(..., mainThreadExecutor)`.
- Esta guía documenta el empaquetado, pero todavía falta una prueba completa de instalación en servidor Nukkit-MOT.
