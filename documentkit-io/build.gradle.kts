plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // The module's own sources contain no @Serializable classes, but its tests
    // define application models, which is exactly how a consumer uses it.
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.mavenPublish)
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
