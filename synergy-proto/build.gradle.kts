plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.protobuf)
}

// Synergy gRPC codegen module — plain JVM so the protobuf plugin applies.
// Protos are the reviewer-verified reconstructions from PR #2 rev.3
// (source of truth: docs/re/protos). Frozen RE artifacts: regenerate by
// editing src/main/proto, CI validates the build.

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.protobuf.kotlin)
    api(libs.grpc.protobuf)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    compileOnly(libs.javax.annotation.api)
}

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
                create("kotlin") // 'java' builtin is registered by default
            }
            plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}
