plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    id("io.ktor.plugin") version "3.5.2"
}

group = "com.vibes.quantum"
version = "2.6.0"

repositories { mavenCentral() }

application { mainClass.set("com.vibes.quantum.server.ApplicationKt") }

dependencies {
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-server-default-headers-jvm")
    implementation("io.ktor:ktor-server-body-limit-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("ch.qos.logback:logback-classic:1.6.5")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm")
}

kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
