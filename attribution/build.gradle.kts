import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.dokka")
    id("com.vanniktech.maven.publish")
}

// A release is a git tag: CI and JitPack build it with -Pversion=<tag>.
val publishedVersion = project.version.toString()
    .takeIf { it != "unspecified" } ?: rootProject.file("VERSION").readText().trim()

// JitPack (which sets JITPACK=true) keeps serving the coordinates it always has,
// com.github.fload-ai:fload-attribution-android. Maven Central gets com.fload:attribution.
val onJitPack = System.getenv("JITPACK") == "true"

android {
    namespace = "com.fload.attribution"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = TestExceptionFormat.FULL
                showStandardStreams = true
            }
        }
    }
}

mavenPublishing {
    // The release variant with a sources jar and a javadoc jar (built by Dokka,
    // which the plugin picks up because it is applied).
    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        ),
    )

    if (onJitPack) {
        coordinates("com.github.fload-ai", "fload-attribution-android", publishedVersion)
    } else {
        coordinates("com.fload", "attribution", publishedVersion)
        // Uploads to the Sonatype Central Portal. Needs mavenCentralUsername and
        // mavenCentralPassword (a Central Portal user token) as Gradle properties.
        // automaticRelease = true: the portal releases the upload once it validates. The release
        // gate is the approval of the maven-central environment in publish.yml, given per version.
        publishToMavenCentral(automaticRelease = true)
        // Maven Central requires signed artifacts. Signs only when a key is configured
        // (signingInMemoryKey and signingInMemoryKeyPassword), so local builds need none.
        if (project.hasProperty("signingInMemoryKey")) {
            signAllPublications()
        }
    }

    pom {
        name.set("Fload Attribution for Android")
        description.set(
            "Counts an Android app's installs in Fload and attributes them with Google Play's " +
                "Install Referrer. No advertising ID, no extra permission prompt.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/fload-ai/fload-attribution-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("fload")
                name.set("Fload")
                url.set("https://fload.com")
            }
        }
        scm {
            url.set("https://github.com/fload-ai/fload-attribution-android")
            connection.set("scm:git:https://github.com/fload-ai/fload-attribution-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/fload-ai/fload-attribution-android.git")
        }
    }
}

// The version an install reports (SdkInfo.VERSION) must be the version being published.
val verifySdkVersion by tasks.registering {
    group = "verification"
    description = "Fails when SdkInfo.VERSION differs from the published version."
    val source = file("src/main/kotlin/com/fload/attribution/core/Model.kt")
    val expected = publishedVersion
    inputs.file(source)
    doLast {
        val declared = Regex("""const val VERSION: String = "([^"]+)"""")
            .find(source.readText())?.groupValues?.get(1)
        check(declared == expected) {
            "SdkInfo.VERSION is \"$declared\" but the version being published is \"$expected\". " +
                "Set it in ${source.relativeTo(rootDir)} first."
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

dependencies {
    // Google's Play Install Referrer API: the only runtime dependency.
    implementation("com.android.installreferrer:installreferrer:2.2")
    testImplementation("junit:junit:4.13.2")
}
