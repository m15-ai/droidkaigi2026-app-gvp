package com.m15.gvp.util

/**
 * Small instruct models (Gemma/Qwen via MediaPipe) frequently run past their own turn and start
 * emitting the *next* turn of the conversation — e.g. after answering they continue with
 * "User: Do you have any other questions? Assistant: I'm here to help…". Our [PromptBuilder] format
 * uses plain "User:"/"Assistant:" role labels with no model-native end-of-turn token, so nothing
 * halts generation. This hallucinated continuation would otherwise be spoken by TTS and saved to the
 * Room message history.
 *
 * Two distinct things have to be cleaned, and conflating them is a trap:
 *  - A *leading* echoed label ("Assistant: Hello!") — the model parroting the prompt's final
 *    "Assistant:" tag. This must be **stripped**, never treated as a turn boundary: doing so once
 *    wiped whole replies to empty.
 *  - A *subsequent* turn marker, always preceded by a newline once real content exists. Everything
 *    from there on is the hallucinated next turn and gets cut.
 */
object LlmStop {

    // The model echoing its own role label at the very start of the reply (prompt parroting).
    private val LEADING_LABEL = Regex("""^\s*(?:User|Assistant|System)\s*:\s*""")

    // A newline-introduced "User:"/"Assistant:"/"System:" label — the start of a hallucinated turn.
    private val TURN_MARKER = Regex("""\n\s*(?:User|Assistant|System)\s*:""")

    // Chat-template special tokens. If MediaPipe surfaces one as text instead of stopping, everything
    // from it on is the next turn / scaffolding — cut there. Covers Gemma, ChatML (Qwen), Phi.
    private val SPECIAL_TOKEN = Regex(
        """<end_of_turn>|<start_of_turn>|<\|im_end\|>|<\|im_start\|>|<\|end\|>|<\|user\|>|<\|assistant\|>|<\|system\|>|<eos>|<\|endoftext\|>"""
    )

    /**
     * Longest a leading label or split turn marker can run, used by the streaming caller to hold back
     * a tail that might still complete into one. Covers "\n Assistant : " with slack.
     */
    const val MAX_MARKER_LEN = 16

    /** A cleaned response and whether a hallucinated turn marker was hit (so the caller can stop). */
    data class Cleaned(val text: String, val markerHit: Boolean)

    /** Strip a leading echoed label, then cut at the first hallucinated turn marker. Not trimmed. */
    fun clean(raw: String): Cleaned {
        val body = raw.replaceFirst(LEADING_LABEL, "")
        // Earliest of: a special template token, or a newline-introduced role label.
        val cut = listOfNotNull(
            SPECIAL_TOKEN.find(body)?.range?.first,
            TURN_MARKER.find(body)?.range?.first,
        ).minOrNull()
        return if (cut != null) Cleaned(body.substring(0, cut), true) else Cleaned(body, false)
    }

    /** [raw] cleaned of a leading label and any hallucinated continuation, whitespace-trimmed. */
    fun trim(raw: String): String = clean(raw).text.trim()
}