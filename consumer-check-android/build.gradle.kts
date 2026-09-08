/**
 * An independent Android build that consumes DocumentKit the way a real
 * application would: by coordinates, from a repository, with no reference to
 * the library's source tree.
 *
 * This exists because the JVM consumer-check structurally cannot catch an
 * Android packaging failure. documentkit-android publishes an AAR, and a plain
 * Kotlin/JVM project cannot resolve an AAR at all - so when that module stopped
 * publishing entirely, every job in CI stayed green. Only a build with the
 * Android plugin applied can prove the artifact exists, resolves, and exposes
 * the API it claims to.
 *
 * Compiling is the assertion. If DocumentTransfer cannot be constructed and its
 * suspending operations referenced, this build fails.
 */
plugins {
    id("com.android.library") version "8.9.1"
    id("org.jetbrains.kotlin.android") version "2.2.0"
    // Required of every consumer that defines a @Serializable document model.
    // A library cannot supply a compiler plugin, so this is a real step in the
    // README's Getting started section rather than an implementation detail.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
}

android {
    namespace = "io.github.masterplaycoding.documentkit.consumercheck"
    compileSdk = 36

    defaultConfig {
        // The minimum the library claims to support. If documentkit-android
        // ever raises its own minSdk, this build breaks rather than the claim
        // in the README quietly becoming false.
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    implementation("io.github.masterplaycoding.documentkit:documentkit-android:0.1.0-SNAPSHOT")
    // Note what is absent: kotlinx-coroutines and kotlinx-serialization. Both
    // must arrive transitively through the library's own api dependencies. If
    // either is declared as implementation upstream, this build stops
    // compiling, which is the point.
}
