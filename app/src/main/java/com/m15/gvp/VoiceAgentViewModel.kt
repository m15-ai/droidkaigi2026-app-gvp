package com.m15.gvp

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m15.gvp.audio.SileroVad
import com.m15.gvp.llm.GvpLlmModel
import com.m15.gvp.llm.LlmClient
import com.m15.gvp.llm.LlmModelSpec
import com.m15.gvp.llm.LlmOrchestrator
import com.m15.gvp.llm.LlmStatus
import com.m15.gvp.llm.PipelineMode
import com.m15.gvp.settings.GvpPrefs
import com.m15.gvp.settings.ThemeMode
import com.m15.gvp.stt.GvpSttModel
import com.m15.gvp.stt.SherpaSttEngine
import com.m15.gvp.stt.SttEngine
import com.m15.gvp.stt.SttModelSpec
import com.m15.gvp.stt.SttEvent
import com.m15.gvp.stt.SttStatus
import com.m15.gvp.tts.AndroidTtsEngine
import com.m15.gvp.service.VoiceAgentService
import com.m15.gvp.util.LatencyTracker
import com.m15.gvp.util.LlmStop
import com.m15.gvp.util.areSimilar
import com.m15.gvp.util.sanitizeForSpeech
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface SupportsSpeakerphone {
    fun setSpeakerphoneEnabled(enabled: Boolean)
}

data class AgentUiState(
    val sessionId: String? = null,
    val sessionActive: Boolean = false,
    val livePartial: String? = null,
    val assistantLive: String? = null,
    val isThinking: Boolean = false,
    val error: String? = null,
    val messages: List<Pair<String, String>> = emptyList(),
    val speakerOn: Boolean = true,
    // Pipeline status (drives the VAD/STT/LLM/TTS chips)
    val userSpeaking: Boolean = false,
    val ttsSpeaking: Boolean = false
)

class VoiceAgentViewModel(
    private val stt: SttEngine = ServiceLocator.stt,
    private val vad: SileroVad = ServiceLocator.vad,
    private val llm: LlmClient = ServiceLocator.llm,
    private val audio: com.m15.gvp.audio.AudioCapture = ServiceLocator.audio,
    private val barge: BargeInController = ServiceLocator.barge,
    private val prefs: GvpPrefs = ServiceLocator.prefs
) : ViewModel() {

    companion object {
        private const val TAG = "GVP.Pipeline"
        /** How long after TTS ends to keep the VAD's stricter barge-in gate, covering the echo tail. */
        private const val ECHO_SUPPRESS_HANGOVER_MS = 400L
    }

    private val am: AudioManager = ServiceLocator.audioManager
    private val _ui = MutableStateFlow(AgentUiState())
    val ui: StateFlow<AgentUiState> = _ui

    private var micStarted = false
    private var sttJob: Job? = null
    private var llmJob: Job? = null
    private var echoSuppressClearJob: Job? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    // --- On-device LLM status (MediaPipe / Gemini Nano / stub) ---
    val llmStatus: StateFlow<LlmStatus> =
        (llm as? LlmOrchestrator)?.status ?: MutableStateFlow(LlmStatus.READY)
    val llmStatusDetail: StateFlow<String?> =
        (llm as? LlmOrchestrator)?.statusDetail ?: MutableStateFlow(null)
    /** Name of the engine actually running (e.g. "Gemini Nano" on AICore), for the Setup card title. */
    val activeLlmModelName: StateFlow<String> =
        (llm as? LlmOrchestrator)?.activeModelName ?: MutableStateFlow(GvpLlmModel.DEFAULT.displayName)

    // --- On-device STT status — reflects whichever engine the router is set to (Sherpa or ML Kit) ---
    /** EXPERIMENT(stt-eval): true = ML Kit GenAI STT, false = Sherpa. Takes effect next session. */
    val useMlKitStt: StateFlow<Boolean> =
        prefs.useMlKitStt.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val sttStatus: StateFlow<SttStatus> =
        combine(useMlKitStt, ServiceLocator.sttRouter.sherpa.status, ServiceLocator.sttRouter.mlkit.status) {
            useMl, sherpa, mlkit -> if (useMl) mlkit else sherpa
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SttStatus.CHECKING)
    val sttStatusDetail: StateFlow<String?> =
        combine(useMlKitStt, ServiceLocator.sttRouter.sherpa.statusDetail, ServiceLocator.sttRouter.mlkit.statusDetail) {
            useMl, sherpa, mlkit -> if (useMl) mlkit else sherpa
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // --- System message (user-configurable, persisted via DataStore) ---
    private val _systemMessage = MutableStateFlow(LlmClient.DEFAULT_SYSTEM_MESSAGE)
    val systemMessage: StateFlow<String> = _systemMessage

    // --- Visualizer plumbing ---
    private val _ttsLevel = MutableStateFlow(0f)
    val ttsLevel: StateFlow<Float> = _ttsLevel

    private val _showVisualizer = MutableStateFlow(true)
    val showVisualizer: StateFlow<Boolean> = _showVisualizer

    // --- Pipeline latency (time to first token) ---
    private val latency = LatencyTracker()
    val latencyMs: StateFlow<Long?> = latency.ttftMs

    // --- Settings exposed to the UI ---
    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)
    val ttsMuted: StateFlow<Boolean> =
        prefs.ttsMuted.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val vadSilenceMs: StateFlow<Long> =
        prefs.vadSilenceMs.stateIn(viewModelScope, SharingStarted.Eagerly, GvpPrefs.DEFAULT_VAD_SILENCE_MS)
    val micEnergyThreshold: StateFlow<Float> =
        prefs.micEnergyThreshold.stateIn(viewModelScope, SharingStarted.Eagerly, GvpPrefs.DEFAULT_MIC_ENERGY_THRESHOLD)
    val bargeInEnergyThreshold: StateFlow<Float> =
        prefs.bargeInEnergyThreshold.stateIn(viewModelScope, SharingStarted.Eagerly, GvpPrefs.DEFAULT_BARGE_IN_ENERGY_THRESHOLD)

    // --- On-device LLM model selection (MediaPipe / LiteRT) ---
    val availableLlmModels: List<LlmModelSpec> = GvpLlmModel.CATALOG
    val llmModelId: StateFlow<String> =
        prefs.llmModelId.stateIn(viewModelScope, SharingStarted.Eagerly, GvpLlmModel.DEFAULT.id)
    val pipelineMode: StateFlow<PipelineMode> =
        prefs.pipelineMode.stateIn(viewModelScope, SharingStarted.Eagerly, PipelineMode.AUTO)
    val hfToken: StateFlow<String> =
        prefs.hfToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // --- On-device STT model selection (Sherpa-ONNX) ---
    val availableSttModels: List<SttModelSpec> = GvpSttModel.CATALOG
    val sttModelId: StateFlow<String> =
        prefs.sttModelId.stateIn(viewModelScope, SharingStarted.Eagerly, GvpSttModel.DEFAULT.id)

    // --- Selected TTS voice (persisted; null = system default) ---
    val ttsVoice: StateFlow<String?> =
        prefs.ttsVoice.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        Log.i(TAG, "VoiceAgentViewModel initialized")
        (ServiceLocator.tts as? AndroidTtsEngine)?.onAudioLevel = ::onTtsAudioLevel

        // Detect the best on-device LLM at startup. Non-blocking: the pipeline runs on the stub until
        // a real engine resolves ready (or the user downloads the selected LiteRT model).
        (llm as? LlmOrchestrator)?.let { engine ->
            // Keep the HF token in sync so a gated (Gemma) download can authenticate.
            viewModelScope.launch { prefs.hfToken.collect { engine.setHfToken(it) } }
            // React to the selected pipeline + model together so the orchestrator always warms up
            // with both current selections (and we can tell what actually changed):
            //  - startup        → full warmUp() honoring the persisted pipeline mode.
            //  - pipeline change → warmUp() again to (re)bind under the new mode.
            //  - model change    → selectModel() (explicit switch semantics), but only when the mode
            //                      can use MediaPipe — under AICORE the LiteRT picker is a no-op.
            viewModelScope.launch {
                var prevMode: PipelineMode? = null
                var prevId: String? = null
                combine(prefs.pipelineMode, prefs.llmModelId) { mode, id -> mode to id }
                    .collect { (mode, id) ->
                        val spec = GvpLlmModel.byId(id)
                        engine.pipelineMode = mode
                        engine.selectedModel = spec
                        when {
                            prevMode == null -> engine.warmUp()
                            mode != prevMode -> engine.warmUp()
                            id != prevId && mode != PipelineMode.AICORE -> engine.selectModel(spec)
                        }
                        prevMode = mode
                        prevId = id
                    }
            }
        }
        // React to the selected STT model. The first emission loads the persisted model at startup;
        // later emissions are explicit user switches (reload if present, else offer its download).
        // selectModel handles both. Until ready, only VAD events flow.
        ServiceLocator.sttRouter.sherpa.let { engine ->
            viewModelScope.launch {
                prefs.sttModelId.collect { id -> engine.selectModel(GvpSttModel.byId(id)) }
            }
        }
        // EXPERIMENT(stt-eval): keep the router's backend in sync with the toggle (next session applies).
        viewModelScope.launch {
            prefs.useMlKitStt.collect { ServiceLocator.sttRouter.useMlKit = it }
        }

        // Keep engines + UI in sync with persisted settings.
        viewModelScope.launch { prefs.systemMessage.collect { _systemMessage.value = it } }
        viewModelScope.launch {
            prefs.speakerOn.collect { on ->
                _ui.update { it.copy(speakerOn = on) }
                if (ui.value.sessionActive) applyRouting()
                (ServiceLocator.tts as? SupportsSpeakerphone)?.setSpeakerphoneEnabled(on)
            }
        }
        viewModelScope.launch {
            prefs.ttsMuted.collect { muted -> (ServiceLocator.tts as? AndroidTtsEngine)?.muted = muted }
        }
        viewModelScope.launch { prefs.vadSilenceMs.collect { vad.silenceThresholdMs = it } }
        viewModelScope.launch { prefs.micEnergyThreshold.collect { vad.energyThreshold = it } }
        viewModelScope.launch { prefs.bargeInEnergyThreshold.collect { vad.bargeInEnergyThreshold = it } }
        viewModelScope.launch {
            prefs.ttsVoice.collect { name -> (ServiceLocator.tts as? AndroidTtsEngine)?.setVoiceByName(name) }
        }
    }

    fun setSystemMessage(message: String) {
        viewModelScope.launch { prefs.setSystemMessage(message) }
    }

    /**
     * Status-card tap action: download the LiteRT model when that's what's needed, otherwise re-detect
     * the best engine (e.g. after enabling AICore GenAI features or an adb model push).
     */
    fun onLlmStatusTap() {
        val engine = llm as? LlmOrchestrator ?: return
        viewModelScope.launch {
            if (llmStatus.value == LlmStatus.NEEDS_DOWNLOAD) engine.downloadModelAndInit()
            else engine.warmUp()
        }
    }

    /**
     * STT status-card tap: download the ASR model when needed, otherwise re-detect/reload it.
     * The STT model section always drives the Sherpa engine, so reach for it directly — [stt] is the
     * [SttRouter], not a [SherpaSttEngine], so casting it would always fail (and the button no-op).
     */
    fun onSttStatusTap() {
        val engine = ServiceLocator.sttRouter.sherpa
        viewModelScope.launch {
            if (sttStatus.value == SttStatus.NEEDS_DOWNLOAD) engine.downloadModelAndPrepare()
            else engine.prepare()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    /** Switch the on-device LiteRT model. The orchestrator reloads it or flags it for download. */
    fun setLlmModel(id: String) {
        viewModelScope.launch {
            if (id != llmModelId.value) {
                // Start the new model with a clean context so it doesn't inherit (and parrot) the
                // previous model's conversation — otherwise A/B comparisons bleed across models.
                // Non-destructive: saved DB transcripts remain; only the live chat context resets.
                llmJob?.cancel(); llmJob = null
                _ui.update {
                    it.copy(
                        messages = emptyList(),
                        assistantLive = null,
                        livePartial = null,
                        isThinking = false
                    )
                }
            }
            prefs.setLlmModelId(id)
        }
    }

    fun setHfToken(token: String) {
        viewModelScope.launch { prefs.setHfToken(token) }
    }

    /**
     * Switch the inference pipeline (Auto / MediaPipe / AICore). The orchestrator re-warms under the
     * new mode. Clears the live chat context so an A/B switch between engines doesn't carry over the
     * previous engine's conversation (saved transcripts are untouched).
     */
    fun setPipelineMode(mode: PipelineMode) {
        viewModelScope.launch {
            if (mode != pipelineMode.value) {
                llmJob?.cancel(); llmJob = null
                _ui.update {
                    it.copy(
                        messages = emptyList(),
                        assistantLive = null,
                        livePartial = null,
                        isThinking = false
                    )
                }
            }
            prefs.setPipelineMode(mode)
        }
    }

    /** Switch the on-device STT model. The engine reloads it or flags it for download. */
    fun setSttModel(id: String) {
        viewModelScope.launch { prefs.setSttModelId(id) }
    }

    fun setTtsMuted(muted: Boolean) {
        viewModelScope.launch { prefs.setTtsMuted(muted) }
    }

    fun setVadSilenceMs(ms: Long) {
        viewModelScope.launch { prefs.setVadSilenceMs(ms) }
    }

    fun setMicEnergyThreshold(value: Float) {
        viewModelScope.launch { prefs.setMicEnergyThreshold(value) }
    }

    fun setBargeInEnergyThreshold(value: Float) {
        viewModelScope.launch { prefs.setBargeInEnergyThreshold(value) }
    }

    /** EXPERIMENT(stt-eval): switch the STT backend (Sherpa ⇄ ML Kit GenAI); applies next session. */
    fun setUseMlKitStt(value: Boolean) {
        viewModelScope.launch { prefs.setUseMlKitStt(value) }
    }

    fun setTtsVoice(name: String) {
        viewModelScope.launch { prefs.setTtsVoice(name) }
    }

    fun availableTtsVoices(): List<String> =
        (ServiceLocator.tts as? AndroidTtsEngine)?.availableVoices()?.map { it.name } ?: emptyList()

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { ServiceLocator.repo.clearAll() }
                .onFailure { Log.w(TAG, "clearHistory failed: ${it.message}") }
            _ui.update { it.copy(messages = emptyList()) }
        }
    }

    fun toggleSpeaker() {
        viewModelScope.launch { prefs.setSpeakerOn(!ui.value.speakerOn) }
    }

    fun onTtsAudioLevel(level: Float) {
        _ttsLevel.value = level
        val speaking = level > 0f
        // Full-duplex barge-in: while TTS plays, switch the VAD to its louder/sustained criteria so
        // the assistant's own echo can't self-trigger a barge-in. A user talking over it still clears
        // the higher bar and cancels the response (see BargeInController). When TTS ends, hold the
        // stricter gate for a short hangover so the lingering echo tail can't start a phantom turn
        // before the acoustic path settles.
        echoSuppressClearJob?.cancel()
        if (speaking) {
            echoSuppressClearJob = null
            vad.echoSuppression = true
        } else {
            echoSuppressClearJob = viewModelScope.launch {
                delay(ECHO_SUPPRESS_HANGOVER_MS)
                vad.echoSuppression = false
            }
        }
        _ui.update { it.copy(ttsSpeaking = speaking) }
    }

    fun toggleVisualizer() {
        _showVisualizer.value = !_showVisualizer.value
    }

    private fun applyRouting() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        val speakerOn = ui.value.speakerOn
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val preferredTypes = if (speakerOn) {
                    listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                } else {
                    listOf(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    )
                }
                val devices = am.availableCommunicationDevices
                for (type in preferredTypes) {
                    val dev = devices.firstOrNull { it.type == type }
                    if (dev != null) {
                        am.setCommunicationDevice(dev)
                        Log.i(TAG, "Routed to type $type (speakerOn=$speakerOn)")
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "applyRouting failed (speakerOn=$speakerOn)")
        }
    }

    /**
     * Send user text to the on-device LLM and collect its response. Passes full conversation
     * history so the model has context (same stateless pattern as Cliff).
     */
    private fun sendToLLM(text: String) {
        llmJob?.cancel()

        llmJob = viewModelScope.launch {
            _ui.update { it.copy(isThinking = true) }

            val history = ui.value.messages
            val sysMsg = _systemMessage.value

            Log.i(TAG, "LLM ⇢ sending: ${text.take(80)} (${history.size} history msgs)")

            latency.markRequestSent()

            llm.sendUserText(text = text, history = history, systemMessage = sysMsg)
                .collect { ev ->
                    when (ev) {
                        is LlmClient.Event.TextDelta -> {
                            val delta = ev.text
                            if (delta.isNotEmpty()) {
                                latency.markFirstToken()
                                _ui.update { st ->
                                    st.copy(
                                        isThinking = true,
                                        assistantLive = (st.assistantLive ?: "") + delta
                                    )
                                }
                                // Feed the engine incrementally so it starts speaking complete
                                // sentences before the full response arrives.
                                runCatching { ServiceLocator.tts.streamDelta(delta) }
                                    .onFailure { Log.w(TAG, "TTS streamDelta failed") }
                            }
                        }
                        is LlmClient.Event.TextCompleted -> {
                            // Strip markdown/newlines/emojis the model emits despite the system prompt,
                            // so TTS speaks clean prose (and the transcript shows it).
                            val finalText = sanitizeForSpeech(
                                LlmStop.trim(ev.text.ifBlank { ui.value.assistantLive.orEmpty() })
                            )
                            Log.i(TAG, "LLM completed → ${finalText.take(80)}")

                            if (finalText.isNotEmpty()) {
                                ui.value.sessionId?.let { ServiceLocator.repo.addAssistantText(it, finalText) }
                                _ui.update { st ->
                                    st.copy(
                                        isThinking = false,
                                        assistantLive = null,
                                        messages = st.messages + ("assistant" to finalText)
                                    )
                                }
                                // Sentences already streamed to TTS via streamDelta; flush the
                                // trailing partial sentence to finish the spoken response.
                                runCatching { ServiceLocator.tts.flush() }
                                    .onFailure { Log.w(TAG, "TTS flush failed") }
                            } else {
                                runCatching { ServiceLocator.tts.flush() }
                                _ui.update { it.copy(isThinking = false, assistantLive = null) }
                            }
                        }
                        is LlmClient.Event.Error -> {
                            Log.w(TAG, "LLM error: ${ev.t.message}")
                            _ui.update { it.copy(isThinking = false, error = ev.t.message) }
                        }
                    }
                }
        }
    }

    fun startSession() {
        if (ui.value.sessionActive) return

        _showVisualizer.value = true

        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = applyRouting()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = applyRouting()
        }
        am.registerAudioDeviceCallback(audioDeviceCallback, null)
        applyRouting()

        // Foreground service keeps the process alive while listening.
        VoiceAgentService.start(ServiceLocator.appContext)

        viewModelScope.launch {
            val sid = ServiceLocator.repo.newSession("Voice Chat")
            _ui.update { it.copy(sessionId = sid, sessionActive = true, error = null) }

            // ---- On-device STT (VAD-gated): partial + final → send to LLM ----
            stt.start()
            sttJob?.cancel()
            sttJob = viewModelScope.launch {
                stt.events().collect { e ->
                    barge.onSttEvent(e)
                    when (e) {
                        is SttEvent.UserStart -> _ui.update { it.copy(userSpeaking = true) }
                        is SttEvent.UserStop -> _ui.update { it.copy(userSpeaking = false) }
                        is SttEvent.Partial -> {
                            if (e.isFinal) {
                                val text = e.text.trim()
                                val lastUser = ui.value.messages.lastOrNull { it.first == "user" }?.second.orEmpty()

                                if (text.isNotEmpty() && text != lastUser && !areSimilar(text, lastUser)) {
                                    ui.value.sessionId?.let { ServiceLocator.repo.addUserText(it, text) }
                                    _ui.update { st ->
                                        st.copy(
                                            livePartial = null,
                                            messages = st.messages + ("user" to text)
                                        )
                                    }
                                    sendToLLM(text)
                                } else {
                                    _ui.update { it.copy(livePartial = null) }
                                    if (areSimilar(text, lastUser)) {
                                        Log.d(TAG, "Skipped duplicate/similar user text")
                                    }
                                }
                            } else {
                                _ui.update { it.copy(livePartial = e.text) }
                            }
                        }
                    }
                }
            }

            // ---- Start mic AFTER collectors are live ----
            // Skip our own AudioCapture when the active engine owns the mic (ML Kit fromMic) — two
            // concurrent AudioRecord instances would conflict.
            if (stt.ownsMic) {
                Log.i(TAG, "Active STT owns the mic — skipping AudioCapture (barge-in inert this session)")
            } else if (!micStarted) {
                runCatching {
                    audio.start { pcm -> stt.sendPcm(pcm) }
                }.onSuccess {
                    micStarted = true
                    Log.i(TAG, "Mic started → streaming PCM to STT")
                }.onFailure {
                    Log.e(TAG, "Failed to start mic")
                    _ui.update { s -> s.copy(error = it.message) }
                }
            }
        }
    }

    fun stopSession() {
        if (!ui.value.sessionActive) return
        _ui.update {
            it.copy(
                sessionActive = false,
                isThinking = false,
                livePartial = null,
                assistantLive = null,
                userSpeaking = false,
                ttsSpeaking = false
            )
        }
        _ttsLevel.value = 0f
        latency.reset()
        runCatching { ServiceLocator.tts.stop() }
        runCatching { llm.cancelResponse() }
        runCatching { stt.close() }
        runCatching { audio.stop() }
        micStarted = false
        sttJob?.cancel(); sttJob = null
        llmJob?.cancel(); llmJob = null
        echoSuppressClearJob?.cancel(); echoSuppressClearJob = null
        vad.echoSuppression = false
        am.mode = AudioManager.MODE_NORMAL
        audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        VoiceAgentService.stop(ServiceLocator.appContext)
        Log.i(TAG, "Session stopped")
    }
}
