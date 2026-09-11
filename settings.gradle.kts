rootProject.name = "documentkit"

pluginManagement {
    repositories {
        google {
            // Google's repository is consulted only for the groups it actually
            // publishes, so an unrelated dependency cannot be resolved from it.
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle download a JDK a task asks for and the machine lacks. Used
    // only by documentkit-android's Robolectric tests, which need Java 21 to
    // simulate API 36; everything is still compiled for, and published as,
    // Java 17.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

include(":documentkit-core")
include(":documentkit-io")
include(":documentkit-android")
include(":documentkit-cli")
include(":samples:lantr-import")
