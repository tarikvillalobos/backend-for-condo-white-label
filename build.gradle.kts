plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "com.community"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.0"

dependencies {
    implementation(platform("io.ktor:ktor-bom:$ktorVersion"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-id")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-body-limit")
    implementation("ch.qos.logback:logback-classic:1.6.4")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.flywaydb:flyway-core:13.8.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.8.0")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.h2database:h2:2.5.250")
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.community.api.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
