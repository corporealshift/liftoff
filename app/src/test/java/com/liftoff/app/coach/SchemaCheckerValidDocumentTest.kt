package com.liftoff.app.coach

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The checker on documents that satisfy each of the three real schemas, plus the order every
 * violation comes out in.
 *
 * Documents are inline rather than under `app/src/test/resources/fixtures/`, which ARCHITECTURE.md
 * reserves for recorded coach replies. Each schema is loaded from its own resource file, so the
 * schema files stay the single source of truth. Plain JVM: a JSON parse and the checker, no Robolectric.
 */
class SchemaCheckerValidDocumentTest {

    @Test
    fun validOutlineDocumentPasses() {
        assertEquals(
            emptyList<String>(),
            errors(
                CoachSchemas.OUTLINE,
                """{"sorties":[{"index":0,"type":"lift","focus":"Push + core","rationale":"Last push was 4 days ago."},""" +
                    """{"index":1,"type":"run","focus":"Easy 5k"},{"index":2,"type":"lift","focus":"Legs + pull"}],""" +
                    """"notes":"Keep the second lift light."}""",
            ),
        )
    }

    /**
     * Both shapes of every optional the lift-plan schema declares: `warmup`, an exercise's
     * `rest_seconds` and `notes`, and a set's `reps`/`seconds`/`weight`.
     */
    @Test
    fun validLiftPlanDocumentPasses() {
        assertEquals(
            emptyList<String>(),
            errors(
                CoachSchemas.LIFT_PLAN,
                """{"type":"lift","title":"Push Day","estimated_minutes":45,"warmup":"10 minutes rowing",""" +
                    """"exercises":[""" +
                    """{"name":"Bench Press","equipment":["barbell"],"rest_seconds":120,"notes":"Top set only",""" +
                    """"sets":[{"reps":5,"weight":60},{"reps":5,"weight":65},{"reps":5,"weight":70}]},""" +
                    """{"name":"Incline Dumbbell Press","equipment":["dumbbell"],"sets":[{"reps":8,"weight":22}]},""" +
                    // A set with seconds instead of reps, and no weight.
                    """{"name":"Plank Hold","equipment":["mat"],"sets":[{"seconds":45},{"seconds":60}]},""" +
                    """{"name":"Curl","equipment":["dumbbell"],"sets":[{"reps":12,"weight":12}]}],""" +
                    """"notes":"Finish with 10 minutes of core."}""",
            ),
        )
        assertEquals(
            emptyList<String>(),
            errors(
                CoachSchemas.LIFT_PLAN,
                // No warmup, no rest_seconds, no exercise notes; every set carries reps only.
                """{"type":"lift","title":"Push Day","estimated_minutes":45,""" +
                    """"exercises":[""" +
                    """{"name":"Bench Press","equipment":["barbell"],"sets":[{"reps":5}]},""" +
                    """{"name":"Row","equipment":["dumbbell"],"sets":[{"reps":8}]},""" +
                    """{"name":"Curl","equipment":["dumbbell"],"sets":[{"reps":12}]}],""" +
                    """"notes":""}""",
            ),
        )
    }

    @Test
    fun validRunPlanDocumentPasses() {
        assertEquals(
            emptyList<String>(),
            errors(
                CoachSchemas.RUN_PLAN,
                """{"type":"run","title":"Tempo Run","kind":"tempo","target_distance":5,"target_pace":"9:30/mi",""" +
                    """"segments":[{"description":"10 minutes easy","distance":1.5,"minutes":10},""" +
                    """{"description":"2 x 5 minutes at tempo","distance":2,"minutes":10},""" +
                    """{"description":"Cool down","distance":1.5,"minutes":8}],""" +
                    """"notes":"Hold the tempo, not the pace on the warm-up."}""",
            ),
        )
    }

    /**
     * One document breaking several rules gives every violation in one pass, in the order the
     * document and schema put them: a value's own keywords, then `required` in schema order, then
     * unexpected properties in document order, then declared `properties` in schema order, then
     * array items by index. An error about the document itself carries `$`, which is why the root's
     * unexpected property comes before the errors on its children.
     */
    @Test
    fun allViolationsAreReportedInDocumentOrder() {
        assertEquals(EXPECTED_ORDER, errors(CoachSchemas.LIFT_PLAN, BROKEN_LIFT_PLAN))
    }

    @Test
    fun sameInputGivesTheSameErrorList() {
        val schema = CoachSchemas.load(CoachSchemas.LIFT_PLAN)

        // Each check parses its own copy of the text, so nothing is shared between the two runs.
        val first = check(schema, BROKEN_LIFT_PLAN)
        val second = check(schema, BROKEN_LIFT_PLAN)

        assertEquals(EXPECTED_ORDER, first)
        assertEquals(first, second)
    }

    private companion object {
        val BROKEN_LIFT_PLAN =
            """{"type":"run","title":"","estimated_minutes":5,""" +
                """"exercises":[{"name":"Bench Press","equipment":["barbell"],"sets":[{"reps":0}]}],""" +
                """"notes":"ok","extra":true}"""

        val EXPECTED_ORDER = listOf(
            "\$: unexpected property 'extra'",
            "type: must be 'lift'",
            "title: must be at least 1 character",
            "estimated_minutes: must be at least 10",
            "exercises: must have at least 3 items",
            "exercises[0].sets[0].reps: must be at least 1",
        )
    }

    private fun errors(resource: String, document: String): List<String> =
        check(CoachSchemas.load(resource), document)

    private fun check(schema: CoachSchema, document: String): List<String> =
        SchemaChecker.check(Json.parseToJsonElement(document), schema).map { it.toString() }
}
