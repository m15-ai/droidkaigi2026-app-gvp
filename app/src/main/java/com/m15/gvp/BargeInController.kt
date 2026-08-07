package com.m15.gvp

import android.util.Log
import com.m15.gvp.llm.LlmClient
import com.m15.gvp.stt.SttEvent
import com.m15.gvp.tts.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Interruption handling. When the VAD reports the user has started speaking while the assistant is
 * talking, cancel the in-flight LLM response and squelch TTS. 150 ms debounce so a brief blip
 * doesn't trigger a false barge-in. Carried over from Cliff, wired to GVP's [SttEvent] instead of
 * the Deepgram Flux event stream.
 */
class BargeInController(
    private val llm: LlmClient,
    private val tts: TtsClient
) {
    private val userSpeaking = AtomicBoolean(false)
    private var pendingJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        private const val TAG = "GVP.BargeIn"
        /** Debounce so a brief blip doesn't trigger barge-in. */
        private const val DEBOUNCE_MS = 150L
        /**
         * Ignore barge-in during the first moments of a TTS utterance. On speakerphone the mic hears
         * the assistant's own speech onset (echo) at roughly the user's volume, which would otherwise
         * self-trigger and cut the response off after the first word.
         */
        private const val TTS_ONSET_GRACE_MS = 1200L
    }

    fun onSttEvent(e: SttEvent) {
        when (e) {
            is SttEvent.UserStart -> {
                Log.i(TAG, "UserStart (wasSpeaking=${userSpeaking.get()}, ttsSpeaking=${tts.isSpeaking()})")

                if (userSpeaking.getAndSet(true)) return

                pendingJob?.cancel()
                pendingJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    if (!userSpeaking.get()) return@launch
                    // Only interrupt audio the assistant is actually speaking. While it's merely
                    // "thinking" (LLM generating, no TTS yet), a fresh utterance must NOT cancel the
                    // in-flight response — when generation was slow this killed every reply in a cascade.
                    // The new utterance still becomes the next turn via the normal STT→LLM path.
                    if (!tts.isSpeaking()) {
                        Log.i(TAG, "barge-in skipped (assistant not speaking — treated as next turn)")
                        return@launch
                    }
                    // Suppress barge-in during the TTS onset window (echo, not the user).
                    val sinceTts = tts.msSinceSpeakStarted()
                    if (sinceTts < TTS_ONSET_GRACE_MS) {
                        Log.i(TAG, "barge-in suppressed (TTS onset grace, ${sinceTts}ms)")
                        return@launch
                    }
                    Log.i(TAG, "BARGE-IN TRIGGER → cancelResponse + tts.stop()")
                    llm.cancelResponse()
                    tts.stop()
                }
            }

            is SttEvent.UserStop -> {
                Log.i(TAG, "UserStop")
                userSpeaking.set(false)
                pendingJob?.cancel()
                pendingJob = null
            }

            else -> Unit
        }
    }
}
