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

/** Where verifyPublishedCoordinates publishes to and then reads back. */
val testRepository: Provider<Directory> = layout.buildDirectory.dir("test-repo")

// Publishing metadata is applied uniformly, so an artifact resolved from a
// repository carries the same identity as one built here.
subprojects {
    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            // A build-local repository, deliberately preferred over mavenLocal
            // for verification. ~/.m2 is shared, stateful and never cleaned, so
            // a module that stopped publishing still appears to be there from a
            // previous run - which is exactly how the missing Android
            // publication stayed invisible.
            repositories {
                maven {
                    name = "localTestRepo"
                    url = testRepository.get().asFile.toURI()
                }
            }

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

/**
 * Asserts that every artifact a release is supposed to contain actually exists.
 *
 * The list is hard-coded rather than derived from the projects, and that is the
 * point. A derived list agrees with whatever the build happens to produce, so a
 * module that silently stops publishing - which is what documentkit-android did
 * - still passes. This list is a statement of what a release *is*; if the build
 * disagrees with it, one of the two is wrong and a human decides which.
 */
val expectedArtifacts = listOf(
    // Kotlin Multiplatform modules publish a root module plus one per target.
    "documentkit-core", "documentkit-core-jvm", "documentkit-core-android",
    "documentkit-io", "documentkit-io-jvm", "documentkit-io-android",
    // A plain Android library module. Its publication has to be registered by
    // hand, which is why it is the one that went missing.
    "documentkit-android",
)

// Captured outside the task block on purpose. Inside tasks.register the
// receiver is the Task, where `group` is the task's own group ("verification"),
// so reading it there silently looks for artifacts under the wrong path.
val publishedGroupPath: String = project.group.toString().replace('.', '/')
val publishedVersion: String = project.version.toString()

tasks.register("verifyPublishedCoordinates") {
    group = "verification"
    description = "Fails if any expected artifact is absent from the build-local repository."

    dependsOn(subprojects.map { "${it.path}:publishAllPublicationsToLocalTestRepoRepository" })

    val repositoryDirectory = testRepository
    val groupPath = publishedGroupPath
    val releaseVersion = publishedVersion
    val expected = expectedArtifacts

    doLast {
        val root = repositoryDirectory.get().asFile
        val missing = mutableListOf<String>()

        for (artifact in expected) {
            val directory = File(root, "$groupPath/$artifact/$releaseVersion")
            val published = directory.listFiles()?.map { it.name }.orEmpty()

            // Every Gradle publication produces at least a POM and Gradle
            // module metadata. Checking for both catches a publication that
            // exists but produced no resolvable variants.
            //
            // Matched by prefix and extension rather than by exact name: a
            // Maven repository rewrites SNAPSHOT filenames to unique timestamps
            // (documentkit-io-0.1.0-20260908.085936-1.pom), while a release
            // version keeps the plain form.
            for (suffix in listOf("pom", "module")) {
                val found = published.any {
                    it.startsWith("$artifact-") && it.endsWith(".$suffix")
                }
                if (!found) {
                    missing += "$artifact/$releaseVersion/$artifact-*.$suffix"
                }
            }
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("The release is missing ${missing.size} expected artifact(s):")
                    missing.forEach { appendLine("  - $it") }
                    appendLine()
                    appendLine("Published under $root:")
                    File(root, groupPath).listFiles()?.sorted()?.forEach {
                        appendLine("  - ${it.name}")
                    }
                    appendLine()
                    append(
                        "If a module was removed on purpose, update expectedArtifacts in " +
                            "build.gradle.kts. Otherwise its publication is not registered.",
                    )
                },
            )
        }

        logger.lifecycle("All ${expected.size} expected artifacts are present in $root")
    }
}
