package com.m15.gvp.llm

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Canned-response LLM used as the fallback when Gemini Nano is unavailable on the device (see
 * [GeminiNanoEngine]). Returns a fixed reply, streamed word-by-word so the ViewModel's streaming
 * collection, TTFT latency marker, and barge-in cancellation all behave as they will with the real
 * engine.
 */
class StubLlmEngine : LlmClient {

    private val TAG = "GVP.LLM"
    private val cancelled = AtomicBoolean(false)

    override fun sendUserText(
        text: String,
        history: List<Pair<String, String>>,
        systemMessage: String
    ): Flow<LlmClient.Event> = flow {
        cancelled.set(false)
        Log.d(TAG, "Stub LLM ⇢ prompt: ${text.take(80)} (${history.size} history msgs)")

        val reply = "This is a placeholder on-device response. " +
            "Gemini Nano is unavailable on this device, so the stub engine is responding."

        // Simulate token streaming so TTFT + UI streaming behave realistically.
        val words = reply.split(" ")
        val sb = StringBuilder()
        for ((i, w) in words.withIndex()) {
            if (cancelled.get()) {
                Log.d(TAG, "Stub LLM cancelled mid-stream")
                return@flow
            }
            val chunk = if (i == 0) w else " $w"
            sb.append(chunk)
            emit(LlmClient.Event.TextDelta(chunk))
            delay(40)
        }
        if (!cancelled.get()) {
            emit(LlmClient.Event.TextCompleted(sb.toString()))
        }
    }

    override fun cancelResponse() {
        cancelled.set(true)
    }

    override fun close() {
        cancelled.set(true)
    }
}