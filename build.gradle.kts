plugins {
    `java-library`
    `maven-publish`
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation("com.zaxxer:HikariCP:6.3.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.49.1.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "asyncsql"
            pom {
                name.set("AsyncSQL")
                description.set("A platform-independent asynchronous JDBC library for Java game server plugins.")
                url.set("https://github.com/Mykoss/AsyncSQL")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                scm {
                    url.set("https://github.com/Mykoss/AsyncSQL")
                    connection.set("scm:git:https://github.com/Mykoss/AsyncSQL.git")
                }
            }
        }
    }
}
