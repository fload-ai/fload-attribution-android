plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            // Fload's Android SDK, called from androidMain. (Maven Central, once published:
            // com.fload:attribution.) The iOS app adds the Swift package itself.
            implementation("com.github.fload-ai:fload-attribution-android:1.0.1")
        }
    }
}

android {
    namespace = "com.fload.samples.kmp.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
