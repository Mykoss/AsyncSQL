# AsyncSQL con Maven (sin `plugin.yml` libraries)

AsyncSQL se empaqueta con **Maven Shade** dentro del JAR del plugin. El plugin no debe declarar `libraries` en `plugin.yml`.

> Esta versión todavía no está publicada en Maven Central. Ejecuta `gradle publishToMavenLocal` desde el proyecto AsyncSQL para instalar primero la librería en tu repositorio local de Maven.

Ejemplo para agregar al `pom.xml` de tu plugin:

```xml
<dependencies>
    <dependency>
        <groupId>com.nukkit-mot</groupId>
        <artifactId>nukkit-mot</artifactId>
        <version>1.26.50-R1</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>io.github.mykoss</groupId>
        <artifactId>asyncsql</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </dependency>
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>3.49.1.0</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.6.0</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals><goal>shade</goal></goals>
                    <configuration>
                        <createDependencyReducedPom>false</createDependencyReducedPom>
                        <relocations>
                            <relocation>
                                <pattern>io.github.mykoss.asyncsql</pattern>
                                <shadedPattern>dev.example.plugin.libs.asyncsql</shadedPattern>
                            </relocation>
                            <relocation>
                                <pattern>com.zaxxer.hikari</pattern>
                                <shadedPattern>dev.example.plugin.libs.hikari</shadedPattern>
                            </relocation>
                        </relocations>
                        <transformers>
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                        </transformers>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Compila con `mvn clean package` y usa el JAR final generado por Shade. Reemplaza el paquete `dev.example.plugin` por el de tu plugin.

**Importante:** Nukkit-MOT debe seguir siendo `provided`, y no debe aparecer `libraries:` en el `plugin.yml`. SQLite necesita sus recursos nativos y el registro `META-INF/services/java.sql.Driver`; no relocalices `org.sqlite` sin comprobar que el driver continúa funcionando. Si usas MySQL o MariaDB, sustituye la dependencia SQLite por el driver JDBC correspondiente.

La instalación real en Nukkit-MOT y otros forks requiere pruebas adicionales de classloaders y carga del driver JDBC antes de declararla compatible en producción.
