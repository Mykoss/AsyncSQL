plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.mykoss"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

dependencies {
    api("io.github.mykoss:asyncsql:0.1.0-SNAPSHOT")
    compileOnly("com.nukkit-mot:nukkit-mot:1.26.50-R1") {
        isTransitive = false
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "asyncsql-nukkit-mot"
        }
    }
}
