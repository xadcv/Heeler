// The Android app: Jetpack Compose over the `:ssh` transport. Android 17
// (API 37) is the compile and target SDK; the floor is Android 13 (API 33).
// AGP 9 compiles Kotlin itself, so no `org.jetbrains.kotlin.android` here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.screenshot)
}

android {
    namespace = "dev.bybee.heeler"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.bybee.heeler"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // R8 is deferred until the JSch/BouncyCastle keep rules are written and
            // exercised on a device; a minified build that drops a JSch algorithm
            // class fails only at connect time.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Compose Preview screenshot tests render every screen state host-side
    // (layoutlib), which is how UI changes are verified in CI without a device.
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    packaging {
        // BouncyCastle ships signed jars; the signature files are meaningless inside an APK.
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ssh"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
}
