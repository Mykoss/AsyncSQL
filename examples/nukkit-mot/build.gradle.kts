plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.mykoss"
version = "0.1.0-beta"

repositories {
    mavenLocal() // CI installs the AsyncSQL library before this demo is compiled.
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("com.nukkit-mot:nukkit-mot:1.26.50-R1")
    implementation("io.github.mykoss:asyncsql:0.1.0-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.shadowJar {
    mergeServiceFiles()
    relocate("io.github.mykoss.asyncsql", "dev.mykoss.asyncsqlbeta.libs.asyncsql")
    relocate("com.zaxxer.hikari", "dev.mykoss.asyncsqlbeta.libs.hikari")
    // Never shade Nukkit itself or relocate org.sqlite (contains native resources).
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
