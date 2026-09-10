// The SSH transport: implements `dev.bybee.heeler.herdr.Transport` over the
// mwiede JSch fork (direct-streamlocal channels onto the herdr socket, PTY
// exec for attach). Pure JVM so its suites run without an emulator; the
// end-to-end suite provisions a disposable sshd and fake herdr through
// `android/scripts/run-transport-e2e.sh` and is skipped without them.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    api(project(":herdr"))
    // `api`: JschTransportConfig takes a JSch Identity and Ed25519Identity implements it.
    api(libs.jsch)
    implementation(libs.bouncycastle)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

fun camelToSnake(name: String): String = name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()

tasks.test {
    useJUnitPlatform()
    // Populated by android/scripts/run-transport-e2e.sh; absent in a plain
    // `gradle test`, which makes the real-SSH suite skip itself.
    for (name in listOf("port", "portDenied", "user", "authorizedKeysFile", "hostKeyPublicFile", "homeDir")) {
        val property = "heeler.e2e.$name"
        System.getProperty(property)?.let { systemProperty(property, it) }
        System.getenv("HEELER_E2E_${camelToSnake(name)}")?.let { systemProperty(property, it) }
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}
