/**
 * Writes one fixed document with a *released* DocumentKit, fetched from Maven
 * Central, for the compatibility corpus in
 * documentkit-io/src/jvmCommonTest/compat-corpus/.
 *
 *   ./gradlew run -PdocumentkitVersion=0.4.0 --args="<output file>"
 *
 * Not part of the main build, on purpose: it must never compile against the
 * source tree, or the corpus would record what the current code writes rather
 * than what users' copies of each release wrote.
 */
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

kotlin {
    jvmToolchain(17)
}

val documentkitVersion = providers.gradleProperty("documentkitVersion").get()

dependencies {
    implementation("io.github.masterplaycoding.documentkit:documentkit-io:$documentkitVersion")
}

application {
    mainClass.set("CompatWriterKt")
}
