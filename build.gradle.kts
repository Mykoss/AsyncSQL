plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    implementation("com.zaxxer:HikariCP:6.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("com.mysql:mysql-connector-j:9.2.0")
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.2")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    // Explicit coordinates: settings.gradle.kts uses 'AsyncSQL' with uppercase.
    coordinates("io.github.mykoss", "asyncsql", project.version.toString())

    // Upload to Sonatype Central Portal as USER_MANAGED: the owner must confirm
    // the final 'Publish' action in https://central.sonatype.com/publishing/deployments .
    publishToMavenCentral()

    // CI verification and publishToMavenLocal must work without GPG credentials.
    // Only signing-enabled release jobs supply this property through GitHub Secrets.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("AsyncSQL")
        description.set("Platform-independent asynchronous JDBC library for Java Minecraft server plugins.")
        inceptionYear.set("2026")
        url.set("https://github.com/Mykoss/AsyncSQL")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("Mykoss")
                name.set("Mykoss")
                url.set("https://github.com/Mykoss")
            }
        }
        scm {
            url.set("https://github.com/Mykoss/AsyncSQL")
            connection.set("scm:git:https://github.com/Mykoss/AsyncSQL.git")
            developerConnection.set("scm:git:ssh://git@github.com/Mykoss/AsyncSQL.git")
        }
    }
}
