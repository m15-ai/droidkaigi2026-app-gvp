package com.m15.gvp.llm

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for an LLM client. In GVP the implementation is the on-device Gemini Nano
 * engine (via AICore). The interface mirrors Cliff's streaming contract so the ViewModel
 * collection logic is unchanged — even though Gemini Nano may return a full response at once,
 * the engine wraps it in [Event.TextCompleted].
 */
interface LlmClient {

    /**
     * Sends user text along with conversation history and returns a flow of response events.
     * Each call is a standalone request — no server-side state.
     *
     * @param text The new user message
     * @param history Previous messages as (role, content) pairs where role is "user" or "assistant"
     * @param systemMessage The system prompt to use
     */
    fun sendUserText(
        text: String,
        history: List<Pair<String, String>> = emptyList(),
        systemMessage: String = DEFAULT_SYSTEM_MESSAGE
    ): Flow<Event>

    /** Cancel an in-flight response if supported (used by barge-in). */
    fun cancelResponse()

    /** Release resources. */
    fun close()

    sealed interface Event {
        data class TextDelta(val text: String) : Event
        data class TextCompleted(val text: String) : Event
        data class Error(val t: Throwable) : Event
    }

    companion object {
        const val DEFAULT_SYSTEM_MESSAGE =
            "You are a helpful voice assistant running entirely on-device. " +
            "Keep responses concise and conversational — they will be spoken aloud via TTS. " +
            "Limit responses to 1-3 sentences unless the user asks for detail. " +
            "Never use bullet points, numbered lists, markdown, emojis, or special formatting. " +
            "Speak in plain, natural sentences like a real conversation."
    }
}
