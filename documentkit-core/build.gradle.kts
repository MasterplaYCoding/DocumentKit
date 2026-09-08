plugins {
    alias(libs.plugins.kotlinMultiplatform)
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

    // Explicit API mode: every public declaration needs an explicit visibility
    // and return type. This is a library, so an accidentally public helper is
    // a compatibility commitment nobody meant to make.
    explicitApi()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: DocumentMigration exposes JsonObject in
            // its public signature, so consumers need the dependency too.
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "io.github.masterplaycoding.documentkit"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
