plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.mavenPublish)
    application
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

application {
    mainClass.set("io.github.masterplaycoding.documentkit.cli.MainKt")
    applicationName = "documentkit"
}

dependencies {
    implementation(project(":documentkit-io"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
