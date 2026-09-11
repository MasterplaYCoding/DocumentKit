rootProject.name = "documentkit-compat-writer"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Maven Central only: the point is to run the binaries that were actually
    // released, not anything built from this tree.
    repositories {
        mavenCentral()
    }
}
