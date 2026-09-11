plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.dokka)
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
    sourceSets["test"].java.srcDir("src/test/kotlin")

    // Robolectric runs these on the JVM against real Android framework code
    // for each SDK named in @Config - no emulator. It needs the merged
    // manifest and resources to build an Application.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // No publishing { singleVariant(...) } block here: the publishing plugin
    // configures the release variant, and declaring it twice is an error.
}

kotlin {
    explicitApi()
}

// Robolectric's API 36 sandbox refuses to start on anything older than Java
// 21. The tests run on a 21 toolchain on every machine - downloaded if absent
// - rather than only on CI's JDK 21 leg: an API level tested on half the
// matrix is an API level that silently goes untested on the other half. The
// library's own bytecode target stays 17 (compileOptions above).
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
    )
    // API 36's framework creates shared memory through a FileDescriptor, and
    // Robolectric's stand-in for that reaches into a JDK-internal package.
    // Without this export every API 36 test dies in setup, before any of our
    // code runs. Test JVM only.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
}

mavenPublishing {
    // publishJavadocJar = false, because turning it on makes AGP register its
    // own JavaDocGenerationTask - which runs a Dokka bundled with AGP 8.9 that
    // crashes on Kotlin 2.2 sources, since it still uses the compiler's removed
    // descriptor API. The jar below is built from a current Dokka instead.
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = false,
        ),
    )
}

/**
 * The javadoc jar, built from Dokka's HTML output.
 *
 * The KMP modules get theirs from the publishing plugin automatically; this
 * module needs it wired by hand for the reason above. It contains real
 * documentation rather than the empty stub it briefly shipped, so a consumer's
 * IDE has something to show.
 */
val dokkaJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
    from(tasks.named("dokkaGeneratePublicationHtml"))
}

afterEvaluate {
    publishing.publications.withType<MavenPublication>().configureEach {
        artifact(dokkaJavadocJar)
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
