plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // The module's own sources contain no @Serializable classes, but its tests
    // define application models, which is exactly how a consumer uses it.
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
    alias(libs.plugins.dokka)
}

kotlin {
    jvmToolchain(17)

    jvm()

    androidTarget {
        publishLibraryVariants("release")
    }

    explicitApi()

    sourceSets {
        // An intermediate source set for code that needs java.util.zip and
        // java.nio but must be shared, unchanged, by the JVM and Android
        // targets. The archive implementation lives here exactly once: the
        // alternative is two copies that drift, which is what the project this
        // was extracted from had.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":documentkit-core"))
                // api, not implementation: DocumentStore's public constructor
                // takes a CoroutineDispatcher, and every I/O entry point is a
                // suspend function. Coroutines are part of this module's ABI,
                // so a consumer cannot use it without them on the compile
                // classpath.
                api(libs.kotlinx.coroutines.core)
            }
        }
        val jvmCommonTest by creating {
            dependsOn(commonTest.get())
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        jvmMain.get().dependsOn(jvmCommonMain)
        jvmTest.get().dependsOn(jvmCommonTest)

        androidMain.get().dependsOn(jvmCommonMain)
    }
}

/**
 * Runs MemoryCeilingTest in a JVM whose heap is far smaller than the asset it
 * streams.
 *
 * A separate task because the heap is the assertion. Under jvmTest's default
 * heap - a quarter of the machine's RAM - the same test passes whether the
 * library streams or buffers, which makes it worse than no test: it would
 * report a guarantee it never checked.
 *
 * 192m against a 512 MiB asset. Comfortable for the rest of the work and
 * impossible to buffer the asset in.
 */
val memoryCeilingTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Streams an asset larger than the heap, to prove nothing buffers it."

    val jvmTest = tasks.named<Test>("jvmTest")
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })

    maxHeapSize = "192m"
    filter { includeTestsMatching("*MemoryCeilingTest") }

    // Reported rather than swallowed: an OutOfMemoryError here is the finding.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}

tasks.named<Test>("jvmTest") {
    // It would pass here for the wrong reason, and take a minute doing it.
    filter { excludeTestsMatching("*MemoryCeilingTest") }
}

tasks.named("check") {
    dependsOn(memoryCeilingTest)
}

android {
    namespace = "io.github.masterplaycoding.documentkit.io"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
