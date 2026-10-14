package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §7.6 step 3's semantic checks, one document inline per rule, each asserting the exact
 * `path: problem` list — these lines are pasted into a repair prompt unchanged (§7.3 step 5), so a
 * test that produced a second, unexpected error would be as much of a failure as a wrong wording.
 *
 * Every document is checked against one fixed context: pattern `RLRLR` (positions 0, 2, 4 are run),
 * the four active equipment keys, and a 60-minute sortie, so a 75-minute budget.
 */
class CoachReplySemanticTest {

    // ─── outline: one entry per pattern position ────────────────────────────────

    @Test
    fun outlineEntryCountNamesThePatternAndBothCounts() {
        // Four entries for a five-position pattern: the line names the pattern and both numbers.
        assertEquals(
            listOf("sorties: expected 5 sorties for the pattern RLRLR, got 4"),
            errorsOf(outline(S0, S1, S2, S3), OUTLINE_CONTEXT),
        )
        // A sixth day is a week the app cannot store, and reads the same way.
        assertEquals(
            listOf("sorties: expected 5 sorties for the pattern RLRLR, got 6"),
            errorsOf(outline(S0, S1, S2, S3, S4, """{"index":5,"type":"lift","focus":"Core"}"""), OUTLINE_CONTEXT),
        )
    }

    @Test
    fun outlineIndexMustMatchItsPosition() {
        // The fourth entry calls itself 4; the entry after it is where the pattern says it should be.
        assertEquals(
            listOf("sorties[3].index: must be 3"),
            errorsOf(
                outline(
                    S0, S1, S2,
                    """{"index":4,"type":"lift","focus":"Legs + pull"}""",
                    S4,
                ),
                OUTLINE_CONTEXT,
            ),
        )
    }

    @Test
    fun outlineTypeMustMatchThePatternAtItsPosition() {
        // `lift` is a legal word in the schema; at position 2 of RLRLR it is still the wrong day.
        assertEquals(
            listOf("sorties[2].type: must be 'run' at position 2 of the pattern RLRLR"),
            errorsOf(
                outline(
                    S0, S1,
                    """{"index":2,"type":"lift","focus":"Legs + pull"}""",
                    S3, S4,
                ),
                OUTLINE_CONTEXT,
            ),
        )
    }

    // ─── lift: sets, equipment, budget, names ───────────────────────────────────

    @Test
    fun setWithBothRepsAndSecondsIsRejected() {
        assertEquals(
            listOf("exercises[0].sets[1]: reps and seconds cannot both be set"),
            errorsOf(
                liftPlan(
                    listOf(
                        """{"name":"Bench Press","equipment":["barbell","bench"],""" +
                            """"sets":[{"reps":5,"weight":60},{"reps":5,"seconds":30}]}""",
                        ROW, CURL,
                    ),
                ),
                LIFT_CONTEXT,
            ),
        )
    }

    @Test
    fun setWithNeitherRepsNorSecondsIsRejected() {
        // The set schema declares no `required`, so only the semantic check catches a set with no target.
        assertEquals(
            listOf("exercises[0].sets[1]: set must have either reps or seconds"),
            errorsOf(
                liftPlan(
                    listOf(
                        """{"name":"Bench Press","equipment":["barbell","bench"],""" +
                            """"sets":[{"reps":5,"weight":60},{"weight":60}]}""",
                        ROW, CURL,
                    ),
                ),
                LIFT_CONTEXT,
            ),
        )
    }

    @Test
    fun equipmentIdNotInTheListNamesTheIdAndItsPath() {
        assertEquals(
            listOf("exercises[2].equipment: kettlebell is not in the equipment list"),
            errorsOf(
                liftPlan(
                    listOf(
                        BENCH, ROW,
                        """{"name":"Goblet Squat","equipment":["kettlebell"],"sets":[{"reps":10}]}""",
                    ),
                ),
                LIFT_CONTEXT,
            ),
        )
        // The brief's own example line, unquoted, from a list that does hold the other three keys.
        assertEquals(
            listOf("exercises[2].equipment: barbell is not in the equipment list"),
            errorsOf(
                liftPlan(
                    listOf(
                        """{"name":"Bench Press","equipment":["bench"],"sets":[{"reps":5,"weight":60}]}""",
                        ROW,
                        """{"name":"Front Squat","equipment":["barbell"],"sets":[{"reps":5}]}""",
                    ),
                ),
                LIFT_CONTEXT.copy(equipmentKeys = listOf("dumbbell", "bench", "pullup-bar")),
            ),
        )
    }

    @Test
    fun estimatedMinutesAboveTheSortieBudgetIsRejected() {
        // 80 is inside the schema's own 180 ceiling, so this is the semantic line on its own.
        assertEquals(
            listOf("estimated_minutes: 80 is more than 75 (1.25 × the 60-minute sortie length)"),
            errorsOf(liftPlan(listOf(BENCH, ROW, CURL), minutesField = "80"), LIFT_CONTEXT),
        )
        // The budget is exact, so a 50-minute sortie rejects 63 and the coach sees the fractional limit.
        assertEquals(
            listOf("estimated_minutes: 63 is more than 62.5 (1.25 × the 50-minute sortie length)"),
            errorsOf(liftPlan(listOf(BENCH, ROW, CURL), minutesField = "63"), LIFT_CONTEXT.copy(sortieLengthMinutes = 50)),
        )
    }

    @Test
    fun duplicateExerciseNameNamesBothSpellingsAndBothIndexes() {
        // §7.7 stores each exercise once by normalized name, so these two spellings are one exercise.
        assertEquals(
            listOf("exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]"),
            errorsOf(
                liftPlan(
                    listOf(
                        BENCH, ROW, CURL,
                        """{"name":"bench  press","equipment":["barbell","bench"],"sets":[{"reps":8,"weight":50}]}""",
                    ),
                ),
                LIFT_CONTEXT,
            ),
        )
    }

    @Test
    fun planTypeNotMatchingTheSortieIsRejected() {
        // A complete, valid run plan offered for the lift day: the one decisive mistake, not its five consequences.
        assertEquals(
            listOf("type: 'run' does not match the lift sortie being planned"),
            errorsOf(RUN_PLAN, LIFT_CONTEXT),
        )
        assertEquals(
            listOf("type: 'lift' does not match the run sortie being planned"),
            errorsOf(liftPlan(listOf(BENCH, ROW, CURL)), RUN_CONTEXT),
        )
    }

    // ─── how the passes combine ─────────────────────────────────────────────────

    @Test
    fun schemaAndSemanticViolationsInOneDocumentAreReportedInThatOrder() {
        // §7.3 allows two repairs, so both unrelated problems are named at once, schema line first.
        assertEquals(
            listOf(
                "estimated_minutes: expected an integer, got a string",
                "exercises[2].equipment: kettlebell is not in the equipment list",
            ),
            errorsOf(
                liftPlan(
                    listOf(
                        BENCH, ROW,
                        """{"name":"Goblet Squat","equipment":["kettlebell"],"sets":[{"reps":10}]}""",
                    ),
                    minutesField = "\"45\"",
                ),
                LIFT_CONTEXT,
            ),
        )
    }

    @Test
    fun aTypeThatIsNeitherLiftNorRunIsReportedOnceByTheChecker() {
        // The short-circuit is only for a well-formed lift/run of the wrong kind, so "swim" is the checker's to name.
        assertEquals(
            listOf("type: must be 'lift'"),
            errorsOf(liftPlan(listOf(BENCH, ROW, CURL), typeField = "\"swim\""), LIFT_CONTEXT),
        )
    }

    @Test
    fun aDocumentThatPassesEveryCheckMapsIntoTheCoachTypes() {
        val result = CoachReplyValidator.validate(liftPlan(listOf(BENCH, ROW, CURL)), LIFT_CONTEXT)
        assertTrue("expected a valid reply, got $result", result is CoachReplyResult.Valid)
        val plan = (result as CoachReplyResult.Valid).plan as CoachLiftPlan

        assertEquals("Push Day", plan.title)
        assertEquals(45, plan.estimatedMinutes)
        assertEquals("2 minutes rowing", plan.warmup)
        assertEquals("Keep it light.", plan.notes)
        assertEquals(3, plan.exercises.size)
        assertEquals(listOf("barbell", "bench"), plan.exercises[0].equipment)
        assertEquals(120, plan.exercises[0].restSeconds)
        assertEquals(null, plan.exercises[1].restSeconds)
        assertEquals(listOf(5, null, 60.0), listOf(plan.exercises[0].sets[0].reps, plan.exercises[0].sets[0].seconds, plan.exercises[0].sets[0].weight))
        assertEquals(listOf(5, null, 60.0), listOf(plan.exercises[0].sets[1].reps, plan.exercises[0].sets[1].seconds, plan.exercises[0].sets[1].weight))
        assertEquals(listOf(8, null, null), listOf(plan.exercises[1].sets[0].reps, plan.exercises[1].sets[0].seconds, plan.exercises[1].sets[0].weight))
        assertEquals("Bicep Curl", plan.exercises[2].name)
        assertEquals(listOf("dumbbell"), plan.exercises[2].equipment)
        assertEquals(listOf(12, null, null), listOf(plan.exercises[2].sets[0].reps, plan.exercises[2].sets[0].seconds, plan.exercises[2].sets[0].weight))
    }

    private companion object {
        val KEYS = listOf("barbell", "dumbbell", "bench", "pullup-bar")

        val OUTLINE_CONTEXT = GenerationContext(
            kind = GenerationKind.OUTLINE,
            pattern = "RLRLR",
            equipmentKeys = KEYS,
            sortieLengthMinutes = 60,
        )
        val LIFT_CONTEXT = GenerationContext(
            kind = GenerationKind.FLIGHT_PLAN,
            pattern = "RLRLR",
            sortieType = SortieType.LIFT,
            equipmentKeys = KEYS,
            sortieLengthMinutes = 60,
        )
        val RUN_CONTEXT = GenerationContext(
            kind = GenerationKind.FLIGHT_PLAN,
            pattern = "RLRLR",
            sortieType = SortieType.RUN,
            equipmentKeys = KEYS,
            sortieLengthMinutes = 60,
        )

        // The five entries that match RLRLR exactly, so a test can break one position at a time.
        const val S0 = """{"index":0,"type":"run","focus":"Easy 5k"}"""
        const val S1 = """{"index":1,"type":"lift","focus":"Push + core"}"""
        const val S2 = """{"index":2,"type":"run","focus":"Tempo intervals"}"""
        const val S3 = """{"index":3,"type":"lift","focus":"Legs + pull"}"""
        const val S4 = """{"index":4,"type":"run","focus":"Long run"}"""

        const val BENCH =
            """{"name":"Bench Press","equipment":["barbell","bench"],""" +
                """"sets":[{"reps":5,"weight":60},{"reps":5,"weight":60}],"rest_seconds":120}"""
        const val ROW = """{"name":"One-Arm Row","equipment":["dumbbell"],"sets":[{"reps":8}]}"""
        const val CURL = """{"name":"Bicep Curl","equipment":["dumbbell"],"sets":[{"reps":12}]}"""

        const val RUN_PLAN =
            """{"type":"run","title":"Easy Run","kind":"easy","target_distance":5,""" +
                """"target_pace":"9:30/mi","segments":[""" +
                """{"description":"4 minutes easy","minutes":4},""" +
                """{"description":"20 minutes conversational","minutes":20},""" +
                """{"description":"4 minutes warm-down","minutes":4}],""" +
                """"notes":"Conversational pace."}"""

        fun outline(vararg sorties: String): String =
            """{"sorties":[${sorties.joinToString(",")}],"notes":"Keep Thursday light."}"""

        /** A lift plan that satisfies its schema and every semantic check, with the named parts replaced. */
        fun liftPlan(
            exercises: List<String>,
            typeField: String = "\"lift\"",
            minutesField: String = "45",
        ): String =
            """{"type":$typeField,"title":"Push Day","estimated_minutes":$minutesField,""" +
                """"warmup":"2 minutes rowing","exercises":[${exercises.joinToString(",")}],""" +
                """"notes":"Keep it light."}"""
    }

    private fun errorsOf(raw: String, context: GenerationContext): List<String> {
        val result = CoachReplyValidator.validate(raw, context)
        assertTrue("expected an invalid reply, got $result", result is CoachReplyResult.Invalid)
        return (result as CoachReplyResult.Invalid).errors
    }
}
