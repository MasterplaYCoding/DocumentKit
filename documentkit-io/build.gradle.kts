plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // The module's own sources contain no @Serializable classes, but its tests
    // define application models, which is exactly how a consumer uses it.
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(17)

    jvm()

    explicitApi()

    sourceSets {
        // An intermediate source set for code that needs java.util.zip and
        // java.nio but must be shared, unchanged, by the JVM and Android
        // targets. Adding androidTarget() later means adding one dependsOn
        // line here, not forking the archive implementation.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":documentkit-core"))
                implementation(libs.kotlinx.coroutines.core)
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
    }
}
