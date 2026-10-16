# Publicar AsyncSQL en Maven Central

Este repositorio utiliza el plugin `com.vanniktech.maven.publish` y el **Central Portal** de Sonatype, no OSSRH (servicio descontinuado). El artefacto es `io.github.mykoss:asyncsql:<version>`.

## Requisitos ya configurados por el propietario

- Namespace verificado: `io.github.mykoss`.
- Clave GPG pública publicada con ID `01ED4D942DD0A180`.
- Cuatro secretos privados en GitHub Actions: `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`.

No incluir tokens, contraseñas o claves privadas en Git, en issues o en logs.

## Preparación de la primera versión

1. Confirma que los tests de la rama `main` pasen en [GitHub Actions](https://github.com/Mykoss/AsyncSQL/actions).
2. Revisa la API y el changelog. `0.1.0` es una primera versión pública; no se considera `1.0.0` estable.
3. En GitHub abre **Actions → Publish to Maven Central (manual approval) → Run workflow**.
4. Selecciona la rama `main`, escribe `0.1.0` en el campo `version` y ejecuta el workflow.
5. El workflow compila, corre las pruebas locales, verifica secretos y firma/sube la publicación a Sonatype. **No hace publicación automática**.
6. Ve a [Central Portal Deployments](https://central.sonatype.com/publishing/deployments), espera a que el deployment figure como `VALIDATED`, revisa archivos y pulsa **Publish**. Una vez publicado **no puede reemplazarse el mismo número de versión**.
7. Espera la propagación a Maven Central (puede tomar hasta 30 minutos) y valida su presencia en [Maven Central Search](https://central.sonatype.com/search?q=io.github.mykoss%3Aasyncsql). Entonces crea el release/tag `v0.1.0` en GitHub desde el commit correspondiente de `main`, si aún no existe.

En el caso de un despliegue fallido, lee los errores en Central Portal antes de reintentar. Si se creó un deployment de una versión ya publicada, no vuelvas a publicar esas mismas coordenadas.

## Credenciales y firma

Sonatype usa un token de usuario con **username y password** propios, no la contraseña de GitHub.

El workflow transforma los secretos únicamente en variables de entorno del paso de publicación:

- `CENTRAL_USERNAME` → `ORG_GRADLE_PROJECT_mavenCentralUsername`
- `CENTRAL_PASSWORD` → `ORG_GRADLE_PROJECT_mavenCentralPassword`
- `SIGNING_KEY` → `ORG_GRADLE_PROJECT_signingInMemoryKey`
- `SIGNING_PASSWORD` → `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`

La clave privada se carga **en memoria** en el proceso Gradle. No se escribe en el repositorio. Si cambias de clave de firma, también revisa el ID de clave en `.github/workflows/publish-central.yml`.

El workflow usa `publishToMavenCentral` para subir una publicación **USER_MANAGED**. Nunca se ejecuta como consecuencia de un `push`, un tag o un PR; hay que iniciarlo manualmente en `main`.

## Desarrollo local

```bash
gradle clean build publishToMavenLocal
```

En compilaciones normales no se requieren secretos ni firmas. La firma se activa solo cuando se proporciona la propiedad Gradle `signingInMemoryKey`.

## Consumo de la versión pública

Gradle:

```kotlin
repositories { mavenCentral() }
dependencies { implementation("io.github.mykoss:asyncsql:0.1.0") }
```

Maven:

```xml
<dependency>
  <groupId>io.github.mykoss</groupId>
  <artifactId>asyncsql</artifactId>
  <version>0.1.0</version>
</dependency>
```

Para los plugins de Minecraft, empaqueta AsyncSQL y su driver JDBC junto con tu plugin (Gradle Shadow o Maven Shade). **No se utiliza `plugin.yml` con `libraries`**.

Documentación: https://vanniktech.github.io/gradle-maven-publish-plugin/central/ y https://central.sonatype.org/publish/.
