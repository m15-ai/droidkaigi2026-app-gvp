package com.m15.gvp.stt

import kotlinx.coroutines.flow.Flow

/**
 * On-device streaming speech-to-text contract. Mirrors Cliff's FluxClient event model so the
 * ViewModel and BargeInController logic carry over unchanged. In GVP the implementation is
 * Sherpa-ONNX streaming ASR, gated by Silero VAD.
 */
interface SttEngine {
    /**
     * True if this engine captures the microphone itself (e.g. ML Kit's `AudioSource.fromMic()`),
     * so the caller must NOT also start [com.m15.gvp.audio.AudioCapture] — two concurrent AudioRecord
     * instances would conflict. When false (the default), the engine is fed PCM via [sendPcm].
     */
    val ownsMic: Boolean get() = false

    /** Begin a recognition session (open the model, reset state). */
    fun start()

    /** Feed a 16 kHz mono PCM frame from the mic. No-op for engines where [ownsMic] is true. */
    fun sendPcm(pcm: ShortArray)

    /** Stream of recognition + VAD events. */
    fun events(): Flow<SttEvent>

    /** Tear down the session and release the model. */
    fun close()
}

sealed interface SttEvent {
    /** VAD detected speech start (drives barge-in). */
    data object UserStart : SttEvent

    /** VAD detected speech end. */
    data object UserStop : SttEvent

    /** A recognition result. [isFinal] marks the end-of-utterance transcript to send to the LLM. */
    data class Partial(val text: String, val isFinal: Boolean) : SttEvent
}
