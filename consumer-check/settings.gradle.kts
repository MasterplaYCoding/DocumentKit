rootProject.name = "documentkit-consumer-check"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // The build-local repository, not mavenLocal. ~/.m2 is shared and never
        // cleaned, so it happily serves a stale artifact from an earlier run -
        // which means a consumer check against it can pass while the current
        // build produces something different, or nothing at all.
        maven {
            name = "documentKitTestRepo"
            url = uri("../build/test-repo")
        }
        mavenCentral()
    }
}
