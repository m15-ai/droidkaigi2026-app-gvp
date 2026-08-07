package com.m15.gvp.audio

import android.util.Log
import kotlin.math.sqrt

/**
 * Voice-activity gate that brackets utterances for the STT engine.
 *
 * TODO(real-integration): replace the RMS-energy heuristic below with the real Silero VAD ONNX
 * model run through Sherpa-ONNX's VAD API (~2 MB model). The public surface (process/reset and
 * the [Transition] result) is what the STT engine depends on, so the swap is local to this file.
 *
 * v1 stub: a frame is "voiced" when its normalized RMS exceeds [energyThreshold]. Speech starts
 * on the first voiced frame and ends after [silenceThresholdMs] of continuous silence.
 */
class SileroVad(
    @Volatile var silenceThresholdMs: Long = 1_500L,
    // Idle noise floor on the test device measured ~0.018–0.026 RMS, which self-triggered at the old
    // 0.012 bar. Raised so ambient/mic noise doesn't fake an utterance. Live-tunable from Settings
    // for per-device calibration.
    @Volatile var energyThreshold: Float = 0.03f,
    // Barge-in robustness (full-duplex). While the assistant is speaking the mic also picks up the
    // assistant's own voice — the residual echo the hardware AEC doesn't fully cancel. That echo is
    // *sustained* for the whole utterance, so a longer debounce alone can't reject it; the
    // discriminator is loudness. A user talking into the phone is louder than the residual echo, so
    // during TTS we raise the voiced bar to [bargeInEnergyThreshold] and require the loud input to
    // persist for [bargeInOnsetMs] before declaring speech. Tune both per device using the
    // "barge-in candidate rms=…" debug logs (raise the threshold above the logged echo level).
    // Calibrated on the Pixel 10 / Tensor G5 (with the 8× capture gain): AEC residual echo during TTS
    // sits at ~0.001–0.002 RMS (rare transient ~0.011), while a barge-in "STOP" peaks ~0.04–0.07. The
    // bar goes between them — 0.025 clears echo comfortably yet catches the user's voice. (The old 0.20
    // was tuned for a different device whose speakerphone echo ran 0.06–0.14 RMS; here it was ~3× above
    // the user's own voice, so barge-in never fired.) Live-tunable from Settings.
    @Volatile var bargeInEnergyThreshold: Float = 0.025f,
    // The loud input must persist this long during TTS before it counts as a barge-in. On the Pixel 10
    // the threshold alone separates voice (0.04–0.08) from echo (≤0.011), so this only needs to reject
    // single-frame transients — kept short because the hardware AEC chops the near-end into brief bursts
    // during double-talk (a 150ms onset never accumulated; "STOP" stalled at ~104ms). The run is NOT
    // required to be continuous: sub-threshold dips shorter than [bargeInOnsetGapMs] don't reset it, so
    // a wide gap bridges the AEC's chopping. Onset is wall-clock from the first voiced frame of the run.
    private val bargeInOnsetMs: Long = 60L,
    private val bargeInOnsetGapMs: Long = 400L
) {
    private val TAG = "GVP.VAD"

    enum class Transition { NONE, SPEECH_START, SPEECH_END }

    /**
     * Set true while TTS is playing (toggled from the TTS lifecycle). Switches the gate to the
     * louder, sustained barge-in criteria above so the assistant's echo can't self-trigger a turn.
     */
    @Volatile var echoSuppression: Boolean = false

    private var speaking = false
    private var lastVoiceAtMs = 0L
    private var voicedRunStartMs = 0L
    private var logThrottle = 0

    fun reset() {
        speaking = false
        lastVoiceAtMs = 0L
        voicedRunStartMs = 0L
    }

    /** Feed a 16 kHz mono PCM frame; returns any speech-boundary transition it triggers. */
    fun process(pcm: ShortArray, nowMs: Long): Transition {
        val rms = rms01(pcm)
        val suppressing = echoSuppression
        val threshold = if (suppressing) bargeInEnergyThreshold else energyThreshold
        val voiced = rms >= threshold

        if (voiced) {
            if (!speaking) {
                // Start a new onset run, or continue the current one. A gap longer than
                // bargeInOnsetGapMs since the last voiced frame restarts the run; shorter dips
                // (normal speech) are bridged so the run can accumulate to the onset window. In
                // normal mode the onset window is 0, so this fires on the first voiced frame.
                val gap = nowMs - lastVoiceAtMs
                if (voicedRunStartMs == 0L || gap > bargeInOnsetGapMs) {
                    voicedRunStartMs = nowMs
                }
                lastVoiceAtMs = nowMs
                val onsetMs = if (suppressing) bargeInOnsetMs else 0L
                if (suppressing && logThrottle++ % 10 == 0) {
                    Log.d(TAG, "barge-in candidate rms=%.3f (thr=%.3f, held %dms/%dms)".format(
                        rms, threshold, nowMs - voicedRunStartMs, onsetMs))
                }
                if (nowMs - voicedRunStartMs >= onsetMs) {
                    speaking = true
                    voicedRunStartMs = 0L
                    Log.d(TAG, "speech start (rms=%.3f, suppressing=%b)".format(rms, suppressing))
                    return Transition.SPEECH_START
                }
                return Transition.NONE
            }
            lastVoiceAtMs = nowMs
            return Transition.NONE
        }

        // Not voiced. Abandon a pending onset run only once the dip exceeds the gap tolerance, so
        // brief intra-word silences don't keep resetting it.
        if (voicedRunStartMs != 0L && nowMs - lastVoiceAtMs > bargeInOnsetGapMs) {
            voicedRunStartMs = 0L
        }

        if (speaking && nowMs - lastVoiceAtMs >= silenceThresholdMs) {
            speaking = false
            Log.d(TAG, "speech end (silence ${nowMs - lastVoiceAtMs} ms)")
            return Transition.SPEECH_END
        }
        return Transition.NONE
    }

    private fun rms01(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0.0
        for (s in pcm) {
            val v = s / 32768.0
            sum += v * v
        }
        return sqrt(sum / pcm.size).toFloat()
    }
}
