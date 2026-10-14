package com.liftoff.app.coach

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One violation of each of the 13 keywords the three schema files use, each against the real schema
 * loaded from its resource file, each asserting the single line the checker must produce.
 *
 * Plain JVM test: a JSON parse and the checker, so no Robolectric. The documents are inline so a
 * test states its violation and its expected message side by side.
 */
class SchemaCheckerKeywordTest {

    // ─── type ─────────────────────────────────────────────────────────────────
    @Test
    fun typeViolationNamesPathAndExpectedAndActualType() {
        assertSingleError(
            CoachSchemas.OUTLINE,
            """{"sorties":[{"index":0,"type":"lift","focus":"Push"}],"notes":42}""",
            "notes: expected a string, got a number",
        )
    }

    /** A quoted number is a type error, not a number that happens to read as one. */
    @Test
    fun quotedNumberIsRejectedAsTheWrongType() {
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            liftPlan(minutesField = "\"estimated_minutes\":\"45\""),
            "estimated_minutes: expected an integer, got a string",
        )
    }

    /** JSON has one number type, so `45.0` means 45; a real fraction is not an integer. */
    @Test
    fun integerTypeAcceptsAWholeNumberWrittenWithADecimalAndRejectsAFraction() {
        assertEquals(
            emptyList<String>(),
            errors(CoachSchemas.LIFT_PLAN, liftPlan(minutesField = "\"estimated_minutes\":45.0")),
        )
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            liftPlan(minutesField = "\"estimated_minutes\":45.5"),
            "estimated_minutes: expected an integer, got a number",
        )
    }

    // ─── const ────────────────────────────────────────────────────────────────
    @Test
    fun constViolationNamesTheRequiredValue() {
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            liftPlan(typeField = "\"type\":\"run\""),
            "type: must be 'lift'",
        )
    }

    // ─── enum ─────────────────────────────────────────────────────────────────
    @Test
    fun enumViolationNamesTheValueAndTheAllowedValues() {
        assertSingleError(
            CoachSchemas.RUN_PLAN,
            """{"type":"run","title":"Easy Run","kind":"jog","segments":[{"description":"30 minutes easy"}],"notes":"ok"}""",
            "kind: 'jog' is not one of easy, tempo, intervals, long, recovery",
        )
    }

    /** A non-string offending value is written as its own JSON text, so the model fixes the number. */
    @Test
    fun nonStringEnumValueIsRejectedAndNamesItsJsonValue() {
        assertSingleError(
            CoachSchemas.OUTLINE,
            """{"sorties":[{"index":0,"type":5,"focus":"Push"}],"notes":"ok"}""",
            "sorties[0].type: 5 is not one of lift, run",
        )
    }

    // ─── required ─────────────────────────────────────────────────────────────
    @Test
    fun missingRequiredPropertyIsReportedOnItsObject() {
        assertSingleError(
            CoachSchemas.OUTLINE,
            """{"sorties":[{"index":0,"type":"lift"}],"notes":"ok"}""",
            "sorties[0]: missing required property 'focus'",
        )
    }

    // ─── additionalProperties ─────────────────────────────────────────────────
    @Test
    fun unexpectedPropertyIsReportedOnItsObject() {
        assertSingleError(
            CoachSchemas.OUTLINE,
            """{"sorties":[{"index":0,"type":"lift","focus":"Push","day":1}],"notes":"ok"}""",
            "sorties[0]: unexpected property 'day'",
        )
    }

    // ─── minimum / maximum ────────────────────────────────────────────────────
    @Test
    fun numberBelowMinimumIsRejected() {
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            liftPlan(minutesField = "\"estimated_minutes\":5"),
            "estimated_minutes: must be at least 10",
        )
    }

    @Test
    fun numberAboveMaximumIsRejected() {
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            liftPlan(minutesField = "\"estimated_minutes\":200"),
            "estimated_minutes: must be at most 180",
        )
    }

    /** A bound is rendered from the schema's own text, so a decimal bound prints as that decimal. */
    @Test
    fun decimalBoundIsRenderedFromTheSchemaText() {
        assertSingleError(
            CoachSchemas.RUN_PLAN,
            """{"type":"run","title":"Easy Run","kind":"easy","target_distance":0.25,""" +
                """"segments":[{"description":"30 minutes easy"}],"notes":"ok"}""",
            "target_distance: must be at least 0.5",
        )
    }

    // ─── minLength / maxLength ────────────────────────────────────────────────
    @Test
    fun stringBelowMinLengthIsRejected() {
        assertSingleError(
            CoachSchemas.OUTLINE,
            """{"sorties":[{"index":0,"type":"lift","focus":""}],"notes":"ok"}""",
            "sorties[0].focus: must be at least 1 character",
        )
    }

    @Test
    fun stringAboveMaxLengthIsRejected() {
        assertSingleError(
            CoachSchemas.OUTLINE,
            """{"sorties":[{"index":0,"type":"lift","focus":"Push"}],"notes":"${"x".repeat(1001)}"}""",
            "notes: must be at most 1000 characters",
        )
    }

    // ─── minItems / maxItems ──────────────────────────────────────────────────
    @Test
    fun arrayBelowMinItemsIsRejected() {
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            """{"type":"lift","title":"Push Day","estimated_minutes":45,""" +
                """"exercises":[{"name":"Bench Press","equipment":["barbell"],"sets":[{"reps":5}]},""" +
                """{"name":"Row","equipment":["dumbbell"],"sets":[{"reps":8}]}],"notes":"ok"}""",
            "exercises: must have at least 3 items",
        )
    }

    @Test
    fun arrayAboveMaxItemsIsRejected() {
        val nineSets = (1..9).joinToString(",") { """{"reps":$it}""" }
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            liftPlan(firstSets = "\"sets\":[$nineSets]"),
            "exercises[0].sets: must have at most 8 items",
        )
    }

    // ─── properties (nested) ──────────────────────────────────────────────────
    @Test
    fun nestedPropertyViolationNamesTheFullPath() {
        assertSingleError(
            CoachSchemas.LIFT_PLAN,
            liftPlan(firstSets = "\"sets\":[{\"reps\":0}]"),
            "exercises[0].sets[0].reps: must be at least 1",
        )
    }

    // ─── items ────────────────────────────────────────────────────────────────
    @Test
    fun arrayItemViolationNamesItsIndex() {
        assertSingleError(
            CoachSchemas.OUTLINE,
            """{"sorties":["lift"],"notes":"ok"}""",
            "sorties[0]: expected an object, got a string",
        )
    }

    /**
     * Checks [document] against the real schema at [resource] and asserts the error list is exactly
     * [expected] — one line, path first — so a keyword test cannot pass on an unrelated error.
     */
    private fun assertSingleError(resource: String, document: String, expected: String) {
        assertEquals(listOf(expected), errors(resource, document))
    }

    private fun errors(resource: String, document: String): List<String> =
        SchemaChecker.check(Json.parseToJsonElement(document), CoachSchemas.load(resource)).map { it.toString() }

    /**
     * A lift-plan document that satisfies its schema, with one field replaced so each test breaks a
     * single keyword.
     */
    private fun liftPlan(
        typeField: String = "\"type\":\"lift\"",
        minutesField: String = "\"estimated_minutes\":45",
        firstSets: String = "\"sets\":[{\"reps\":5,\"weight\":60}]",
    ): String =
        """{$typeField,"title":"Push Day",$minutesField,""" +
            """"exercises":[{"name":"Bench Press","equipment":["barbell"],$firstSets},""" +
            """{"name":"Row","equipment":["dumbbell"],"sets":[{"reps":8}]},""" +
            """{"name":"Curl","equipment":["dumbbell"],"sets":[{"reps":12}]}],"notes":"ok"}"""
}
