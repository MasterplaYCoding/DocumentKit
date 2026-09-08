plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidLibrary) apply false
}

allprojects {
    group = "io.github.masterplaycoding.documentkit"
    version = "0.1.0-SNAPSHOT"
}

// Publishing metadata is applied uniformly, so an artifact resolved from a
// repository carries the same identity as one built here.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                pom {
                    name.set("DocumentKit ${project.name}")
                    description.set(
                        "Versioned application documents with structured data and binary assets.",
                    )
                    url.set("https://github.com/MasterplaYCoding/DocumentKit")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    scm {
                        url.set("https://github.com/MasterplaYCoding/DocumentKit")
                    }
                }
            }
        }
    }
}
