rootProject.name = "documentkit-consumer-check-android"

pluginManagement {
    repositories {
        google {
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
        // The build-local repository DocumentKit publishes to, first. This
        // build must resolve the artifacts the library just produced, not a
        // release that happens to share the coordinates.
        maven {
            name = "documentKitTestRepo"
            url = uri("../build/test-repo")
        }
        google()
        mavenCentral()
    }
}
