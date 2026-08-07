package com.m15.gvp.llm

/**
 * User-selectable on-device inference pipeline. Controls which engine [LlmOrchestrator.warmUp]
 * is allowed to bind:
 *
 *  - [AUTO] — prefer the selected MediaPipe/LiteRT model, fall back to AICore Gemini Nano, then
 *    offer a download. The historical default; the right choice on most devices.
 *  - [MEDIAPIPE] — force the LiteRT (MediaPipe) path with the selected model; never touch AICore.
 *  - [AICORE] — force Gemini Nano via AICore (Feature 636). Needs a provisioned device (Tensor G5
 *    Pixel etc.); otherwise the LLM reports unavailable rather than silently dropping to MediaPipe.
 */
enum class PipelineMode(val label: String) {
    AUTO("Auto"),
    MEDIAPIPE("MediaPipe"),
    AICORE("AICore");

    companion object {
        /** Parse a persisted name back to a mode, defaulting to [AUTO] for unknown/null values. */
        fun fromName(name: String?): PipelineMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}