plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.m15.gvp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.m15.gvp"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            // Sherpa-ONNX native libs target 64-bit ARM only for v1
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // --- Compose BOM (manages versions for all Compose artifacts) ---
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // DataStore (Preferences) for settings
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room (KSP)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Material Components for XML theme parent
    implementation("com.google.android.material:material:1.12.0")

    // On-device LLM, primary path: MediaPipe LLM Inference (LiteRT). Runs downloaded .task models
    // (Gemma/Qwen/etc.) directly — the same runtime as Google's AI Edge Gallery — and works on this
    // device where AICore's Prompt feature isn't provisioned. https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // On-device LLM, secondary path: Gemini Nano via ML Kit GenAI Prompt API (AICore). Used only if a
    // device has Feature 636 provisioned; otherwise the orchestrator prefers MediaPipe, then a stub.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta2")

    // EXPERIMENT(stt-eval): ML Kit GenAI Speech Recognition (AICore). Advanced mode runs a GenAI ASR
    // model on Pixel 10 (alpha — no SLA). Evaluated as an alternative to Sherpa-ONNX; selectable at
    // runtime via the "ML Kit GenAI STT" settings toggle (see SttRouter / MlKitGenAiSttEngine).
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")

    // On-device STT: Sherpa-ONNX (streaming Zipformer + Silero VAD). Official prebuilt AAR bundles the
    // JNI .so + Kotlin API (com.k2fsa.sherpa.onnx). Vendored from the k2-fsa/sherpa-onnx v1.13.2 release;
    // only the arm64-v8a .so is packaged (see abiFilters). Model files are downloaded at runtime.
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))

    // TODO(real-integration): remaining on-device engine dependency. Kept commented out so the
    // project builds without resolving large model artifacts. Uncomment and verify the version
    // against the latest release when wiring the real STT engine.
    //   Sherpa-ONNX (STT + Silero VAD): bundle the Android AAR from
    //     https://github.com/k2-fsa/sherpa-onnx/releases (e.g. sherpa-onnx-vX.Y.Z-android.aar)
    //   implementation("com.k2fsa.sherpa:sherpa-onnx:<version>")
}

// Room schema export
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}
