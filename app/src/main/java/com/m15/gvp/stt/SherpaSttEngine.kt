package com.m15.gvp.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.m15.gvp.audio.SileroVad
import com.m15.gvp.llm.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/**
 * On-device streaming STT backed by Sherpa-ONNX (a selectable English streaming Zipformer
 * transducer — see [GvpSttModel]), gated by [SileroVad].
 *
 * The energy VAD still brackets utterances (its [SttEvent.UserStart]/[SttEvent.UserStop] drive
 * barge-in) and its tuned silence threshold decides when to *finalize* a transcript. Mic PCM is fed
 * to the Sherpa recognizer continuously to produce live partials, except while TTS is playing
 * ([SileroVad.echoSuppression]) — that gating stops the recognizer from transcribing the assistant's
 * own voice echoed back through the mic.
 *
 * The recognizer loads the user-selected model from [GvpSttModel.CATALOG] ([selectedModel]);
 * [prepare]/[downloadModelAndPrepare] fetch and load it, publishing [status]. [selectModel] switches
 * the active model at runtime (reloading the recognizer or flagging the new model for download).
 * Until ready, only VAD events flow (no transcripts).
 */
class SherpaSttEngine(
    private val vad: SileroVad,
    context: Context
) : SttEngine {

    private val TAG = "GVP.STT"
    private val appContext = context.applicationContext

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 128)

    // Buffer a little audio until the recognizer is ready (mirrors the warm-up grace from the stub).
    private val pending = ArrayDeque<ShortArray>()
    private val pendingMaxFrames = 50

    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var stream: OnlineStream? = null
    @Volatile private var started = false
    private var lastPartial = ""

    private val _status = MutableStateFlow(SttStatus.CHECKING)
    val status: StateFlow<SttStatus> = _status
    private val _statusDetail = MutableStateFlow<String?>(null)
    val statusDetail: StateFlow<String?> = _statusDetail

    /** The active model. Switch it via [selectModel]; the VM keeps it in sync with the persisted id. */
    @Volatile var selectedModel: SttModelSpec = GvpSttModel.DEFAULT
        private set

    // ---- Model lifecycle ----

    /** Load the recognizer if the selected model is present; otherwise mark [SttStatus.NEEDS_DOWNLOAD]. */
    suspend fun prepare() {
        val model = selectedModel
        _status.value = SttStatus.CHECKING
        if (!model.isDownloaded(appContext)) {
            _status.value = SttStatus.NEEDS_DOWNLOAD
            _statusDetail.value = "${model.displayName} not downloaded (~${model.approxSizeMb} MB)"
            return
        }
        loadRecognizer()
    }

    /**
     * Switch the active ASR model. Loads the new model's recognizer if present, otherwise flags it
     * for download — mirrors the LLM orchestrator's model-switch behaviour. No-op if the model is
     * already selected and ready.
     */
    suspend fun selectModel(model: SttModelSpec) {
        if (model.id == selectedModel.id && _status.value == SttStatus.READY) return
        selectedModel = model
        releaseRecognizer()
        prepare()
    }

    /** Download the selected model's files (with progress), then load the recognizer. From the UI. */
    suspend fun downloadModelAndPrepare() {
        val model = selectedModel
        _status.value = SttStatus.DOWNLOADING
        val ok = withContext(Dispatchers.IO) {
            for (f in model.files) {
                if (model.resolve(appContext, f) != null) continue
                val r = ModelDownloader.download(model.url(f), model.downloadDest(appContext, f)) { dl, total ->
                    _statusDetail.value = "Downloading $f… " +
                        if (total > 0) "${dl / 1_000_000}/${total / 1_000_000} MB" else "${dl / 1_000_000} MB"
                }
                if (r.isFailure) {
                    _statusDetail.value = "Download failed — ${r.exceptionOrNull()?.message}"
                    return@withContext false
                }
            }
            true
        }
        if (ok) loadRecognizer() else _status.value = SttStatus.NEEDS_DOWNLOAD
    }

    private suspend fun loadRecognizer() {
        val created = withContext(Dispatchers.IO) { createRecognizer() }
        _status.value = if (created) SttStatus.READY else SttStatus.UNAVAILABLE
    }

    private fun createRecognizer(): Boolean = try {
        val model = selectedModel
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = model.path(appContext, model.encoder),
                    decoder = model.path(appContext, model.decoder),
                    joiner = model.path(appContext, model.joiner),
                ),
                tokens = model.path(appContext, model.tokens),
                numThreads = 2,
                provider = "cpu",
            ),
            // We finalize on the (tuned) VAD silence threshold rather than Sherpa's own endpointing.
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        val rec = OnlineRecognizer(config = config)
        stream = rec.createStream()
        recognizer = rec
        _statusDetail.value = "Sherpa: ${model.displayName}"
        Log.i(TAG, "Sherpa recognizer loaded (${model.displayName})")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Sherpa recognizer load failed", t)
        _statusDetail.value = "Recognizer load failed — ${(t.message ?: t.javaClass.simpleName).take(120)}"
        false
    }

    /** Free the native recognizer/stream so a different model can be loaded in its place. */
    private fun releaseRecognizer() {
        started = false
        lastPartial = ""
        runCatching { stream?.release() }
        runCatching { recognizer?.release() }
        stream = null
        recognizer = null
    }

    // ---- SttEngine ----

    override fun start() {
        Log.i(TAG, "Sherpa STT start")
        vad.reset()
        pending.clear()
        lastPartial = ""
        runCatching { stream?.let { recognizer?.reset(it) } }
        started = true
    }

    override fun sendPcm(pcm: ShortArray) {
        if (!started) {
            pending.addLast(pcm.copyOf())
            while (pending.size > pendingMaxFrames) pending.removeFirst()
            return
        }
        while (pending.isNotEmpty()) process(pending.removeFirst())
        process(pcm)
    }

    private fun process(pcm: ShortArray) {
        // 1. VAD drives barge-in boundaries and end-of-utterance finalization.
        when (vad.process(pcm, System.currentTimeMillis())) {
            SileroVad.Transition.SPEECH_START -> _events.tryEmit(SttEvent.UserStart)
            SileroVad.Transition.SPEECH_END -> {
                _events.tryEmit(SttEvent.UserStop)
                finalizeUtterance()
            }
            SileroVad.Transition.NONE -> Unit
        }

        // 2. Feed the recognizer for live partials — but not while TTS plays (would transcribe echo).
        val rec = recognizer
        val st = stream
        if (rec == null || st == null || vad.echoSuppression) return

        val samples = FloatArray(pcm.size) { pcm[it] / 32768.0f }
        st.acceptWaveform(samples, 16000)
        while (rec.isReady(st)) rec.decode(st)
        val text = rec.getResult(st).text
        if (text.isNotEmpty() && text != lastPartial) {
            lastPartial = text
            _events.tryEmit(SttEvent.Partial(text, isFinal = false))
        }
    }

    private fun finalizeUtterance() {
        val rec = recognizer ?: return
        val st = stream ?: return
        val text = rec.getResult(st).text.trim()
        if (text.isNotEmpty()) {
            _events.tryEmit(SttEvent.Partial(text, isFinal = true))
        }
        runCatching { rec.reset(st) }
        lastPartial = ""
    }

    override fun events(): Flow<SttEvent> = _events.asSharedFlow()

    override fun close() {
        // Reset for the next session but keep the (expensive) recognizer loaded for the app's lifetime.
        Log.i(TAG, "Sherpa STT close")
        started = false
        pending.clear()
        lastPartial = ""
        runCatching { stream?.let { recognizer?.reset(it) } }
    }
}