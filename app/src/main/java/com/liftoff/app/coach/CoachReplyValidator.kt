package com.liftoff.app.coach

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** §7.6 step 1's outcome: the one JSON object the reply holds, or the single line saying why it does not. */
internal sealed interface Extracted {

    data class Ok(val document: JsonObject) : Extracted

    /** Already a `path: problem` line, because an extraction failure is always about the document itself. */
    data class Failed(val error: String) : Extracted
}

/**
 * The phone's authority over a coach reply (§7.6). Pure Kotlin: the error lines go into a repair
 * prompt unchanged, so they share `SchemaChecker`'s `path: problem` vocabulary.
 */
object CoachReplyValidator {

    fun validate(raw: String, context: GenerationContext): CoachReplyResult {
        return when (val extracted = extract(raw)) {
            is Extracted.Failed -> CoachReplyResult.Invalid(listOf(extracted.error))
            // The schema and semantic passes are the next step, so a cleanly extracted document has nothing to report yet.
            is Extracted.Ok -> CoachReplyResult.Invalid(emptyList())
        }
    }

    /**
     * Trim, unwrap one surrounding Markdown code fence, then find exactly one top-level JSON object.
     * The object is located by depth-counting rather than by parsing, so prose around it and a reply
     * that never closes get their own lines instead of kotlinx's one catch-all message.
     */
    internal fun extract(raw: String): Extracted {
        val text = unwrapFence(raw.trim()).trim()
        if (text.isEmpty()) return failed("the reply is empty")

        val start = text.indices.first { !text[it].isWhitespace() }
        if (text[start] != '{') {
            // Prose is only "before the JSON object" when there is an object to be before; a reply that
            // is some other JSON value names itself.
            val proseAhead = !isStartOfAValue(text[start]) && firstBraceOutsideAString(text) >= 0
            return failed(
                if (proseAhead) "unexpected text before the JSON object"
                else "expected a JSON object, got ${wordFor(text[start])}"
            )
        }

        val end = closingBrace(text, start) ?: return failed("the JSON object is not closed")
        if (text.substring(end + 1).any { !it.isWhitespace() }) {
            return failed("unexpected text after the JSON object")
        }

        val document = try {
            Json.parseToJsonElement(text.substring(start, end + 1))
        } catch (e: SerializationException) {
            // kotlinx echoes the whole document on the lines after the reason; a repair prompt needs the reason.
            return failed("not valid JSON: ${e.message?.lineSequence()?.first()?.trim() ?: e}")
        }
        if (document !is JsonObject) return failed("expected a JSON object, got ${wordFor(text[start])}")

        return Extracted.Ok(document)
    }

    /**
     * Only the outermost pair comes off: the first line is 3 or more backticks with an optional info
     * string, the last line is at least that many backticks and nothing else. Tildes are not a fence.
     */
    private fun unwrapFence(text: String): String {
        val lines = text.split('\n')
        if (lines.size < 2) return text
        val opening = FENCE.matchEntire(lines[0]) ?: return text
        val ticks = opening.groupValues[1].length
        val closing = lines[lines.lastIndex].trim()
        if (closing.length < ticks || closing.any { it != '`' }) return text
        return lines.subList(1, lines.lastIndex).joinToString("\n")
    }

    /** Index of the first `{` that is not inside a string, or -1 when the text holds none. */
    private fun firstBraceOutsideAString(text: String): Int {
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val char = text[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            if (char == '"') inString = true
            else if (char == '{') return index
        }
        return -1
    }

    /** Index of the `}` that closes the object opened at [open], or null when it never closes. */
    private fun closingBrace(text: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until text.length) {
            val char = text[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun isStartOfAValue(char: Char): Boolean =
        char == '[' || char == '"' || char == 't' || char == 'f' || char == 'n' || char.isDigit() || char == '-'

    private fun wordFor(char: Char): String = when {
        char == '[' -> "an array"
        char == '"' -> "a string"
        char == 't' || char == 'f' -> "a boolean"
        char.isDigit() || char == '-' -> "a number"
        else -> "text"
    }

    private fun failed(problem: String): Extracted.Failed = Extracted.Failed("${SchemaChecker.ROOT}: $problem")

    // The info string is a bare word (`json`, `kotlin`); a backtick or whitespace in it means the line is not a fence.
    private val FENCE = Regex("(`{3,})[^`\\s]*[ \\t]*")
}
