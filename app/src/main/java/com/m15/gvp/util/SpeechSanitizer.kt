package com.m15.gvp.util

/**
 * Cleans raw LLM output into plain prose for TTS (and the transcript). Small on-device models often
 * ignore the "no markdown / no lists" system prompt and emit markdown, list markers, literal "\n"
 * escapes, or emojis — all of which the TTS engine reads aloud verbatim or as awkward pauses.
 */
private val LITERAL_ESCAPES = Regex("""\\[nrt]""")            // literal backslash-n/r/t in the text
private val WHITESPACE_RUNS = Regex("""[\r\n\t]+""")
private val MD_EMPHASIS = Regex("""\*\*|__|\*|_|`{1,3}|~~""") // bold/italic/code/strikethrough markers
private val MD_HEADER = Regex("""(?m)^\s{0,3}#{1,6}\s*""")
private val MD_BULLET = Regex("""(?m)^\s*[-*•]\s+""")
private val MD_NUMBERED = Regex("""(?m)^\s*\d+[.)]\s+""")
private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")        // [text](url) -> text
private val EMOJI = Regex(
    "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}" +
        "\\x{2190}-\\x{21FF}\\x{1F1E6}-\\x{1F1FF}\\x{FE00}-\\x{FE0F}\\u200D]"
)
private val MULTISPACE = Regex(""" {2,}""")

fun sanitizeForSpeech(raw: String): String {
    var s = raw
    s = LITERAL_ESCAPES.replace(s, " ")
    s = MD_LINK.replace(s, "$1")
    s = MD_HEADER.replace(s, "")
    s = MD_BULLET.replace(s, "")
    s = MD_NUMBERED.replace(s, "")
    s = MD_EMPHASIS.replace(s, "")
    s = EMOJI.replace(s, "")
    s = WHITESPACE_RUNS.replace(s, " ")
    s = MULTISPACE.replace(s, " ")
    return s.trim()
}