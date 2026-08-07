package com.m15.gvp.llm

/** Lifecycle of the on-device LLM, surfaced to the UI (see [LlmOrchestrator.status]). */
enum class LlmStatus {
    /** Detecting which on-device engine is usable. */
    CHECKING,
    /** A real model is downloaded/available but not yet warmed up. */
    DOWNLOADING,
    /** The LiteRT model isn't on the device yet — user can tap to download it. */
    NEEDS_DOWNLOAD,
    /** A real engine (MediaPipe or Gemini Nano) is warmed up and serving requests. */
    READY,
    /** No real engine is usable — the stub engine is serving requests. */
    UNAVAILABLE
}