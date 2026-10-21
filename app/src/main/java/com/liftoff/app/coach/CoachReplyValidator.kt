package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SortieType
import com.liftoff.app.domain.sortieTypesOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        val document = when (val extracted = extract(raw)) {
            is Extracted.Failed -> return CoachReplyResult.Invalid(listOf(extracted.error))
            is Extracted.Ok -> extracted.document
        }

        // A plan of the wrong kind is the one decisive mistake; the schema errors it would trigger are derivative.
        planTypeError(document, context)?.let { return CoachReplyResult.Invalid(listOf(it)) }

        val errors = SchemaChecker.check(document, schemaFor(context)).map { it.toString() } +
            semanticErrors(document, context)
        if (errors.isNotEmpty()) return CoachReplyResult.Invalid(errors)

        // `document` is what `extract` pulled out of the reply, so its text is the validated JSON (§8).
        return CoachReplyResult.Valid(plan = planOf(document, context), json = document.toString())
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

    // ─── §7.6 step 2: the schema for what was asked for ─────────────────────────

    /** The schema is picked from the request, not from the reply: the reply is what is on trial. */
    private fun schemaFor(context: GenerationContext): CoachSchema =
        when (context.kind) {
            GenerationKind.OUTLINE -> outlineSchema
            GenerationKind.FLIGHT_PLAN ->
                if (context.sortieType == SortieType.RUN) runPlanSchema else liftPlanSchema
        }

    /**
     * A well-formed `lift`/`run` of the wrong kind gets its one line and stops. Any other `type`
     * (absent, not a string, `"swim"`) is the checker's to report, once.
     */
    private fun planTypeError(document: JsonObject, context: GenerationContext): String? {
        if (context.kind != GenerationKind.FLIGHT_PLAN) return null
        val wanted = context.sortieType ?: return null
        val declared = typeWordOf(document) ?: return null
        if (declared == wanted) return null
        return "type: '${word(declared)}' does not match the ${word(wanted)} sortie being planned"
    }

    /** The document's `type` when it is one of the two words the schemas know. */
    private fun typeWordOf(document: JsonObject): SortieType? =
        when ((document["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "lift" -> SortieType.LIFT
            "run" -> SortieType.RUN
            else -> null
        }

    // ─── §7.6 step 3: the semantic checks ───────────────────────────────────────

    /**
     * These run over the nodes whose declared type matched, so a node the schema already rejected is
     * not re-checked inside. A document with both a schema violation and a semantic one gets both
     * lines, because §7.3's two repairs need them named at once.
     */
    private fun semanticErrors(document: JsonObject, context: GenerationContext): List<String> =
        when (context.kind) {
            GenerationKind.OUTLINE -> outlineErrors(document, context)
            // §7.5.3 gives a run plan nothing to check beyond its type matching the sortie.
            GenerationKind.FLIGHT_PLAN ->
                if (context.sortieType == SortieType.LIFT) liftErrors(document, context) else emptyList()
        }

    private fun outlineErrors(document: JsonObject, context: GenerationContext): List<String> {
        val errors = mutableListOf<String>()
        val sorties = document["sorties"] as? JsonArray ?: return errors
        val pattern = sortieTypesOf(context.pattern)

        if (sorties.size != pattern.size) {
            errors += "sorties: expected ${pattern.size} sorties for the pattern ${context.pattern}, got ${sorties.size}"
        }

        // The entries that exist are checked whether or not the count matched.
        sorties.forEachIndexed { position, entry ->
            val sortie = entry as? JsonObject ?: return@forEachIndexed

            val index = sortie.integerOrNull("index")
            if (index != null && index != position) {
                errors += "sorties[$position].index: must be $position"
            }

            // Only a word the enum already accepts is compared with the pattern, so a bad word is named once.
            val declared = (sortie["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (declared != null && declared in TYPE_WORDS && position < pattern.size && declared != word(pattern[position])) {
                errors += "sorties[$position].type: must be '${word(pattern[position])}' at position $position of the pattern ${context.pattern}"
            }
        }
        return errors
    }

    private fun liftErrors(document: JsonObject, context: GenerationContext): List<String> {
        val errors = mutableListOf<String>()

        budgetError(document, context)?.let { errors += it }

        val exercises = document["exercises"] as? JsonArray ?: return errors
        val duplicates = mutableListOf<String>()
        val firstByName = mutableMapOf<String, Pair<Int, String>>()

        exercises.forEachIndexed { position, exercise ->
            val node = exercise as? JsonObject ?: return@forEachIndexed
            setErrors(node, position, errors)
            equipmentErrors(node, position, context, errors)

            val name = (node["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name != null) {
                // §7.7 stores each exercise once by normalized name, so two spellings are one exercise.
                val first = firstByName.putIfAbsent(ExerciseNames.normalize(name), position to name)
                if (first != null) {
                    duplicates += "exercises[$position].name: '$name' duplicates '${first.second}' at exercises[${first.first}]"
                }
            }
        }

        errors += duplicates
        return errors
    }

    private fun budgetError(document: JsonObject, context: GenerationContext): String? {
        // Only a value the schema's `integer` type accepted is budgeted, so a fractional or quoted
        // estimated_minutes is reported once, by the checker.
        val minutes = document.integerOrNull("estimated_minutes") ?: return null
        val limit = 1.25 * context.sortieLengthMinutes
        if (minutes <= limit) return null
        return "estimated_minutes: $minutes is more than ${number(limit)} " +
            "(1.25 × the ${context.sortieLengthMinutes}-minute sortie length)"
    }

    private fun setErrors(exercise: JsonObject, position: Int, errors: MutableList<String>) {
        val sets = exercise["sets"] as? JsonArray ?: return
        sets.forEachIndexed { index, set ->
            val node = set as? JsonObject ?: return@forEachIndexed
            val hasReps = node.isSet("reps")
            val hasSeconds = node.isSet("seconds")
            when {
                hasReps && hasSeconds -> errors += "exercises[$position].sets[$index]: reps and seconds cannot both be set"
                !hasReps && !hasSeconds -> errors += "exercises[$position].sets[$index]: set must have either reps or seconds"
            }
        }
    }

    private fun equipmentErrors(exercise: JsonObject, position: Int, context: GenerationContext, errors: MutableList<String>) {
        val ids = exercise["equipment"] as? JsonArray ?: return
        for (id in ids) {
            val word = (id as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            // The context carries active keys only, so a deactivated key reads as an unknown one.
            if (word !in context.equipmentKeys) {
                errors += "exercises[$position].equipment: $word is not in the equipment list"
            }
        }
    }

    // ─── §7.6 step 4: the document mapped into coach types ──────────────────────

    private fun planOf(document: JsonObject, context: GenerationContext): CoachPlan =
        when (context.kind) {
            GenerationKind.OUTLINE -> outlinePlan(document)
            GenerationKind.FLIGHT_PLAN ->
                if (context.sortieType == SortieType.RUN) runPlan(document) else liftPlan(document)
        }

    private fun outlinePlan(document: JsonObject): CoachOutline {
        val sorties = (document["sorties"] as JsonArray).map { entry ->
            val sortie = entry as JsonObject
            CoachSortieFocus(
                index = sortie.integer("index"),
                sortieType = if (sortie.text("type") == "run") SortieType.RUN else SortieType.LIFT,
                focus = sortie.text("focus"),
                rationale = sortie.optionalText("rationale"),
            )
        }
        return CoachOutline(sorties = sorties, notes = document.text("notes"))
    }

    private fun liftPlan(document: JsonObject): CoachLiftPlan {
        val exercises = (document["exercises"] as JsonArray).map { entry ->
            val exercise = entry as JsonObject
            CoachExercise(
                name = exercise.text("name"),
                equipment = (exercise["equipment"] as JsonArray).map { (it as JsonPrimitive).content },
                sets = (exercise["sets"] as JsonArray).map { set ->
                    val node = set as JsonObject
                    CoachSet(
                        reps = node.optionalInteger("reps"),
                        seconds = node.optionalInteger("seconds"),
                        weight = node.optionalNumber("weight"),
                    )
                },
                restSeconds = exercise.optionalInteger("rest_seconds"),
                notes = exercise.optionalText("notes"),
            )
        }
        return CoachLiftPlan(
            title = document.text("title"),
            estimatedMinutes = document.integer("estimated_minutes"),
            warmup = document.optionalText("warmup"),
            exercises = exercises,
            notes = document.text("notes"),
        )
    }

    private fun runPlan(document: JsonObject): CoachRunPlan {
        val segments = (document["segments"] as JsonArray).map { entry ->
            val segment = entry as JsonObject
            CoachSegment(
                description = segment.text("description"),
                distance = segment.optionalNumber("distance"),
                minutes = segment.optionalNumber("minutes"),
            )
        }
        return CoachRunPlan(
            title = document.text("title"),
            kind = document.text("kind"),
            targetDistance = document.optionalNumber("target_distance"),
            targetPace = document.optionalText("target_pace"),
            segments = segments,
            notes = document.text("notes"),
        )
    }

    // ─── shared bits ────────────────────────────────────────────────────────────

    /** Only the outermost pair comes off: the first line is 3 or more backticks with an optional info
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

    /** A key counts as set when it is there and is not JSON null. */
    private fun JsonObject.isSet(name: String): Boolean {
        val value = this[name] ?: return false
        return value !is JsonNull
    }

    private fun JsonObject.text(name: String): String = (this[name] as JsonPrimitive).content

    private fun JsonObject.optionalText(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.integer(name: String): Int = (this[name] as JsonPrimitive).content.toDouble().toInt()

    /** An Int only when the schema's `integer` type would accept the value, i.e. a number that is whole. */
    private fun JsonObject.integerOrNull(name: String): Int? {
        val value = (this[name] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull()
            ?: return null
        return if (value.rem(1.0) == 0.0) value.toInt() else null
    }

    private fun JsonObject.optionalInteger(name: String): Int? = integerOrNull(name)

    private fun JsonObject.optionalNumber(name: String): Double? =
        (this[name] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.content?.toDoubleOrNull()

    private fun word(sortieType: SortieType): String = if (sortieType == SortieType.RUN) "run" else "lift"

    /** A whole number is written whole; a fractional limit keeps its fraction. */
    private fun number(value: Double): String =
        if (value.rem(1.0) == 0.0) value.toLong().toString() else value.toString()

    private val TYPE_WORDS = setOf("lift", "run")

    // One generation should not re-read the classpath for the same schema.
    private val outlineSchema by lazy { CoachSchemas.load(CoachSchemas.OUTLINE) }
    private val liftPlanSchema by lazy { CoachSchemas.load(CoachSchemas.LIFT_PLAN) }
    private val runPlanSchema by lazy { CoachSchemas.load(CoachSchemas.RUN_PLAN) }

    // The info string is a bare word (`json`, `kotlin`); a backtick or whitespace in it means the line is not a fence.
    private val FENCE = Regex("(`{3,})[^`\\s]*[ \\t]*")
}
