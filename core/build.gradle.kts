plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.protobuf)
}

kotlin {
    // Android target is added once android-app lands (needs local.properties sdk.dir).
    jvm()

    sourceSets {
        commonMain.dependencies {
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
            api(libs.protobuf.kotlin)
            implementation(libs.grpc.protobuf)
            implementation(libs.grpc.stub)
            implementation(libs.grpc.kotlin.stub)
            compileOnly(libs.javax.annotation.api)
        }
    }

    jvmToolchain(17)
}

// Synergy gRPC codegen — protos are the reviewer-verified reconstructions in
// core/src/jvmMain/proto (source of truth: docs/re/protos, PR #2 rev.3).
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java")
                create("kotlin")
            }
            plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}
