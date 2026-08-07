package com.m15.gvp.llm

/**
 * Builds the prompt string handed to MediaPipe's `addQueryChunk`. These LiteRT `.task` models are
 * instruction-tuned and expect their *own* chat template's turn tokens — feeding a generic
 * "User:/Assistant:" text format makes them behave erratically: Gemma3-1B in particular intermittently
 * emits an immediate end-of-turn (zero output tokens) instead of answering. So the prompt is formatted
 * per the model's [ChatTemplate]; this also gives generation a real stop token, which is what keeps
 * the model from running on into a hallucinated next turn.
 *
 * Keeps the last [MAX_PAIRS] exchange pairs (requirements §LLM rolling context).
 */
object PromptBuilder {

    private const val MAX_PAIRS = 5

    fun build(
        text: String,
        history: List<Pair<String, String>>,
        systemMessage: String,
        template: ChatTemplate,
    ): String {
        // Most recent turns only, then the new user turn the model must answer.
        val turns = history.takeLast(MAX_PAIRS * 2) + ("user" to text)
        return template.format(turns.map { (role, content) -> role to content.trim() }, systemMessage.trim())
    }
}

/**
 * Per-family prompt formatting. Each instruction-tuned family has distinct turn tokens; getting them
 * right is what makes the model reliably answer (and stop). Roles in [turns] are "user"/"assistant".
 */
enum class ChatTemplate {
    /** Gemma 2/3: `<start_of_turn>user … <end_of_turn>`, assistant role is "model", no system role. */
    GEMMA {
        override fun format(turns: List<Pair<String, String>>, system: String): String = buildString {
            var systemPending = system.isNotBlank()
            for ((role, content) in turns) {
                if (role == "assistant") {
                    append("<start_of_turn>model\n").append(content).append("<end_of_turn>\n")
                } else {
                    append("<start_of_turn>user\n")
                    if (systemPending) {           // Gemma has no system turn — fold it into the first user turn.
                        append(system).append("\n\n")
                        systemPending = false
                    }
                    append(content).append("<end_of_turn>\n")
                }
            }
            append("<start_of_turn>model\n")
        }
    },

    /** Qwen / ChatML: `<|im_start|>role … <|im_end|>`, with a dedicated system turn. */
    CHATML {
        override fun format(turns: List<Pair<String, String>>, system: String): String = buildString {
            if (system.isNotBlank()) append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            for ((role, content) in turns) {
                val r = if (role == "assistant") "assistant" else "user"
                append("<|im_start|>").append(r).append('\n').append(content).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    },

    /** Phi-3/4: `<|user|> … <|end|>` / `<|assistant|> … <|end|>`, with a `<|system|>` turn. */
    PHI {
        override fun format(turns: List<Pair<String, String>>, system: String): String = buildString {
            if (system.isNotBlank()) append("<|system|>\n").append(system).append("<|end|>\n")
            for ((role, content) in turns) {
                val tag = if (role == "assistant") "<|assistant|>" else "<|user|>"
                append(tag).append('\n').append(content).append("<|end|>\n")
            }
            append("<|assistant|>\n")
        }
    },

    /** Plain "User:/Assistant:" prose — for APIs that take only flat text (ML Kit GenAI Prompt API). */
    GENERIC {
        override fun format(turns: List<Pair<String, String>>, system: String): String = buildString {
            if (system.isNotBlank()) append(system).append("\n\n")
            for ((role, content) in turns) {
                append(if (role == "assistant") "Assistant" else "User").append(": ").append(content).append('\n')
            }
            append("Assistant:")
        }
    };

    abstract fun format(turns: List<Pair<String, String>>, system: String): String
}