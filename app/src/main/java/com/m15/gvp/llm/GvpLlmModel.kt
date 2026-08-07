package com.m15.gvp.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference.Backend
import java.io.File

/**
 * A selectable on-device LiteRT model run by [MediaPipeLlmEngine]. The [CATALOG] mirrors the model
 * families from Google's AI Edge Gallery / mediapipe-samples. Each model is a single `.task` file
 * fetched from Hugging Face (or adb-pushed to `/data/local/tmp`).
 *
 * [authGated] models (Gemma) require accepting Google's license on Hugging Face; the in-app download
 * then needs a personal HF access token (see [ModelDownloader] and `GvpPrefs.hfToken`). Non-gated
 * models (Qwen, Phi) download anonymously. A gated model can also be adb-pushed without a token.
 *
 * Sampling/backend/max-tokens live per-spec so each model can be tuned independently. All ship on
 * [Backend.CPU] — the known-good config from mediapipe-samples.
 */
data class LlmModelSpec(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    /** Approximate download size in MB, for the picker. The real size comes from the HTTP headers. */
    val approxSizeMb: Int,
    val authGated: Boolean,
    /** Native chat template — instruction-tuned models misbehave without their own turn tokens. */
    val template: ChatTemplate,
    val backend: Backend = Backend.CPU,
    val maxTokens: Int = 1024,
    // Sampling — tightened from the sample defaults for concise, on-topic voice replies.
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
) {
    /** Where the in-app download writes this model (app-private storage). */
    fun downloadDest(context: Context): File = File(context.filesDir, fileName)

    /**
     * Resolve an existing model file: prefer the in-app download location, then an adb-pushed copy at
     * `/data/local/tmp/<file>` (dev escape hatch, matches mediapipe-samples). Returns null if neither
     * exists, in which case the model needs downloading.
     */
    fun resolve(context: Context): File? {
        val downloaded = downloadDest(context)
        if (downloaded.exists() && downloaded.length() > 0) return downloaded
        val pushed = File("/data/local/tmp", fileName)
        if (pushed.exists() && pushed.length() > 0) return pushed
        return null
    }

    fun isDownloaded(context: Context): Boolean = resolve(context) != null
}

/**
 * The LiteRT model catalog and selection helpers. Add a model by appending a [LlmModelSpec] to
 * [CATALOG]; the Settings picker and download flow pick it up automatically.
 */
object GvpLlmModel {

    private const val HF = "https://huggingface.co"

    private fun litertUrl(repo: String, file: String) = "$HF/litert-community/$repo/resolve/main/$file"

    /** Default — small (~0.5 GB), fast, and non-gated so the in-app download works without a token. */
    val QWEN_0_5B = LlmModelSpec(
        id = "qwen2.5-0.5b-q8",
        displayName = "Qwen2.5-0.5B-Instruct (q8)",
        fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        url = litertUrl("Qwen2.5-0.5B-Instruct", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"),
        approxSizeMb = 530,
        authGated = false,
        template = ChatTemplate.CHATML,
    )

    /** 3× the params of the 0.5B — noticeably more coherent, still non-gated. */
    val QWEN_1_5B = LlmModelSpec(
        id = "qwen2.5-1.5b-q8",
        displayName = "Qwen2.5-1.5B-Instruct (q8)",
        fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        url = litertUrl("Qwen2.5-1.5B-Instruct", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"),
        approxSizeMb = 1650,
        authGated = false,
        template = ChatTemplate.CHATML,
    )

    /** Strongest reasoning here (~3.8B params) but a large download / RAM footprint. Non-gated. */
    val PHI_4_MINI = LlmModelSpec(
        id = "phi-4-mini-q8",
        displayName = "Phi-4-mini-instruct (q8)",
        fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
        url = litertUrl("Phi-4-mini-instruct", "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task"),
        approxSizeMb = 4100,
        authGated = false,
        template = ChatTemplate.PHI,
    )

    /** Google's open-weight Gemma. **Gated**: needs HF license acceptance + a token (or adb-push). */
    val GEMMA3_1B = LlmModelSpec(
        id = "gemma3-1b-q8",
        displayName = "Gemma3-1B-IT (q8)",
        fileName = "Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task",
        url = litertUrl("Gemma3-1B-IT", "Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task"),
        approxSizeMb = 1000,
        authGated = true,
        template = ChatTemplate.GEMMA,
    )

    val CATALOG: List<LlmModelSpec> = listOf(QWEN_0_5B, QWEN_1_5B, PHI_4_MINI, GEMMA3_1B)

    val DEFAULT: LlmModelSpec = QWEN_0_5B

    /** Look up a spec by persisted id; falls back to [DEFAULT] for unknown/null ids. */
    fun byId(id: String?): LlmModelSpec = CATALOG.firstOrNull { it.id == id } ?: DEFAULT
}