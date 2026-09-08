plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "io.github.masterplaycoding.documentkit.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDir("src/main/kotlin")

    // No publishing { singleVariant(...) } block here: the publishing plugin
    // configures the release variant, and declaring it twice is an error.
}

kotlin {
    explicitApi()
}

mavenPublishing {
    // AGP's built-in javadoc task runs a bundled Dokka that crashes on Kotlin
    // 2.2 sources - it still uses the compiler's removed descriptor API - so
    // AGP's javadoc generation is switched off and replaced by the stub below.
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        ),
    )
}

/**
 * An empty javadoc jar.
 *
 * Maven Central requires the artifact to be present; it does not require it to
 * have content, and the KMP modules publish an equally empty one that the
 * publishing plugin generates for them. This keeps all three modules'
 * published shapes identical rather than leaving one silently short an
 * artifact - which is the class of mistake this module has already made once.
 *
 * Real API documentation is 0.2 work, tracked with the Dokka wiring.
 */
val androidJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        artifact(androidJavadocJar)
    }
}

// The publication itself is registered by com.vanniktech.maven.publish, applied
// above. It was previously registered here by hand in an afterEvaluate block,
// because AGP only *prepares* a "release" software component and leaves the
// registration to the build author - a step the Kotlin Multiplatform plugin
// performs automatically for the other two modules, which is why its absence
// here went unnoticed until this module turned out to publish nothing at all.
//
// The plugin does that step for AGP and KMP alike, so all three modules are now
// configured identically and the asymmetry that caused the bug is gone.
// verifyPublishedCoordinates guards the outcome either way.

dependencies {
    api(project(":documentkit-io"))
    // api for the same reason as documentkit-io: DocumentTransfer's constructor
    // takes a CoroutineDispatcher and its operations suspend.
    api(libs.kotlinx.coroutines.core)
}
