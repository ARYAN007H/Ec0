pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ec0"

include(":synergy-proto")
include(":core")
include(":desktop")
include(":android-app")
include(":ui")
