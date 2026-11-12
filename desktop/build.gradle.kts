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
            implementation(compose.desktop.currentOs)
            implementation(compose.material)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }

    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "ec0.desktop.MainKt"
    }
}
