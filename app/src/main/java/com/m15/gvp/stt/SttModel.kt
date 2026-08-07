package com.m15.gvp.stt

import android.content.Context
import java.io.File

/**
 * A selectable on-device streaming ASR model for [SherpaSttEngine]. Mirrors
 * [com.m15.gvp.llm.LlmModelSpec]'s resolve/download pattern: a per-model storage dir plus the
 * `/data/local/tmp` adb-push escape hatch.
 *
 * Every catalog entry is one of csukuangfj's Sherpa-ONNX streaming Zipformer transducers on Hugging
 * Face — four files (encoder/decoder/joiner/tokens) downloaded individually at runtime, no archive
 * extraction. Add a model by appending a [SttModelSpec] to [GvpSttModel.CATALOG]; the Settings
 * picker and download flow pick it up automatically.
 */
data class SttModelSpec(
    val id: String,
    val displayName: String,
    /** HF repo "…/resolve/main" base, no trailing slash. */
    val baseUrl: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String = "tokens.txt",
    /** Approx total download size in MB, for the picker. The real size comes from the HTTP headers. */
    val approxSizeMb: Int,
) {
    /** All files that must be present for the recognizer to load. */
    val files: List<String> get() = listOf(encoder, decoder, joiner, tokens)

    fun url(fileName: String): String = "$baseUrl/$fileName"

    /**
     * Per-model app-private storage dir. Models share file names (e.g. every repo ships a
     * `tokens.txt`), so each gets its own directory to keep downloads from colliding.
     */
    fun storageDir(context: Context): File = File(context.filesDir, "stt/$id")

    /** Where the in-app download writes [fileName] (its parent dir is created on demand). */
    fun downloadDest(context: Context, fileName: String): File =
        File(storageDir(context), fileName).also { it.parentFile?.mkdirs() }

    /**
     * Resolve an existing file: prefer this model's storage dir, then an adb-pushed copy at
     * `/data/local/tmp/<file>`. The [GvpSttModel.DEFAULT] model also falls back to the legacy flat
     * `filesDir` location used before the catalog existed, so an earlier download isn't re-fetched.
     * Returns null if none exists, in which case the model needs downloading.
     */
    fun resolve(context: Context, fileName: String): File? {
        val own = File(storageDir(context), fileName)
        if (own.exists() && own.length() > 0) return own
        val pushed = File("/data/local/tmp", fileName)
        if (pushed.exists() && pushed.length() > 0) return pushed
        if (id == GvpSttModel.DEFAULT.id) {
            val legacy = File(context.filesDir, fileName)
            if (legacy.exists() && legacy.length() > 0) return legacy
        }
        return null
    }

    fun isDownloaded(context: Context): Boolean = files.all { resolve(context, it) != null }

    /** Absolute path to [fileName], falling back to this model's download dest if not present yet. */
    fun path(context: Context, fileName: String): String =
        resolve(context, fileName)?.absolutePath ?: File(storageDir(context), fileName).absolutePath
}

/** The streaming-ASR model catalog and selection helpers. */
object GvpSttModel {

    private fun repo(name: String) = "https://huggingface.co/csukuangfj/$name/resolve/main"

    /**
     * Default — csukuangfj's English streaming Zipformer (2023-06-26), int8-quantized. ~73 MB and
     * fast; the known-good baseline. LibriSpeech-trained.
     */
    val ZIPFORMER_EN_06_26_INT8 = SttModelSpec(
        id = "zipformer-en-2023-06-26-int8",
        displayName = "Zipformer EN 2023-06-26 (int8)",
        baseUrl = repo("sherpa-onnx-streaming-zipformer-en-2023-06-26"),
        encoder = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
        decoder = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
        joiner = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
        approxSizeMb = 73,
    )

    /**
     * Libri + GigaSpeech-trained streaming Zipformer (2023-06-21), int8. A larger, stronger model
     * (~190 MB): the GigaSpeech data makes it noticeably more robust on real-world / spontaneous
     * speech than the LibriSpeech-only builds. Recommended higher-quality pick.
     */
    val ZIPFORMER_EN_GIGA_06_21_INT8 = SttModelSpec(
        id = "zipformer-en-libri-giga-2023-06-21-int8",
        displayName = "Zipformer EN Libri+GigaSpeech 2023-06-21 (int8)",
        baseUrl = repo("sherpa-onnx-streaming-zipformer-en-2023-06-21"),
        encoder = "encoder-epoch-99-avg-1.int8.onnx",
        decoder = "decoder-epoch-99-avg-1.onnx",
        joiner = "joiner-epoch-99-avg-1.int8.onnx",
        approxSizeMb = 190,
    )

    /**
     * The same 2023-06-26 model in full precision (float32 encoder/joiner) — highest accuracy of the
     * three, at the cost of a much larger download (~265 MB) and more RAM/CPU at runtime.
     */
    val ZIPFORMER_EN_06_26_FLOAT = SttModelSpec(
        id = "zipformer-en-2023-06-26-float",
        displayName = "Zipformer EN 2023-06-26 (float, highest accuracy)",
        baseUrl = repo("sherpa-onnx-streaming-zipformer-en-2023-06-26"),
        encoder = "encoder-epoch-99-avg-1-chunk-16-left-128.onnx",
        decoder = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
        joiner = "joiner-epoch-99-avg-1-chunk-16-left-128.onnx",
        approxSizeMb = 265,
    )

    val CATALOG: List<SttModelSpec> =
        listOf(ZIPFORMER_EN_06_26_INT8, ZIPFORMER_EN_GIGA_06_21_INT8, ZIPFORMER_EN_06_26_FLOAT)

    val DEFAULT: SttModelSpec = ZIPFORMER_EN_06_26_INT8

    /** Look up a spec by persisted id; falls back to [DEFAULT] for unknown/null ids. */
    fun byId(id: String?): SttModelSpec = CATALOG.firstOrNull { it.id == id } ?: DEFAULT
}