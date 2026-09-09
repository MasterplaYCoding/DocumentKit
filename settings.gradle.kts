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
