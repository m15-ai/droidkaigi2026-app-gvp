package com.m15.gvp.util

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks pipeline latency for the current conversational turn.
 *
 * "Time to first token" (TTFT) = elapsed time from when the user's final
 * transcript is dispatched to the LLM until the first response token streams
 * back. It is the dominant, user-perceptible component of the STT→LLM→TTS
 * pipeline and is surfaced at the top of the visualizer so users get a feel
 * for how the pipeline is performing.
 */
class LatencyTracker {
    private var requestSentAt: Long = 0L
    private var captured = false

    private val _ttftMs = MutableStateFlow<Long?>(null)
    /** Latest measured time-to-first-token in milliseconds, or null if none yet. */
    val ttftMs: StateFlow<Long?> = _ttftMs

    /** Mark the moment the user's final text is dispatched to the LLM. */
    fun markRequestSent() {
        requestSentAt = SystemClock.elapsedRealtime()
        captured = false
    }

    /** Mark the first streamed LLM token; records TTFT once per turn. */
    fun markFirstToken() {
        if (captured || requestSentAt == 0L) return
        captured = true
        _ttftMs.value = SystemClock.elapsedRealtime() - requestSentAt
    }

    /** Clear the displayed value (e.g. on session stop). */
    fun reset() {
        requestSentAt = 0L
        captured = false
        _ttftMs.value = null
    }
}