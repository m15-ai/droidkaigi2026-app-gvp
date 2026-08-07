package com.m15.gvp.stt

/** Lifecycle of the on-device STT model/engine, surfaced to the UI (see [SherpaSttEngine.status]). */
enum class SttStatus {
    /** Checking whether the ASR model is present. */
    CHECKING,
    /** Downloading the ASR model files. */
    DOWNLOADING,
    /** Model isn't on the device yet — user can tap to download (size varies by model). */
    NEEDS_DOWNLOAD,
    /** Recognizer is loaded and transcribing. */
    READY,
    /** Model present but the recognizer failed to load. */
    UNAVAILABLE
}