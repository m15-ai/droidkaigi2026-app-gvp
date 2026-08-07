package com.m15.gvp.tts

/**
 * Text-to-speech contract used by the app. Carried over from Cliff so the ViewModel is unchanged.
 *
 * In GVP the implementation is [AndroidTtsEngine] (offline Android TextToSpeech). The ViewModel
 * feeds streamed LLM tokens via [streamDelta] and calls [flush] when the response completes;
 * [AndroidTtsEngine] speaks complete sentences as they arrive (queuing utterances) so playback
 * begins before the full response is finalized. [speak] remains for one-shot, non-streamed text.
 */
interface TtsClient {
    fun speak(text: String)
    fun streamDelta(delta: String)
    fun flush()
    fun stop()
    fun close() {}
    fun isSpeaking(): Boolean

    /** ms since the current utterance began audible playback, or [Long.MAX_VALUE] if not speaking. */
    fun msSinceSpeakStarted(): Long = Long.MAX_VALUE
}
