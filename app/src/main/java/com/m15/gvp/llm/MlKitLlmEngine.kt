package com.m15.gvp.llm

import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real on-device LLM backed by Gemini Nano via the ML Kit GenAI Prompt API. Streams generated text
 * as [LlmClient.Event.TextDelta]s and a final [LlmClient.Event.TextCompleted], matching the stub's
 * contract so the ViewModel is unchanged.
 *
 * Availability is device-specific (Pixel 8+/9, Galaxy S24, etc.) and the ~1 GB model may need to be
 * downloaded first; [initialize] performs that detection/download and returns whether the real
 * engine is usable. [GeminiNanoEngine] owns the real-vs-stub decision.
 */
class MlKitLlmEngine : LlmClient {

    private val TAG = "GVP.LLM"

    /** Friendly name of the engine's model, shown in the status card when AICore is the active engine. */
    val modelName = "Gemini Nano"

    private var model: GenerativeModel? = null
    private val cancelled = AtomicBoolean(false)

    /** Human-readable AICore status/error from the last [initialize] (for the Setup debug line). */
    @Volatile var detail: String? = null
        private set

    /**
     * Detects feature availability, downloads the model if needed, and warms it up.
     * @return true if the real Gemini Nano engine is ready; false if unavailable on this device.
     */
    suspend fun initialize(onDownloading: () -> Unit): Boolean {
        return try {
            val m = Generation.getClient().also { model = it }
            when (m.checkStatus()) {
                FeatureStatus.AVAILABLE -> {
                    m.warmup()
                    Log.i(TAG, "Gemini Nano available and warmed up")
                    detail = "AICore: AVAILABLE (Feature 636)"
                    true
                }
                FeatureStatus.UNAVAILABLE -> {
                    Log.w(TAG, "Gemini Nano feature unavailable on this device")
                    detail = "AICore: UNAVAILABLE — Prompt feature not supported on this device"
                    false
                }
                else -> {
                    // DOWNLOADABLE / DOWNLOADING — fetch the model, then warm up.
                    Log.i(TAG, "Gemini Nano model downloading…")
                    detail = "AICore: downloading model…"
                    onDownloading()
                    var ok = true
                    m.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadStarted ->
                                Log.i(TAG, "download started (${status.bytesToDownload} bytes)")
                            is DownloadStatus.DownloadFailed -> {
                                ok = false
                                Log.e(TAG, "model download failed", status.e)
                                detail = "AICore: download failed — ${status.e.message}"
                            }
                            is DownloadStatus.DownloadCompleted -> Log.i(TAG, "model download complete")
                            else -> Unit
                        }
                    }
                    if (ok) {
                        m.warmup()
                        detail = "AICore: AVAILABLE after download"
                    }
                    ok
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Gemini Nano init failed", t)
            // Surface the concise AICore reason (e.g. "FEATURE_NOT_FOUND: Feature 636 is not available").
            detail = (t.message ?: t.javaClass.simpleName).substringAfter("error code ").take(140)
            false
        }
    }

    override fun sendUserText(
        text: String,
        history: List<Pair<String, String>>,
        systemMessage: String
    ): Flow<LlmClient.Event> = flow {
        val m = model
        if (m == null) {
            emit(LlmClient.Event.Error(IllegalStateException("Gemini Nano not initialized")))
            return@flow
        }
        cancelled.set(false)

        val prompt = PromptBuilder.build(text, history, systemMessage, ChatTemplate.GENERIC)
        Log.d(TAG, "Gemini Nano ⇢ ${text.take(80)} (${history.size} history msgs, ${prompt.length} chars)")

        val sb = StringBuilder()
        try {
            // Streaming chunks carry the *incremental* new text (confirmed against the ML Kit sample).
            // takeWhile lets barge-in (cancelResponse) stop consuming at the next chunk.
            m.generateContentStream(prompt)
                .takeWhile { !cancelled.get() }
                .collect { resp ->
                    val delta = resp.candidates.firstOrNull()?.text.orEmpty()
                    if (delta.isNotEmpty()) {
                        sb.append(delta)
                        emit(LlmClient.Event.TextDelta(delta))
                    }
                }
            if (!cancelled.get()) {
                Log.i(TAG, "Gemini Nano completed → ${sb.take(80)}")
                emit(LlmClient.Event.TextCompleted(sb.toString()))
            } else {
                Log.d(TAG, "Gemini Nano generation cancelled (barge-in)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Gemini Nano generation error", t)
            emit(LlmClient.Event.Error(t))
        }
    }

    override fun cancelResponse() {
        cancelled.set(true)
    }

    override fun close() {
        runCatching { model?.close() }
        model = null
    }
}