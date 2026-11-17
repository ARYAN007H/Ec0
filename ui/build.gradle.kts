plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            // M3 Expressive APIs (MaterialExpressiveTheme, MotionScheme, ButtonGroup…) —
            // decoupled artifact, version pinned independently of the CMP plugin.
            api(libs.compose.material3.expressive)
            api(compose.materialIconsExtended)
            api(libs.kotlinx.coroutines.core)
            // DeviceType/ProtocolType wire codecs live here now (moved from core)
            api(libs.kotlinx.serialization.json)
        }
    }

    jvmToolchain(17)
}

android {
    namespace = "ec0.ui"
    compileSdk = 35
    defaultConfig {
        // Pure Compose UI + serialization — no API-34-only calls; follows the
        // app's Android 9 floor.
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
