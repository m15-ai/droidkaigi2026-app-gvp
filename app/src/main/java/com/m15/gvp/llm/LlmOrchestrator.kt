package com.m15.gvp.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * On-device LLM entry point. Picks the best available engine and exposes status to the UI:
 *
 *  1. [MediaPipeLlmEngine] (LiteRT, e.g. Qwen/Gemma) — preferred; works wherever AI Edge Gallery does.
 *  2. [MlKitLlmEngine] (Gemini Nano via AICore) — used only where Feature 636 is provisioned.
 *  3. [StubLlmEngine] — canned fallback so the pipeline always runs.
 *
 * If MediaPipe's model isn't downloaded yet and AICore isn't available, status is [LlmStatus.NEEDS_DOWNLOAD]
 * and [downloadModelAndInit] fetches it on demand. Construction is cheap; call [warmUp] at startup.
 */
class LlmOrchestrator(context: Context) : LlmClient {

    private val TAG = "GVP.LLM"
    private val appContext = context.applicationContext

    private val mediaPipe = MediaPipeLlmEngine(appContext)
    private val aiCore = MlKitLlmEngine()
    private val stub = StubLlmEngine()

    @Volatile private var active: LlmClient = stub

    /** The user-selected LiteRT model (persisted in GvpPrefs; set by the ViewModel). */
    @Volatile var selectedModel: LlmModelSpec = GvpLlmModel.DEFAULT

    /** User-selected inference pipeline (AUTO/MEDIAPIPE/AICORE); persisted in GvpPrefs. */
    @Volatile var pipelineMode: PipelineMode = PipelineMode.AUTO

    /** Hugging Face token for gated (Gemma) downloads; null/blank for anonymous downloads. */
    @Volatile private var hfToken: String? = null

    private val _status = MutableStateFlow(LlmStatus.CHECKING)
    val status: StateFlow<LlmStatus> = _status

    private val _statusDetail = MutableStateFlow<String?>(null)
    val statusDetail: StateFlow<String?> = _statusDetail

    /** Display name of the *active* engine's model (e.g. "Gemini Nano" when AICore wins, else the
     *  selected LiteRT model). Drives the Setup card title so it reflects what's actually running. */
    private val _activeModelName = MutableStateFlow(selectedModel.displayName)
    val activeModelName: StateFlow<String> = _activeModelName

    fun setHfToken(token: String?) { hfToken = token?.takeIf { it.isNotBlank() } }

    private fun gatedHint(model: LlmModelSpec) =
        if (model.authGated) " · gated — add a Hugging Face token in Settings" else ""

    /**
     * Detect and bind the best engine allowed by [pipelineMode]:
     *  - AUTO: selected MediaPipe model (if present) → AICore Gemini Nano → offer download → stub.
     *  - MEDIAPIPE: only the selected LiteRT model; offer its download if missing, never use AICore.
     *  - AICORE: only Gemini Nano (Feature 636); report unavailable if the device isn't provisioned.
     */
    suspend fun warmUp() {
        _status.value = LlmStatus.CHECKING
        _statusDetail.value = null
        val model = selectedModel
        _activeModelName.value = model.displayName
        val allowMediaPipe = pipelineMode != PipelineMode.AICORE
        val allowAiCore = pipelineMode != PipelineMode.MEDIAPIPE

        // 1. MediaPipe: the selected LiteRT model, already downloaded.
        if (allowMediaPipe && mediaPipe.isModelDownloaded(model) && mediaPipe.initialize(model)) {
            active = mediaPipe
            _activeModelName.value = model.displayName
            _statusDetail.value = mediaPipe.detail
            _status.value = LlmStatus.READY
            Log.i(TAG, "LLM ready → MediaPipe (${model.displayName}) [mode=$pipelineMode]")
            return
        }

        // 2. AICore: Gemini Nano (only provisioned on some devices, e.g. Tensor G5 Pixel).
        if (allowAiCore && aiCore.initialize(onDownloading = {
                _activeModelName.value = aiCore.modelName
                _status.value = LlmStatus.DOWNLOADING
            })) {
            active = aiCore
            _activeModelName.value = aiCore.modelName
            _statusDetail.value = aiCore.detail
            _status.value = LlmStatus.READY
            Log.i(TAG, "LLM ready → Gemini Nano (AICore) [mode=$pipelineMode]")
            return
        }

        // 3. MediaPipe model just needs downloading → offer it.
        if (allowMediaPipe && !mediaPipe.isModelDownloaded(model)) {
            active = stub
            _statusDetail.value = "${model.displayName} not downloaded${gatedHint(model)}"
            _status.value = LlmStatus.NEEDS_DOWNLOAD
            Log.w(TAG, "LLM → stub; ${model.displayName} not downloaded [mode=$pipelineMode]")
            return
        }

        // 4. Nothing usable (e.g. AICORE forced on a device without Feature 636).
        active = stub
        _statusDetail.value = if (pipelineMode == PipelineMode.AICORE) aiCore.detail else (mediaPipe.detail ?: aiCore.detail)
        _status.value = LlmStatus.UNAVAILABLE
        Log.w(TAG, "LLM ready → stub (no engine available for mode $pipelineMode)")
    }

    /**
     * Explicit user model switch. Loads it if already present; otherwise goes straight to
     * NEEDS_DOWNLOAD for that model (no silent AICore/stub fallback — the user asked for this one).
     */
    suspend fun selectModel(model: LlmModelSpec) {
        selectedModel = model
        _activeModelName.value = model.displayName
        _status.value = LlmStatus.CHECKING
        _statusDetail.value = null

        if (mediaPipe.isModelDownloaded(model) && mediaPipe.initialize(model)) {
            active = mediaPipe
            _statusDetail.value = mediaPipe.detail
            _status.value = LlmStatus.READY
            Log.i(TAG, "LLM switched → MediaPipe (${model.displayName})")
            return
        }

        active = stub
        _statusDetail.value = "${model.displayName} not downloaded${gatedHint(model)}"
        _status.value = LlmStatus.NEEDS_DOWNLOAD
        Log.i(TAG, "LLM switch → ${model.displayName} needs download")
    }

    /** Download the selected LiteRT model with progress, then warm up MediaPipe. Triggered from the UI. */
    suspend fun downloadModelAndInit() {
        val model = selectedModel

        // Gated model with no token → stop early with an actionable message rather than a 401.
        if (model.authGated && hfToken.isNullOrBlank()) {
            _status.value = LlmStatus.NEEDS_DOWNLOAD
            _statusDetail.value =
                "${model.displayName} is gated — accept its license on Hugging Face and add a token in Settings"
            Log.w(TAG, "download blocked; ${model.displayName} gated and no HF token set")
            return
        }

        _status.value = LlmStatus.DOWNLOADING
        _statusDetail.value = "Downloading ${model.displayName}…"

        val result = ModelDownloader.download(
            url = model.url,
            dest = model.downloadDest(appContext),
            authToken = hfToken,
        ) { downloaded, total ->
            _statusDetail.value = if (total > 0) {
                val pct = (downloaded * 100 / total)
                "Downloading ${model.displayName}… $pct% (${downloaded / 1_000_000}/${total / 1_000_000} MB)"
            } else {
                "Downloading ${model.displayName}… ${downloaded / 1_000_000} MB"
            }
        }

        result.onSuccess {
            if (mediaPipe.initialize(model)) {
                active = mediaPipe
                _statusDetail.value = mediaPipe.detail
                _status.value = LlmStatus.READY
                Log.i(TAG, "LLM ready → MediaPipe (${model.displayName}, after download)")
            } else {
                _statusDetail.value = mediaPipe.detail
                _status.value = LlmStatus.UNAVAILABLE
            }
        }.onFailure { e ->
            val msg = e.message ?: e.javaClass.simpleName
            // 401/403 means the model is auth-gated (Gemma) — needs license acceptance + a valid HF token.
            _statusDetail.value = "Download failed — $msg" +
                if (msg.contains("401") || msg.contains("403"))
                    " (gated — check license acceptance and your Hugging Face token)" else ""
            _status.value = LlmStatus.NEEDS_DOWNLOAD
            Log.e(TAG, "model download/init failed", e)
        }
    }

    override fun sendUserText(
        text: String,
        history: List<Pair<String, String>>,
        systemMessage: String
    ): Flow<LlmClient.Event> = active.sendUserText(text, history, systemMessage)

    override fun cancelResponse() = active.cancelResponse()

    override fun close() {
        runCatching { mediaPipe.close() }
        runCatching { aiCore.close() }
        runCatching { stub.close() }
    }
}