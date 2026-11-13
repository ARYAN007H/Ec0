plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":core"))
            implementation(project(":ui"))
            implementation(compose.desktop.currentOs)
            implementation(compose.material)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json) // desktop-side DTO codec (T13 discovery)
        }
    }

    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "ec0.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
            )
            packageName = "ec0"
            packageVersion = "1.0.0"
        }
    }
}
