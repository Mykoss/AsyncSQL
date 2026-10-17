# AsyncSQL: plan de pruebas de la beta Java

Esta beta es una **implementación nueva de Java/JDBC inspirada en libasynql**. No es un port PHP ni promete equivalencia binaria. Se compila con Java 17; el plugin de prueba de Nukkit-MOT utiliza Java 21.

## Cobertura funcional

- [x] Cuatro modos: GENERIC, CHANGE, INSERT, SELECT.
- [x] SQLite, MySQL y MariaDB (pruebas CI con bases de datos reales).
- [x] Consultas asíncronas con CompletableFuture y pool HikariCP.
- [x] Transacciones con commit/rollback explícitos.
- [x] Formato simple legacy: `-- name:`.
- [x] PSF estilo libasynql: `-- #!sqlite`/`-- #!mysql`, grupos anidados, `-- #{` y `-- #}`, `-- #:name type`, defaults, documentación `-- #*` y delimitador `-- #&`.
- [x] Listas `list:` y `list?` (en JDBC se expande de forma segura a `(?,...)`; vacío opcional = `(NULL)`).
- [x] Tipos PSF: string, int, float, bool y timestamp; null con prefijo `?`.
- [x] Recuento de tareas pendientes y futuro `awaitPending()`.
- [x] Ejemplos Gradle Shadow y Maven Shade ejecutables.
- [ ] El ejemplo de Nukkit-MOT se compila en GitHub Actions, pero debe probarse manualmente en un servidor.
- [ ] APIs PHP-specific como generadores Await, callbacks con interfaz PHP y métodos internos de PocketMine no se copian de forma literal.
- [ ] Todavía no hay métricas de carga ni estrés con cientos de jugadores, ni una garantía de compatibilidad con todos los forks.
- [ ] La semántica de PSF `list?` difiere del valor aleatorio del backend MySQL de libasynql; se utiliza `(NULL)` intencionalmente.
- [ ] PSF no soporta todas las extensiones posibles de libasynql (p. ej. instrucciones avanzadas específicas de mysql/mysqli) y no hay garantías de compatibilidad uno a uno.

## Prueba manual en Nukkit-MOT

1. Descarga el artefacto `asyncsql-nukkit-demo` del workflow de GitHub Actions (JAR con dependencias).
2. Instala un servidor Nukkit-MOT compatible con Java 21, versión de referencia 1.26.50-R1.
3. Copia el JAR de prueba en `plugins/`. No coloques AsyncSQL como librería separada ni modifiques `plugin.yml`.
4. Reinicia el servidor y comprueba el mensaje `AsyncSQL beta database initialized`.
5. Entra con un jugador y ejecuta `/asqlbeta` tres veces. El contador persistido debería pasar de 1 a 2 y luego a 3.
6. Reinicia el servidor, vuelve a ejecutar el comando y comprueba que el siguiente número sea 4.
7. Comprueba que otros jugadores tengan su propio contador y que el hilo principal siga respondiendo.
8. Realiza el test de reinicio durante varias consultas y recoge logs si aparecen excepciones.
9. Ejecuta `/asqlbeta compat` tres veces: muestra 1, 2 y 3 monedas en una base `compat.sqlite` independiente. Reinicia y repite: debe mostrar 4. Los callbacks deben atender al jugador desde el hilo principal; confirma que no hay errores.
10. Si aparece `Failed to get driver instance for jdbcUrl=jdbc:sqlite:`, instala el JAR corregido de este demo: registra explícitamente `org.sqlite.JDBC` en el classloader del plugin, y proporciona `driverClassName("org.sqlite.JDBC")` a HikariCP. Si persiste, entrega la excepción completa (incluidos `Caused by`) de Nukkit-MOT para diagnosticar la carga nativa o recursos.
11. Para MySQL/MariaDB hay pruebas CI; comprueba manualmente también tu configuración real de hosting y credenciales.

## Cómo consumirla en un plugin

- Durante el desarrollo, compila la librería con `gradle publishToMavenLocal` y usa `implementation("io.github.mykoss:asyncsql:0.1.0-SNAPSHOT")` en el plugin.
- Genera un JAR con Shadow/Shade que incluya HikariCP, AsyncSQL y tu driver JDBC. No incluyas Nukkit.
- Usa `SqlCatalog.fromPsfResource(MyPlugin.class, "/db/sqlite.sql", "sqlite")`.
- Para resultados en el juego usa `thenAcceptAsync(..., task -> scheduler.scheduleTask(plugin, task))`.

## Criterio para declarar lista la beta

CI en `main` verde, JAR de plugin de ejemplo compilado, al menos un arranque y ejecución en Nukkit-MOT sin errores, y logs de aceptación. No publiques como 1.0.0 hasta cerrar los defectos encontrados por testers.
