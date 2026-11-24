import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// The Kokoro voice runs on sherpa-onnx, whose Android library (a 50 MB .aar) is published
// only on GitHub. It isn't kept in git: the build downloads this exact release once into
// third_party/ and refuses it unless its SHA-256 matches the one GitHub lists for it.
// v2.0.1 is pinned on purpose: newer releases were only days old when this was set up.
val sherpaOnnxVersion = "2.0.1"
val sherpaOnnxSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaOnnxAar: File = rootProject.file("third_party/sherpa-onnx-$sherpaOnnxVersion.aar").also { aar ->
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    if (!aar.exists() || sha256(aar) != sherpaOnnxSha256) {
        aar.parentFile.mkdirs()
        val part = File(aar.path + ".part")
        URI(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
        ).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val got = sha256(part)
        check(got == sherpaOnnxSha256) {
            part.delete()
            "sherpa-onnx $sherpaOnnxVersion: downloaded file's SHA-256 is $got, expected $sherpaOnnxSha256"
        }
        check(part.renameTo(aar)) { "Couldn't move ${part.name} into place" }
    }
}

android {
    namespace = "com.martin.ruby"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.martin.ruby"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // sherpa-onnx ships native code (about 32 MB each) for four processor types. Phones
        // are arm64; debug builds also carry x86_64 for the emulator on this PC (see below).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // The emulator on this PC, where the device tests run.
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            proguardFiles("proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG hides the storage limit's testing tools in release builds.
        buildConfig = true
    }
}

// Room saves each version of the memory database layout here, so upgrades can be checked.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Device tests read the saved schemas, to build an old version of the memory database and upgrade it.
android.sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // Phase 6: the encrypted memory database.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    // Phase 6: authorizing backups to the user's Google Drive (drive.appdata only).
    implementation(libs.play.services.auth)
    // Phase 6: the nightly backup while charging on Wi-Fi.
    implementation(libs.androidx.work.runtime.ktx)
    // Phase 8 step 9: the Kokoro voice, on the phone (sherpa-onnx, downloaded above), and
    // unpacking its model download (.tar.bz2).
    implementation(files(sherpaOnnxAar))
    implementation(libs.commons.compress)
    // The morning briefing's sky: Moon and planets worked out on the phone (MIT license, offline).
    implementation(libs.astronomy)
    // Chatting with Claude through Anthropic's API.
    implementation(libs.anthropic.java)
    testImplementation(libs.junit)
    // Android's org.json, for reading weather and Wikipedia answers in unit tests.
    testImplementation(libs.org.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // Gemini Nano (the AI engine's Free mode). Debug builds only until Free mode is built.
    debugImplementation(libs.genai.prompt)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
