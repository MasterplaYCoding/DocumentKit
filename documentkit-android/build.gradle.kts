plugins {
    `maven-publish`
    alias(libs.plugins.androidLibrary)
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

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":documentkit-io"))
    implementation(libs.kotlinx.coroutines.core)
}
