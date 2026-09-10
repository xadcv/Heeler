// Root build file: declares every plugin once so module files only `alias(...)`.
// AGP 9 provides Kotlin for Android modules itself (built-in Kotlin); the
// Kotlin JVM plugin below is for the pure-JVM `:herdr` and `:ssh` modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.screenshot) apply false
}
