// The herdr wire protocol, the generated wire types, the event model, the
// Pairing Code decoder, and the `Transport` interface the UI depends on. Pure
// JVM and free of Android and SSH types by design (ADR 0011, ADR 0017), so
// every test here runs with `gradle :herdr:test`.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The shared vectors are repo-level fixtures (plugin/test-vectors/), read
    // through this property so the tests do not guess at the working dir.
    systemProperty("heeler.repoRoot", rootDir.parentFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
