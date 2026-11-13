plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    // Android target is added once android-app lands (needs local.properties sdk.dir).
    jvm()

    sourceSets {
        commonMain.dependencies {
            // Shared UI-model enums (DeviceType/ProtocolType) moved to :ui
            api(project(":ui"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            // HTTP + sockets for the LocalSend (T5) and KDE Connect (T6) ports
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // Synergy gRPC stubs (protobuf/grpc-kotlin) live in :synergy-proto
            api(project(":synergy-proto"))
        }
    }

    jvmToolchain(17)
}
