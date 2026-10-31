package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SortieType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.6 step 1 on its own: trim, unwrap one surrounding fence, then exactly one top-level JSON object.
 *
 * Each failure case asserts the one line the coach will read in a repair prompt, so the wording is
 * the plan's extraction table, unchanged. Plain JVM: a string and a JSON parse, no Robolectric.
 */
class CoachReplyExtractionTest {

    @Test
    fun fencedReplyIsUnwrappedToTheJsonObject() {
        // A valid object wrapped in ``` … ``` is a valid reply, and the surrounding blank lines are trimmed away.
        val document = documentOf(
            "\n  ```\n" +
                """{"type":"lift","title":"Push Day","estimated_minutes":45}""" +
                "\n  ```\n"
        )
        assertEquals("lift", (document["type"] as JsonPrimitive).content)
        assertEquals(45, (document["estimated_minutes"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun fenceWithAnInfoStringIsUnwrappedToTheJsonObject() {
        // Models write ```json almost every time; the info string is part of the fence, not content.
        val document = documentOf(
            "```json\n" +
                """{"type":"lift","title":"Push Day","notes":"Keep it light."}""" +
                "\n```"
        )
        assertEquals("Push Day", (document["title"] as JsonPrimitive).content)
        assertEquals("Keep it light.", (document["notes"] as JsonPrimitive).content)
    }

    @Test
    fun proseBeforeTheJsonObjectIsAnError() {
        val raw = "Here's the plan for Thursday: " + LIFT_PLAN
        assertEquals("\$: unexpected text before the JSON object", errorOf(raw))
        // validate's only job so far is to pass that one line through as the whole error list.
        assertEquals(listOf("\$: unexpected text before the JSON object"), validateErrors(raw))
    }

    @Test
    fun proseAfterTheJsonObjectIsAnError() {
        assertEquals("\$: unexpected text after the JSON object", errorOf(LIFT_PLAN + "\nLet me know if you want it lighter."))
        // Two separate fenced blocks are not one surrounding fence, so the second block is trailing text.
        assertEquals(
            "\$: unexpected text after the JSON object",
            errorOf("```json\n$LIFT_PLAN\n```\n\nAlso a lighter version:\n```json\n$LIFT_PLAN\n```"),
        )
    }

    @Test
    fun malformedJsonIsReportedAsNotValidJson() {
        // The braces balance, so the scanner finds the object and kotlinx is what rejects it.
        val error = errorOf("""{"type":"lift","title":"Push Day","estimated_minutes":45,"exercises":[],}""")
        assertTrue("not an extraction line: $error", error.startsWith("\$: not valid JSON: "))
        val reason = error.removePrefix("\$: not valid JSON: ")
        assertTrue("no reason given: $error", reason.isNotEmpty())
        // kotlinx echoes the document on the lines after its reason; only the reason is sent back.
        assertEquals(reason, reason.lines().single())
    }

    @Test
    fun unclosedJsonObjectIsReportedAsNotClosed() {
        assertEquals("\$: the JSON object is not closed", errorOf("""{"type":"lift","title":"Push Day"""))
        // A reply cut off inside a string never closes either.
        assertEquals("\$: the JSON object is not closed", errorOf("""{"type":"lift","notes":"Keep it """))
    }

    @Test
    fun emptyReplyIsAnError() {
        assertEquals("\$: the reply is empty", errorOf(""))
        assertEquals("\$: the reply is empty", errorOf("   \n\t  \n"))
        // A fence with nothing inside it is still an empty reply.
        assertEquals("\$: the reply is empty", errorOf("```json\n```"))
    }

    @Test
    fun nonObjectReplyNamesWhatItActuallyWas() {
        // The word comes from the first character, so the coach is told what arrived instead of an object.
        assertEquals("\$: expected a JSON object, got an array", errorOf("[1, 2, 3]"))
        assertEquals("\$: expected a JSON object, got a string", errorOf("\"Thursday, then Saturday.\""))
        assertEquals("\$: expected a JSON object, got a boolean", errorOf("true"))
        assertEquals("\$: expected a JSON object, got a number", errorOf("-45"))
    }

    @Test
    fun braceInsideAStringDoesNotEndTheObject() {
        val document = documentOf("""{"type":"lift","notes":"a } brace inside the notes","title":"Push Day"}""")
        assertEquals("a } brace inside the notes", (document["notes"] as JsonPrimitive).content)
        assertEquals("Push Day", (document["title"] as JsonPrimitive).content)
    }

    @Test
    fun escapedQuoteInsideAStringDoesNotEndTheObject() {
        val document = documentOf("""{"type":"lift","notes":"say \"light weight\" and keep going","title":"Push Day"}""")
        assertEquals("say \"light weight\" and keep going", (document["notes"] as JsonPrimitive).contentOrNull)
        assertEquals("Push Day", (document["title"] as JsonPrimitive).content)
    }

    private companion object {
        const val LIFT_PLAN =
            """{"type":"lift","title":"Push Day","estimated_minutes":45,""" +
                """"exercises":[{"name":"Bench Press","equipment":["barbell"],"sets":[{"reps":5}]}],""" +
                """"notes":"Keep it light."}"""

        val CONTEXT = GenerationContext(
            kind = GenerationKind.FLIGHT_PLAN,
            pattern = "RLRLR",
            sortieType = SortieType.LIFT,
            equipmentKeys = listOf("barbell", "dumbbell", "bench", "pullup-bar"),
            sortieLengthMinutes = 60,
        )
    }

    private fun documentOf(raw: String): JsonObject {
        val result = CoachReplyValidator.extract(raw)
        assertTrue("expected one JSON object, got $result", result is Extracted.Ok)
        return (result as Extracted.Ok).document
    }

    private fun errorOf(raw: String): String {
        val result = CoachReplyValidator.extract(raw)
        assertTrue("expected an extraction failure, got $result", result is Extracted.Failed)
        return (result as Extracted.Failed).error
    }

    private fun validateErrors(raw: String): List<String> {
        val result = CoachReplyValidator.validate(raw, CONTEXT)
        assertTrue("expected an invalid reply, got $result", result is CoachReplyResult.Invalid)
        return (result as CoachReplyResult.Invalid).errors
    }
}
