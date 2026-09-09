plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.dokka) apply false
}

allprojects {
    group = "io.github.masterplaycoding.documentkit"
    version = providers.gradleProperty("VERSION_NAME").get()
}

/** Where verifyPublishedCoordinates publishes to and then reads back. */
val testRepository: Provider<Directory> = layout.buildDirectory.dir("test-repo")

// Publishing metadata is applied uniformly, so an artifact resolved from a
// repository carries the same identity as one built here.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()

            // Signing is required by Maven Central and impossible without a
            // key, so it is enabled only when one is configured. Otherwise an
            // ordinary local build - or CI, which never publishes - would fail
            // on a credential it has no reason to hold.
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            }

            pom {
                name.set("DocumentKit ${project.name}")
                description.set(
                    "Versioned application documents with structured data and binary assets.",
                )
                url.set("https://github.com/MasterplaYCoding/DocumentKit")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("https://opensource.org/licenses/MIT")
                    }
                }
                // Maven Central rejects a POM without developers.
                developers {
                    developer {
                        id.set("MasterplaYCoding")
                        name.set("Matei Ursache")
                        url.set("https://github.com/MasterplaYCoding")
                    }
                }
                scm {
                    url.set("https://github.com/MasterplaYCoding/DocumentKit")
                    connection.set("scm:git:git://github.com/MasterplaYCoding/DocumentKit.git")
                    developerConnection.set(
                        "scm:git:ssh://git@github.com/MasterplaYCoding/DocumentKit.git",
                    )
                }
            }
        }
    }

    // The build-local repository the coordinate check publishes to and reads
    // back. Registered separately from the plugin's own Central target.
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
    // A JVM application module; publishable like the rest.
    "documentkit-cli",
)

/** The modules a release contains. Samples are not among them, by design. */
val publishableModules = listOf(
    ":documentkit-core",
    ":documentkit-io",
    ":documentkit-android",
    ":documentkit-cli",
)

/** Below this, a jar holds no files worth publishing. An empty one is ~22 bytes. */
val minJarBytes = 512L

// Captured outside the task block on purpose. Inside tasks.register the
// receiver is the Task, where `group` is the task's own group ("verification"),
// so reading it there silently looks for artifacts under the wrong path.
val publishedGroupPath: String = project.group.toString().replace('.', '/')
val publishedVersion: String = project.version.toString()

tasks.register("verifyPublishedCoordinates") {
    group = "verification"
    description = "Fails if any expected artifact is absent from the build-local repository."

    // Spelled out rather than derived from subprojects, for the same reason
    // expectedArtifacts is: what a release contains should be a statement, not
    // a consequence. It also keeps the samples out, which are deliberately
    // unpublished, and skips the :samples container project, which has no
    // publishing task at all.
    dependsOn(
        publishableModules.map { "$it:publishAllPublicationsToLocalTestRepoRepository" },
    )

    val repositoryDirectory = testRepository
    val groupPath = publishedGroupPath
    val releaseVersion = publishedVersion
    val expected = expectedArtifacts
    val minimumJarBytes = minJarBytes

    doLast {
        val root = repositoryDirectory.get().asFile
        val missing = mutableListOf<String>()

        for (artifact in expected) {
            val directory = File(root, "$groupPath/$artifact/$releaseVersion")
            val published = directory.listFiles()?.map { it.name }.orEmpty()

            // A POM and Gradle module metadata catch a publication that exists
            // but produced no resolvable variants. Sources and javadoc jars are
            // checked because Maven Central rejects a release without them, and
            // finding that out during an upload is far too late - the Android
            // module was short a javadoc jar for exactly this reason.
            //
            // Matched by prefix and extension rather than by exact name: a
            // Maven repository rewrites SNAPSHOT filenames to unique timestamps
            // (documentkit-io-0.1.0-20260908.085936-1.pom), while a release
            // version keeps the plain form.
            for (suffix in listOf("pom", "module", "-sources.jar", "-javadoc.jar")) {
                val match = published.firstOrNull {
                    it.startsWith("$artifact-") &&
                        it.endsWith(if (suffix.startsWith("-")) suffix else ".$suffix")
                }
                if (match == null) {
                    missing += "$artifact/$releaseVersion/$artifact-*$suffix"
                    continue
                }

                // Presence is not enough for the jars. This module published an
                // *empty* javadoc jar for a while, which satisfied a
                // presence-only check while giving consumers' IDEs nothing at
                // all. An empty jar is about 22 bytes; anything real is orders
                // of magnitude larger.
                if (suffix.endsWith(".jar") && File(directory, match).length() < minimumJarBytes) {
                    missing += "$artifact/$releaseVersion/$match (present but empty)"
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
