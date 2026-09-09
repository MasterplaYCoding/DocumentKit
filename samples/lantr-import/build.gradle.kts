/**
 * Converts a Lantr `.ltrn` presentation into a DocumentKit container.
 *
 * Deliberately *not* published. This is the evidence that the extraction
 * described in PROVENANCE.md was real - it reads the format the original
 * application wrote - and it is not part of the library's supported surface.
 * No consumer should depend on it, so it does not apply the publishing plugin
 * and appears in no release.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.masterplaycoding.documentkit.samples.lantr.MainKt")
    applicationName = "lantr-import"
}

dependencies {
    implementation(project(":documentkit-io"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnitPlatform()
}
