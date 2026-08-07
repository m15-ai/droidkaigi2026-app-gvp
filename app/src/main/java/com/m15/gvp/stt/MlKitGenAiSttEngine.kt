package com.m15.gvp.stt

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.m15.gvp.audio.SileroVad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.Locale

/**
 * EXPERIMENT(stt-eval): on-device STT via Google's ML Kit GenAI Speech Recognition (AICore), selectable
 * against [SherpaSttEngine] through [SttRouter] (settings toggle "ML Kit GenAI STT").
 *
 * Fed via [sendPcm] from the app's own [com.m15.gvp.audio.AudioCapture] (so [ownsMic] is false): the
 * mic PCM is written to a `ParcelFileDescriptor` pipe whose read end is handed to ML Kit as
 * `AudioSource.fromPfd(...)`. Routing the recognizer through our own capture (rather than ML Kit's
 * `fromMic`) keeps the energy [SileroVad] in the loop, so **barge-in and TTS echo-suppression work**:
 *  - each frame runs through [vad]; its SPEECH_START/END drive [SttEvent.UserStart]/[SttEvent.UserStop]
 *    (and thus the BargeInController), exactly as the Sherpa path does;
 *  - while TTS plays ([SileroVad.echoSuppression]) we stop writing to the pipe, so ML Kit doesn't
 *    transcribe the assistant's own voice.
 *
 * Transcription itself (partials + end-of-utterance finals) is ML Kit's own endpointing — its
 * FinalTextResponse maps to [SttEvent.Partial] with isFinal=true, which the VM sends to the LLM.
 *
 * Mode: prefers Advanced (GenAI, Pixel 10) and falls back to Basic (traditional on-device SODA, API
 * 31+) when the Advanced AICore model isn't provisioned yet. Alpha API, no SLA.
 */
class MlKitGenAiSttEngine(
    private val vad: SileroVad,
    context: Context
) : SttEngine {

    private val TAG = "GVP.STT.MLKit"
    private val appContext = context.applicationContext

    companion object {
        // Advanced (nano-v3, AICore GenAI ASR) is provisioned on the Pixel 10 and transcribes well, BUT
        // it contends with the Gemini Nano LLM (also AICore Feature 636): Nano generation slowed from
        // ~2s to >6s, and slow replies got cancelled by the next utterance. Basic (SODA) coexists with
        // Nano cleanly. Prefer Basic until the LLM runs off AICore (e.g. MediaPipe/LiteRT), then flip
        // this to true to use the GenAI recognizer. See memory: mlkit-genai-stt-eval.
        private const val PREFER_ADVANCED = false
    }

    private val _events = MutableSharedFlow<SttEvent>(extraBufferCapacity = 128)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var recognizer: SpeechRecognizer? = null
    private var sessionJob: Job? = null
    private var writerJob: Job? = null

    // PCM → ML Kit plumbing. sendPcm offers bytes to [pcmChannel] (non-blocking, drops on overflow so
    // pipe backpressure can never stall the audio/VAD thread); [writerJob] drains it into [pipeOut].
    private var pcmChannel: Channel<ByteArray>? = null
    @Volatile private var pipeOut: OutputStream? = null

    @Volatile private var speaking = false
    private var lastPartial = ""

    private val _status = MutableStateFlow(SttStatus.CHECKING)
    val status: StateFlow<SttStatus> = _status
    private val _statusDetail = MutableStateFlow<String?>(null)
    val statusDetail: StateFlow<String?> = _statusDetail

    /** Fed PCM via [sendPcm] (we own the recognizer, the app owns the mic). */
    override val ownsMic: Boolean get() = false

    override fun start() {
        Log.i(TAG, "ML Kit GenAI STT start (fromPfd)")
        vad.reset()
        speaking = false
        lastPartial = ""
        sessionJob?.cancel()
        sessionJob = scope.launch { runSession() }
    }

    private suspend fun runSession() {
        try {
            // Resolve the recognizer once per app launch and cache it — re-probing every session start
            // cost ~5s (the Advanced/Feature-267 download attempt) and swallowed the user's first
            // utterance, besides contending with the Gemini Nano LLM warmup on AICore. Advanced is
            // re-checked on the next cold start (by which point its model may have finished downloading).
            val rec = recognizer ?: run {
                val ready = if (PREFER_ADVANCED) {
                    prepareMode(SpeechRecognizerOptions.Mode.MODE_ADVANCED, awaitDownload = false) ||
                        prepareMode(SpeechRecognizerOptions.Mode.MODE_BASIC, awaitDownload = true)
                } else {
                    prepareMode(SpeechRecognizerOptions.Mode.MODE_BASIC, awaitDownload = true)
                }
                if (!ready) {
                    _status.value = SttStatus.NEEDS_DOWNLOAD
                    Log.w(TAG, "no mode available — AICore model likely still downloading")
                    return
                }
                recognizer!!
            }

            // Build the PCM pipe: ML Kit reads the read end; we write mic frames to the write end.
            val pipe = ParcelFileDescriptor.createPipe()
            val readFd = pipe[0]
            pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            val channel = Channel<ByteArray>(capacity = 64).also { pcmChannel = it }
            writerJob = scope.launch {
                try {
                    for (bytes in channel) pipeOut?.write(bytes)
                } catch (_: Throwable) { /* pipe closed on stop */ }
            }

            _status.value = SttStatus.READY
            Log.i(TAG, "recognition starting (fromPfd)")
            val request = SpeechRecognizerRequest.Builder().apply {
                audioSource = AudioSource.fromPfd(readFd)
            }.build()

            rec.startRecognition(request).collect { resp ->
                when (resp) {
                    is SpeechRecognizerResponse.PartialTextResponse -> onText(resp.text, isFinal = false)
                    is SpeechRecognizerResponse.FinalTextResponse -> onText(resp.text, isFinal = true)
                    is SpeechRecognizerResponse.ErrorResponse -> {
                        Log.w(TAG, "recognition error: $resp")
                        _statusDetail.value = "Recognition error — ${resp.toString().take(120)}"
                    }
                    else -> Unit
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit recognition failed", t)
            _status.value = SttStatus.UNAVAILABLE
            _statusDetail.value = (t.message ?: t.javaClass.simpleName).take(140)
        }
    }

    /**
     * Create a recognizer for [mode] and return true only if the feature reports AVAILABLE (setting
     * [recognizer]). When [awaitDownload] is false and the feature isn't present, the model download is
     * kicked off in the background and this returns false immediately — so the caller falls through to
     * Basic without the ~5s block. On Pixel 10, Advanced (GenAI Feature 267) is typically still
     * downloading, so it's probed with awaitDownload=false; Basic (always present) needs no wait.
     */
    private suspend fun prepareMode(mode: Int, awaitDownload: Boolean): Boolean {
        val name = if (mode == SpeechRecognizerOptions.Mode.MODE_ADVANCED) "Advanced" else "Basic"
        val options = SpeechRecognizerOptions.Builder().apply {
            locale = Locale.US
            preferredMode = mode
        }.build()
        val rec = SpeechRecognition.getClient(options)
        _status.value = SttStatus.CHECKING
        var status = rec.checkStatus()
        Log.i(TAG, "$name checkStatus=$status")
        if (status != FeatureStatus.AVAILABLE) {
            if (awaitDownload) {
                _status.value = SttStatus.DOWNLOADING
                _statusDetail.value = "Preparing ML Kit ASR ($name) model…"
                runCatching { rec.download().collect { Log.i(TAG, "[$name] download: $it") } }
                status = rec.checkStatus()
                Log.i(TAG, "$name checkStatus after download=$status")
            } else {
                // Don't block the session on it — let AICore fetch it for a future launch.
                Log.i(TAG, "$name not ready (status=$status) — backgrounding download, falling back")
                scope.launch { runCatching { rec.download().collect { Log.i(TAG, "[bg $name] download: $it") } } }
            }
        }
        return if (status == FeatureStatus.AVAILABLE) {
            recognizer = rec
            _statusDetail.value = "ML Kit GenAI ($name)"
            Log.i(TAG, "$name mode AVAILABLE")
            true
        } else {
            if (awaitDownload) {
                _statusDetail.value = "ML Kit $name not ready (AICore model still downloading)"
                runCatching { rec.close() }
            }
            false
        }
    }

    override fun sendPcm(pcm: ShortArray) {
        // 1. VAD drives barge-in boundaries (mirrors the Sherpa path) — keep this on the caller thread
        //    so interruption latency isn't affected by recognition backpressure.
        when (vad.process(pcm, System.currentTimeMillis())) {
            SileroVad.Transition.SPEECH_START -> _events.tryEmit(SttEvent.UserStart)
            SileroVad.Transition.SPEECH_END -> _events.tryEmit(SttEvent.UserStop)
            SileroVad.Transition.NONE -> Unit
        }

        // 2. Feed ML Kit — but not while TTS plays (would transcribe the assistant's echo).
        val channel = pcmChannel ?: return
        if (vad.echoSuppression) return
        val bytes = ByteArray(pcm.size * 2)
        var j = 0
        for (s in pcm) {
            val v = s.toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        channel.trySend(bytes) // drop on overflow rather than block the audio thread
    }

    private fun onText(text: String, isFinal: Boolean) {
        val t = text.trim()
        if (isFinal) {
            if (t.isNotEmpty()) _events.tryEmit(SttEvent.Partial(t, isFinal = true))
            lastPartial = ""
        } else if (t.isNotEmpty() && t != lastPartial) {
            lastPartial = t
            _events.tryEmit(SttEvent.Partial(t, isFinal = false))
        }
    }

    override fun events(): Flow<SttEvent> = _events.asSharedFlow()

    override fun close() {
        Log.i(TAG, "ML Kit GenAI STT close")
        speaking = false
        sessionJob?.cancel(); sessionJob = null
        writerJob?.cancel(); writerJob = null
        pcmChannel?.close(); pcmChannel = null
        runCatching { pipeOut?.close() }; pipeOut = null
        scope.launch { runCatching { recognizer?.stopRecognition() } }
    }
}
