plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.example"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("io.github.mykoss:asyncsql:0.1.0-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
}

application {
    mainClass.set("dev.example.smoke.GradleSmoke")
}

tasks.shadowJar {
    mergeServiceFiles()
    relocate("io.github.mykoss.asyncsql", "dev.example.smoke.libs.asyncsql")
    relocate("com.zaxxer.hikari", "dev.example.smoke.libs.hikari")
}
