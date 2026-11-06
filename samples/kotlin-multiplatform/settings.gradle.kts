// Kotlin Multiplatform sample: the shared module asks for the user id link through an
// interface; each app gives it its native Fload SDK (Kotlin on Android, Swift on iOS).
// Build from the repository root: ./gradlew -p samples/kotlin-multiplatform :androidApp:assembleDebug
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
        maven("https://jitpack.io")
    }
}

// The sample builds against this repository's SDK source instead of a published version.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.github.fload-ai:fload-attribution-android")).using(project(":attribution"))
    }
}

rootProject.name = "fload-attribution-kmp-sample"
include(":shared", ":androidApp")
