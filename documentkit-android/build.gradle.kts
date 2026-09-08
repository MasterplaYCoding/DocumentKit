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

// AGP *prepares* a "release" software component; registering a publication for
// it is the build author's job. The Kotlin Multiplatform plugin does that step
// automatically for documentkit-core and documentkit-io, which is why their
// absence here went unnoticed: this module applied maven-publish, configured
// singleVariant above, and then published nothing at all.
//
// afterEvaluate is required rather than stylistic - components["release"] does
// not exist until AGP has finished evaluating the module, so registering it
// eagerly fails.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "documentkit-android"
            }
        }
    }
}

dependencies {
    api(project(":documentkit-io"))
    implementation(libs.kotlinx.coroutines.core)
}
