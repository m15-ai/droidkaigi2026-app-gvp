package com.m15.gvp.llm

import android.content.Context
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import com.m15.gvp.util.LlmStop
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Primary on-device LLM: runs a LiteRT `.task` model via MediaPipe LLM Inference (the same runtime
 * as Google's AI Edge Gallery). Streams generated text as [LlmClient.Event.TextDelta]s and a final
 * [LlmClient.Event.TextCompleted], matching the stub/AICore contract so the ViewModel is unchanged.
 *
 * The model file must already be present (see [GvpLlmModel] / [ModelDownloader]); [initialize] loads
 * it into the inference engine. A fresh [LlmInferenceSession] is created per request because the full
 * conversation history is encoded into each prompt by [PromptBuilder] (stateless, like the others).
 */
class MediaPipeLlmEngine(context: Context) : LlmClient {

    private val TAG = "GVP.LLM"
    private val appContext = context.applicationContext

    private var llmInference: LlmInference? = null
    private val cancelled = AtomicBoolean(false)

    /** The model currently loaded (or last asked to load). Drives sampling/backend/max-tokens. */
    @Volatile private var spec: LlmModelSpec = GvpLlmModel.DEFAULT

    @Volatile private var currentSession: LlmInferenceSession? = null
    @Volatile private var currentFuture: ListenableFuture<String>? = null

    /** Human-readable status/error from the last [initialize] (for the Setup debug line). */
    @Volatile var detail: String? = null
        private set

    fun isModelDownloaded(model: LlmModelSpec): Boolean = model.isDownloaded(appContext)

    /** Load the given downloaded model into the inference engine. @return true on success. */
    fun initialize(model: LlmModelSpec): Boolean {
        spec = model
        val modelFile = model.resolve(appContext)
        if (modelFile == null) {
            detail = "${model.displayName} not downloaded"
            return false
        }
        return try {
            // Reloading a different model: tear down the previous engine first.
            runCatching { llmInference?.close() }
            llmInference = null
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(model.maxTokens)
                .setPreferredBackend(model.backend)
                .build()
            llmInference = LlmInference.createFromOptions(appContext, options)
            Log.i(TAG, "MediaPipe LLM ready (${model.displayName}, ${model.backend})")
            detail = "MediaPipe: ${model.displayName} (${model.backend})"
            true
        } catch (t: Throwable) {
            Log.e(TAG, "MediaPipe init failed", t)
            detail = "MediaPipe load failed — ${(t.message ?: t.javaClass.simpleName).take(120)}"
            false
        }
    }

    private fun newSessionOptions(): LlmInferenceSession.LlmInferenceSessionOptions =
        LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTemperature(spec.temperature)
            .setTopK(spec.topK)
            .setTopP(spec.topP)
            .build()

    override fun sendUserText(
        text: String,
        history: List<Pair<String, String>>,
        systemMessage: String
    ): Flow<LlmClient.Event> = callbackFlow {
        val inference = llmInference
        if (inference == null) {
            trySend(LlmClient.Event.Error(IllegalStateException("MediaPipe model not initialized")))
            close()
            return@callbackFlow
        }
        cancelled.set(false)

        val prompt = PromptBuilder.build(text, history, systemMessage, spec.template)
        Log.d(TAG, "MediaPipe ⇢ ${text.take(80)} (${history.size} history msgs, ${prompt.length} chars)")

        val session = try {
            LlmInferenceSession.createFromOptions(inference, newSessionOptions())
        } catch (t: Throwable) {
            Log.e(TAG, "session create failed", t)
            trySend(LlmClient.Event.Error(t))
            close()
            return@callbackFlow
        }
        currentSession = session

        val sb = StringBuilder()
        // Chars of *cleaned* text already emitted as deltas (clean coords, not raw-buffer coords):
        // cleaning strips a leading echoed label, so raw and clean indices differ.
        val emitted = AtomicInteger(0)
        // Completion can be signalled by either the progress callback (done=true) or the future
        // resolving — whichever wins. Guard so TextCompleted is sent exactly once and the channel
        // isn't closed out from under a pending send (that race dropped the completion → no TTS).
        val finished = AtomicBoolean(false)
        fun finish(fullText: String) {
            if (finished.compareAndSet(false, true)) {
                val clean = LlmStop.clean(fullText).text
                // Flush the tail pump() held back as a possible split marker/label. TTS is driven
                // only by deltas + flush(), and completion can arrive via the future without a final
                // pump(), so without this the last words never reach TTS (text window still shows them).
                val start = emitted.get()
                if (clean.length > start) {
                    trySend(LlmClient.Event.TextDelta(clean.substring(start)))
                    emitted.set(clean.length)
                }
                val completed = clean.trim()
                Log.i(TAG, "MediaPipe completed → ${completed.take(80)}")
                trySend(LlmClient.Event.TextCompleted(completed))
                close()
            }
        }

        // Emit only newly-arrived clean text. Holds back a short tail that might still complete into a
        // turn marker or leading label (either can straddle two deltas), and stops at the first real
        // marker so the hallucinated next turn never reaches TTS/UI. @return true once a marker hit.
        fun pump(done: Boolean): Boolean = synchronized(sb) {
            val cleaned = LlmStop.clean(sb.toString())
            val text = cleaned.text
            val safeEnd = when {
                cleaned.markerHit -> text.length
                done -> text.length
                else -> (text.length - LlmStop.MAX_MARKER_LEN).coerceAtLeast(emitted.get())
            }
            if (safeEnd > emitted.get()) {
                trySend(LlmClient.Event.TextDelta(text.substring(emitted.get(), safeEnd)))
                emitted.set(safeEnd)
            }
            cleaned.markerHit
        }

        val listener = ProgressListener<String> { partial, done ->
            if (cancelled.get()) return@ProgressListener
            if (!partial.isNullOrEmpty()) synchronized(sb) { sb.append(partial) }
            val hitMarker = pump(done)
            if (hitMarker) {
                // Halt the run so we stop burning compute on the hallucinated continuation.
                runCatching { currentFuture?.cancel(true) }
                finish(sb.toString())
            } else if (done) {
                finish(sb.toString())
            }
        }

        val future = try {
            session.addQueryChunk(prompt)
            session.generateResponseAsync(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed", t)
            trySend(LlmClient.Event.Error(t))
            close()
            return@callbackFlow
        }
        currentFuture = future

        // The future resolves with the full text. Complete from here if the progress callback didn't
        // already, and surface any generation error.
        future.addListener({
            try {
                val full = future.get()
                if (cancelled.get()) close() else finish(full)
            } catch (t: Throwable) {
                // A marker-stop cancels the future on purpose; don't surface that as an error.
                if (!cancelled.get() && !finished.get()) {
                    Log.e(TAG, "generation error", t)
                    trySend(LlmClient.Event.Error(t.cause ?: t))
                }
                close()
            }
        }, Executor { it.run() })

        awaitClose {
            runCatching { session.close() }
            if (currentSession === session) currentSession = null
            if (currentFuture === future) currentFuture = null
        }
    }

    override fun cancelResponse() {
        // Barge-in: stop emitting and tear down the in-flight session so the next turn starts clean.
        cancelled.set(true)
        runCatching { currentFuture?.cancel(true) }
        runCatching { currentSession?.close() }
        currentSession = null
        currentFuture = null
    }

    override fun close() {
        runCatching { currentSession?.close() }
        runCatching { llmInference?.close() }
        currentSession = null
        currentFuture = null
        llmInference = null
    }
}