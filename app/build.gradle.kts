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
            // Martin's own Ruby: things only for him (like the uncensored on-phone model) are listed.
            buildConfigField("boolean", "PERSONAL_BUILD", "true")
            // The emulator on this PC, where the device tests run.
            ndk {
                abiFilters += listOf("x86_64")
            }
        }
        release {
            // A public build: personal-only things are hidden or off.
            buildConfigField("boolean", "PERSONAL_BUILD", "false")
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
    packaging {
        jniLibs {
            // Installed as real files, so the on-phone model server (llama.cpp) can be started.
            useLegacyPackaging = true
        }
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

// Ruby's project knowledge: docs/Ruby_Project_Overview.md goes into the app as an asset
// (knowledge/Ruby_Project_Overview.md), and she keeps it in memory (see ProjectKnowledge).
// Replace the file and build again: she updates her notes, without duplicates.
val knowledgeAssets = layout.buildDirectory.dir("generated/knowledgeAssets")
val copyProjectKnowledge = tasks.register<Copy>("copyProjectKnowledge") {
    from(rootProject.file("docs/Ruby_Project_Overview.md"))
    into(knowledgeAssets.map { it.dir("knowledge") })
}
android.sourceSets.getByName("main").assets.srcDir(knowledgeAssets.get().asFile)
tasks.named("preBuild") { dependsOn(copyProjectKnowledge) }

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
    // Gemini Nano: the AI engine's Free mode, on the phone.
    implementation(libs.genai.prompt)
    // The radio: Android's media player, with a media session for the car's buttons.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // Some stations in Radio Browser stream as HLS.
    implementation(libs.media3.hls)
    // Listening for the wake word, and for the user talking over Ruby: Vosk, an on-device
    // recognizer (Apache 2.0) that takes any typed words, with no training or license.
    implementation(libs.vosk.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}


// On-phone models: llama.cpp's own Android build (its llama-server and libraries), which runs
// GGUF models on the phone. Android only runs programs shipped inside the app, so they go in as
// native libraries (the server renamed to libllama_server_exe.so). The archive is downloaded once
// into third_party/ (not kept in git) and checked against its SHA-256.
val llamaBuild = "b12823"
val llamaSha256 = "e1ed84e9d57a27a4d6bb55a784eadc1d4873e2b3a07b2e97ea6c0fa8735c4b88"
val llamaArchive: File = rootProject.file("third_party/llama-$llamaBuild-bin-android-arm64.tar.gz").also { tar ->
    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    if (!tar.exists() || sha256(tar) != llamaSha256) {
        tar.parentFile.mkdirs()
        val part = File(tar.path + ".part")
        URI("https://github.com/ggml-org/llama.cpp/releases/download/$llamaBuild/${tar.name}")
            .toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
        val got = sha256(part)
        check(got == llamaSha256) {
            part.delete()
            "llama.cpp $llamaBuild: downloaded file's SHA-256 is $got, expected $llamaSha256"
        }
        check(part.renameTo(tar)) { "Couldn't move ${part.name} into place" }
    }
}
val llamaJniLibs = layout.buildDirectory.dir("generated/llamaJniLibs").get().asFile
val unpackLlama = tasks.register<Copy>("unpackLlama") {
    from(tarTree(resources.gzip(llamaArchive))) {
        include(
            "*/llama-server", "*/libllama-server-impl.so", "*/libllama.so", "*/libllama-common.so",
            "*/libggml.so", "*/libggml-base.so", "*/libmtmd.so", "*/libggml-cpu-android_*.so",
        )
        eachFile { path = "arm64-v8a/" + (if (name == "llama-server") "libllama_server_exe.so" else name) }
        includeEmptyDirs = false
    }
    into(llamaJniLibs)
}
android.sourceSets.getByName("main").jniLibs.srcDir(llamaJniLibs)
tasks.named("preBuild") { dependsOn(unpackLlama) }
