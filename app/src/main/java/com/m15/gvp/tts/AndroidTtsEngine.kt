package com.m15.gvp.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.m15.gvp.SupportsSpeakerphone
import com.m15.gvp.util.sanitizeForSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline TTS via Android [TextToSpeech]. Replaces Cliff's streaming Deepgram client.
 *
 * Audio-focus handling (request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK before playback, abandon after)
 * and the [SupportsSpeakerphone] hook are carried over from Cliff's DeepgramTtsClient. Android TTS
 * doesn't expose raw PCM, so the visualizer is driven at a fixed gentle level while speaking
 * (requirements §TTS, recommended option b) via [UtteranceProgressListener].
 *
 * Streaming: although a single Android TTS utterance can't be fed incrementally, [streamDelta]
 * splits the incoming token stream into sentences and enqueues each one (QUEUE_FLUSH for the first,
 * QUEUE_ADD after), so playback of sentence N starts while sentence N+1 is still being generated.
 * Audio focus and the visualizer are released only once the whole queue drains (see
 * [pendingUtterances] / [onUtteranceFinished]).
 */
class AndroidTtsEngine(
    context: Context,
    var muted: Boolean = false,
    var onAudioLevel: ((Float) -> Unit)? = null
) : TtsClient, SupportsSpeakerphone {

    private val TAG = "GVP.TTS"
    private val appContext = context.applicationContext

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val speaking = AtomicBoolean(false)
    private val utteranceSeq = AtomicInteger(0)
    @Volatile private var speakStartMs = 0L

    /** Raw streamed text not yet flushed to TTS (holds the trailing partial sentence). */
    private val streamBuffer = StringBuilder()
    /** Utterances enqueued but not yet finished; the visualizer/focus only release at zero. */
    private val pendingUtterances = AtomicInteger(0)
    /** True once a sentence of the current response has been enqueued (later ones use QUEUE_ADD). */
    @Volatile private var streamStarted = false

    @Volatile private var ready = false
    @Volatile private var speakerphoneEnabled = true
    private var focusRequest: AudioFocusRequest? = null
    private var focusGranted = false

    /** Level used to animate the visualizer while Android TTS plays (no raw PCM available). */
    private val activeLevel = 0.4f

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.US
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    speaking.set(true)
                    speakStartMs = System.currentTimeMillis()
                    onAudioLevel?.invoke(activeLevel)
                }
                override fun onDone(utteranceId: String?) = onUtteranceFinished()
                @Deprecated("deprecated in API level 21")
                override fun onError(utteranceId: String?) = onUtteranceFinished()
                override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished()
            })
            Log.i(TAG, "Android TTS ready")
        } else {
            Log.e(TAG, "Android TTS init failed (status=$status)")
        }
    }

    /** Available installed voices (for the settings voice picker). */
    fun availableVoices(): List<Voice> =
        runCatching { tts.voices?.toList().orEmpty() }.getOrDefault(emptyList())

    /** Select a voice by its [Voice.getName]; no-op if not found. */
    fun setVoiceByName(name: String?) {
        if (name.isNullOrBlank()) return
        availableVoices().firstOrNull { it.name == name }?.let { tts.voice = it }
    }

    /** Speak a complete, self-contained string at once (greetings, errors, non-streamed paths). */
    override fun speak(text: String) {
        if (muted) {
            Log.d(TAG, "muted — skipping speak")
            return
        }
        if (text.isBlank()) return
        synchronized(streamBuffer) {
            streamBuffer.setLength(0)
            streamStarted = false
            enqueue(text, flushQueue = true)
        }
    }

    /**
     * Feed a streamed LLM token. Complete sentences are spoken immediately (the first flushes any
     * prior queue, later ones append) so audio starts before the full response arrives; the trailing
     * partial sentence stays buffered until the next delta or [flush].
     */
    override fun streamDelta(delta: String) {
        if (muted || delta.isEmpty()) return
        synchronized(streamBuffer) {
            streamBuffer.append(delta)
            var boundary = lastSentenceBoundary(streamBuffer)
            while (boundary > 0) {
                val sentence = streamBuffer.substring(0, boundary)
                streamBuffer.delete(0, boundary)
                enqueue(sentence, flushQueue = !streamStarted)
                boundary = lastSentenceBoundary(streamBuffer)
            }
        }
    }

    /** End of a streamed response: speak whatever partial sentence remains and reset for the next. */
    override fun flush() {
        if (muted) return
        synchronized(streamBuffer) {
            val remaining = streamBuffer.toString()
            streamBuffer.setLength(0)
            if (remaining.isNotBlank()) enqueue(remaining, flushQueue = !streamStarted)
            streamStarted = false
        }
    }

    /**
     * Index just past the end of the last complete sentence in [sb] (punctuation + any closing
     * quotes/brackets followed by whitespace), or 0 if none. A dot not followed by whitespace
     * (decimals, "U.S.") doesn't count, which keeps most numbers and acronyms intact.
     */
    private fun lastSentenceBoundary(sb: CharSequence): Int {
        var boundary = 0
        var i = 0
        while (i < sb.length) {
            if (sb[i] == '.' || sb[i] == '!' || sb[i] == '?' || sb[i] == '…') {
                var j = i + 1
                while (j < sb.length && sb[j] in "\"')]") j++
                if (j < sb.length && sb[j].isWhitespace()) boundary = j + 1
            }
            i++
        }
        return boundary
    }

    /** Sanitize [raw] for speech and hand it to the TTS engine, tracking the pending-utterance count. */
    private fun enqueue(raw: String, flushQueue: Boolean) {
        if (!ready) return
        val clean = sanitizeForSpeech(raw)
        if (clean.isBlank()) return
        requestFocusIfNeeded()
        val id = "gvp-${utteranceSeq.incrementAndGet()}"
        pendingUtterances.incrementAndGet()
        streamStarted = true
        speaking.set(true)
        onAudioLevel?.invoke(activeLevel)
        val mode = if (flushQueue) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(clean, mode, null, id)
    }

    private fun onUtteranceFinished() {
        if (pendingUtterances.decrementAndGet() <= 0) {
            pendingUtterances.set(0)
            finishSpeaking()
        }
    }

    override fun stop() {
        runCatching { tts.stop() }
        synchronized(streamBuffer) {
            streamBuffer.setLength(0)
            streamStarted = false
        }
        pendingUtterances.set(0)
        finishSpeaking()
    }

    override fun isSpeaking(): Boolean = speaking.get()

    override fun msSinceSpeakStarted(): Long =
        if (speaking.get()) System.currentTimeMillis() - speakStartMs else Long.MAX_VALUE

    override fun close() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
        finishSpeaking()
    }

    override fun setSpeakerphoneEnabled(enabled: Boolean) {
        speakerphoneEnabled = enabled
        // Routing itself is owned by VoiceAgentViewModel.applyRouting(); kept for parity with Cliff.
    }

    private fun finishSpeaking() {
        speaking.set(false)
        onAudioLevel?.invoke(0f)
        abandonFocusIfNeeded()
    }

    private fun requestFocusIfNeeded() {
        if (focusGranted) return
        focusGranted = try {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setOnAudioFocusChangeListener { }
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = r
            audioManager.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun abandonFocusIfNeeded() {
        if (!focusGranted) return
        runCatching { focusRequest?.let { audioManager.abandonAudioFocusRequest(it) } }
        focusGranted = false
        focusRequest = null
    }
}
