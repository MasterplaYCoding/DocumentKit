import java.util.Properties

/**
 * An independent Gradle build that consumes DocumentKit the way a real user
 * would: by coordinates, from a repository, with no reference to the library's
 * source tree.
 *
 * Building from inside the workspace proves the code compiles. It does not
 * prove the published artifacts are usable - Gradle metadata, target variants,
 * the api/implementation split and transitive dependencies are all invisible
 * until something resolves them from outside.
 */
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

kotlin {
    jvmToolchain(17)
}

/**
 * The version under test is whatever the library build is currently producing.
 *
 * Hard-coding it was a trap: the build-local repository holds exactly the
 * version the last publish produced, so a bump in gradle.properties turns this
 * consumer into a check of an artifact that is no longer there. It resolved
 * only for as long as the two happened to agree, and would have failed the
 * release workflow at the first version that was not 0.1.0-SNAPSHOT.
 */
val documentKitVersion: String = Properties().apply {
    file("../gradle.properties").inputStream().use { load(it) }
}.getProperty("VERSION_NAME")

dependencies {
    implementation("io.github.masterplaycoding.documentkit:documentkit-io:$documentKitVersion")
    // Deliberately the only dependency. This file used to also declare
    // kotlinx-coroutines-core, compensating for the library declaring it as
    // implementation rather than api. Removing that line turns this build into
    // a regression test: the library's own api declarations must carry
    // coroutines and serialization to a consumer, or this stops compiling.
}

application {
    mainClass.set("ConsumerCheckKt")
}
