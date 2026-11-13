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
        // CI speed/robustness over lint purity for v0.1
        abortOnError = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui")) // shared full-suite UI (Compose Multiplatform)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.activity.compose)
}
