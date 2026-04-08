plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation(libs.discord.ipc)
    runtimeOnly(libs.slf4j.simple)
}

application {
    mainClass = "dev.emin.fleetrichpresence.bridge.MainKt"
}

kotlin {
    jvmToolchain(21)
}
