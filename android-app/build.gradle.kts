plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "ec0.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ec0.android"
        minSdk = 34 // java.net.http.HttpClient ships on API 34+; A5x runs API 35
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Stable debug keystore committed to the repo: every CI runner signs
    // identically, so `adb install -r` updates work across runs (a fresh
    // runner-generated debug key would break every update with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE). Debug-only — release signing
    // stays separate and is NOT in the repo.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("android-app/debug.keystore")
            storePassword = "ec0debugpass"
            keyAlias = "ec0debug"
            keyPassword = "ec0debugpass"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        // CI speed/robustness over lint purity for v0.1.
        // AGP 8.7.3 lint crashes (IncompatibleClassChangeError in
        // NonNullableMutableLiveDataDetector) against the newer compose classpath that
        // material3 1.10.0-alpha pulls in — revisit lint when AGP is bumped.
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Lint's analyzer crashes (not reports — crashes) against the material3-alpha classpath
// with AGP 8.7.3; the lint tool itself needs the next AGP bump. Same-commit unit tests
// remain the quality gate. Kill every lint* task (lint, lintAnalyze*, lintVital*, lintReport*).
tasks.configureEach { if (name.startsWith("lint")) enabled = false }

dependencies {
    implementation(project(":core"))
    implementation(project(":ui")) // shared full-suite UI (Compose Multiplatform)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.datastore.preferences.core)
    // Android receiver (T14 contract over Ktor CIO — com.sun.net.httpserver is JVM-desktop only)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}
