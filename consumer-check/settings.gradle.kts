rootProject.name = "documentkit-consumer-check"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // mavenLocal first: this build must resolve the artifacts DocumentKit
        // just published, not a release that happens to share the coordinates.
        mavenLocal()
        mavenCentral()
    }
}
