# Compatibilidad: poggit/libasynql vs AsyncSQL Java

**Objetivo:** reproducir la experiencia funcional principal de libasynql en Java con JDBC y CompletableFuture. No es un port PHP ni una API binariamente equivalente. Estas diferencias deben ser conocidas por los testers.

| Área | libasynql | AsyncSQL beta Java |
| --- | --- | --- |
| Asíncrono | Threads + callbacks / generators | Executor acotado y CompletableFuture |
| Operaciones | GENERIC, CHANGE, INSERT, SELECT | GENERIC, CHANGE, INSERT, SELECT |
| SQLite / MySQL | Drivers PHP nativos | JDBC SQLite, MySQL y MariaDB |
| PSF dialectos | sqlite, mysql | sqlite, mysql; también mariadb |
| PSF grupos | Anidados | Anidados |
| Variables | string/int/float/bool/timestamp | Esos tipos, con validación Java |
| PSF defaults | Sí | Sí |
| PSF listas | list:, list? | Sí, parámetros JDBC expandidos |
| Multiconsulta PSF | Separador `-- #&` | Secuencial en una conexión, último resultado |
| Transacciones | No expuesto como API de sesión Java | executeTransaction con commit/rollback |
| Errores | SqlError y onError PHP | CompletableFuture completado excepcionalmente |
| Retornar al hilo principal | Callbacks integrados a PocketMine | Executor del scheduler explícito |
| waitAll | Bloqueante | awaitPending y waitAll (NO en tick de servidor) |
| Registro de queries | Logger con setLoggingQueries | Sí, via DataConnector (sin exponer valores sensibles) |
| Column metadata | SqlColumnInfo / selectWithInfo | SqlSelectResult + SqlColumnInfo con tipos JDBC portables |
| executeMulti / executeImplRaw | Incluidos | Compatibilidad DataConnector con los resultados por fragmento |
| LoadQueryFile en runtime | Sí | DataConnector.loadQueryFile + loadQuery, valida duplicados |
| Factory desde config.yml | libasynql::create(plugin, config, resources) | Libasynql.create(dataFolder, resourcesClass, config, sqlMap, scheduler, logger), requiere adaptador de hilo principal |
| Opciones SSL MySQL | Clases mysqli propias | Opciones JDBC en la URL/driver |
| Compatibilidad Nukkit-MOT | No (es PHP PocketMine) | Plugin demostración empaquetado, pendiente de prueba in-game |

**Matices importantes**

- El `list?` vacío se convierte en `(NULL)` para no construir SQL inseguro; difiere del comportamiento original basado en marcador aleatorio.
- En la API Java, `executeTransaction` agrupa operaciones atómicas, mientras que las consultas normales PSF se ejecutan secuencialmente (no todas las bases de datos permiten transacciones atómicas sobre DDL).
- Los parámetros se enlazan mediante `PreparedStatement`, no mediante interpolación manual.
- Para que SQLite tenga garantizado un orden secuencial, utiliza `workerLimit(1)` como en el demo.
- Las garantías de la beta deben basarse en las pruebas de CI más los resultados del plugin en un servidor real.
