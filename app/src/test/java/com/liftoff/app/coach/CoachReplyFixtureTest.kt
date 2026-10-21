package com.liftoff.app.coach

import com.liftoff.app.data.SortieType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §13's "Validator: run against `fixtures/`": each fixture is the raw reply text as the coach would
 * have sent it, run through `validate` against one fixed context, and the plan it parses into is
 * checked field by field — "no errors" would still pass a document mapped into the wrong values.
 * An invalid fixture asserts its exact list of lines, so a fixture that produces a second,
 * unexpected error fails as loudly as one that words its message wrongly.
 *
 * The fixtures, the loader and the context they are checked against live in [CoachFixtures]: pattern
 * `RLRLR`, the four active equipment keys, a 60-minute sortie, the lift sortie at position 1 and the
 * run sortie at position 0. The data tests read the same replies through the same loader.
 */
class CoachReplyFixtureTest {

    @Test
    fun outlineFixtureParsesIntoTheExpectedOutline() {
        val plan = CoachFixtures.validPlan(CoachFixtures.OUTLINE, CoachFixtures.OUTLINE_CONTEXT) as CoachOutline

        assertEquals(5, plan.sorties.size)
        assertEquals(listOf(0, 1, 2, 3, 4), plan.sorties.map { it.index })
        assertEquals(
            listOf(SortieType.RUN, SortieType.LIFT, SortieType.RUN, SortieType.LIFT, SortieType.RUN),
            plan.sorties.map { it.sortieType },
        )
        assertEquals(
            listOf("Easy 5k", "Push + core", "Tempo intervals", "Legs + pull", "Long run"),
            plan.sorties.map { it.focus },
        )
        // Only the push day carries a rationale; the schema leaves it optional everywhere else.
        assertEquals(
            listOf<String?>(
                null,
                "Upper body only: it sits between the easy run and Thursday's tempo day.",
                null,
                null,
                null,
            ),
            plan.sorties.map { it.rationale },
        )
        assertEquals("Thursday's lift follows the tempo day, so hinge volume stays low.", plan.notes)
    }

    @Test
    fun liftPlanFixtureParsesIntoTheExpectedLiftPlan() {
        val plan = CoachFixtures.validPlan(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT) as CoachLiftPlan

        assertEquals("Push + core", plan.title)
        assertEquals(65, plan.estimatedMinutes)
        assertEquals("3 minutes rowing, then band pull-aparts", plan.warmup)
        assertEquals("Legs stay light: the tempo intervals land the day after this one.", plan.notes)
        assertEquals(4, plan.exercises.size)
        assertEquals(listOf("Bench Press", "Pendlay Row", "Front Squat", "Plank"), plan.exercises.map { it.name })
        assertEquals(listOf(120, 90, 150, 60), plan.exercises.map { it.restSeconds })

        val bench = plan.exercises[0]
        assertEquals(listOf("barbell", "bench"), bench.equipment)
        assertEquals("Same bar as last week; add weight only if all three sets were hit.", bench.notes)
        assertEquals(
            listOf(CoachSet(5, null, 60.0), CoachSet(5, null, 60.0), CoachSet(5, null, 62.5)),
            bench.sets,
        )

        // The plan's one seconds set, and the one exercise that omits `notes` and `rest_seconds` together.
        assertEquals(listOf(CoachSet(10, null, 40.0), CoachSet(null, 40, 20.0)), plan.exercises[2].sets)
        assertNull(plan.exercises[1].notes)
        assertEquals(listOf("barbell"), plan.exercises[1].equipment)
        assertEquals(listOf(CoachSet(null, 45, null), CoachSet(null, 45, null)), plan.exercises[3].sets)
        assertEquals(listOf("bench"), plan.exercises[3].equipment)
    }

    @Test
    fun runPlanFixtureParsesIntoTheExpectedRunPlan() {
        val plan = CoachFixtures.validPlan(CoachFixtures.RUN_PLAN, CoachFixtures.RUN_CONTEXT) as CoachRunPlan

        assertEquals("Tempo intervals", plan.title)
        assertEquals("tempo", plan.kind)
        assertEquals(listOf(6.5, "8:15/mi"), listOf<Any?>(plan.targetDistance, plan.targetPace))
        assertEquals("Float is walking, not jogging — the reps are what count.", plan.notes)
        assertEquals(4, plan.segments.size)
        assertEquals(
            listOf(
                CoachSegment("6 minutes easy to warm up", 1.0, 6.0),
                CoachSegment("4 x 90 seconds at tempo, 90 seconds float", 3.5, 16.0),
                CoachSegment("3 minutes at easy effort", 0.5, 3.0),
                CoachSegment("Warm-down jog", 1.5, 12.0),
            ),
            plan.segments,
        )
    }

    @Test
    fun fencedFixtureParsesIntoTheExpectedPlan() {
        val raw = CoachFixtures.fixture(CoachFixtures.LIFT_PLAN_FENCED)
        assertTrue("${CoachFixtures.LIFT_PLAN_FENCED} is not fenced", raw.trimStart().startsWith("```json"))

        val plan = CoachFixtures.validPlan(CoachFixtures.LIFT_PLAN_FENCED, CoachFixtures.LIFT_CONTEXT) as CoachLiftPlan
        assertEquals(CoachFixtures.validPlan(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT), plan)
        assertEquals("Push + core", plan.title)
        assertEquals(4, plan.exercises.size)
    }

    @Test
    fun validCarriesTheAcceptedJsonObjectRatherThanTheReplyItWasWrappedIn() {
        val plain = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN, CoachFixtures.LIFT_CONTEXT)
        val fenced = CoachFixtures.validReply(CoachFixtures.LIFT_PLAN_FENCED, CoachFixtures.LIFT_CONTEXT)

        // The fence is how the reply was dressed, not part of the object, so both yield one text.
        assertEquals(plain.json, fenced.json)
        assertEquals(plain.plan, fenced.plan)
        assertNotEquals(
            "the raw reply is fenced text with an info string; what is carried is the object",
            CoachFixtures.fixture(CoachFixtures.LIFT_PLAN_FENCED),
            fenced.json,
        )

        // The same object dressed by hand — fence, info string, blank lines — is still one text.
        val dressed = CoachReplyValidator.validate(
            "\n  ```json\n" + CoachFixtures.fixture(CoachFixtures.LIFT_PLAN) + "\n  ```  \n",
            CoachFixtures.LIFT_CONTEXT,
        )
        assertTrue("the dressed reply should have parsed, got $dressed", dressed is CoachReplyResult.Valid)
        assertEquals(plain.json, (dressed as CoachReplyResult.Valid).json)

        val json = plain.json
        assertFalse("no fence markers survive in the accepted text", json.contains("```"))
        assertTrue("the text is the object itself, not text around it", json.startsWith("{") && json.endsWith("}"))

        // The text is the object `extract` accepted from the unfenced fixture, and nothing else.
        val extracted = CoachReplyValidator.extract(CoachFixtures.fixture(CoachFixtures.LIFT_PLAN))
        assertTrue("expected one JSON object, got $extracted", extracted is Extracted.Ok)
        assertEquals((extracted as Extracted.Ok).document.toString(), json)
        assertEquals((extracted as Extracted.Ok).document, Json.parseToJsonElement(json))

        // Prose around the object never reaches a Valid at all: extraction rejects the reply first.
        assertTrue(
            CoachReplyValidator.validate(CoachFixtures.fixture(CoachFixtures.PROSE_AROUND_JSON), CoachFixtures.LIFT_CONTEXT)
            is CoachReplyResult.Invalid,
        )
    }

    // ─── the invalid replies, each with its own message ─────────────────────────

    @Test
    fun badJsonFixtureGivesOneNotValidJsonLine() {
        // Braces balance and a trailing comma follows the last exercise, so the object is found and
        // kotlinx is the one that rejects it. The reason is kotlinx's sentence, so only the prefix is pinned.
        val errors = CoachFixtures.errorsOf(CoachFixtures.BAD_JSON, CoachFixtures.LIFT_CONTEXT)
        assertEquals(1, errors.size)
        assertTrue(errors[0], errors[0].startsWith("\$: not valid JSON: "))
        assertTrue(errors[0], errors[0].removePrefix("\$: not valid JSON: ").isNotBlank())
    }

    @Test
    fun proseAroundJsonFixtureGivesItsOwnError() {
        // "Here's the plan:" before the object and a sentence after it. Extraction names the text that
        // leads and stops there, so the reply reads as prose, not as kotlinx's broken-JSON message.
        assertEquals(
            listOf("\$: unexpected text before the JSON object"),
            CoachFixtures.errorsOf(CoachFixtures.PROSE_AROUND_JSON, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun schemaViolationFixtureGivesTheCheckersErrors() {
        // A quoted estimated_minutes fails the checker's `integer` type, and an exercise's extra `day`
        // key is reported on its object. Both are the checker's own paths and wording, in its own order.
        assertEquals(
            listOf(
                "estimated_minutes: expected an integer, got a string",
                "exercises[1]: unexpected property 'day'",
            ),
            CoachFixtures.errorsOf(CoachFixtures.SCHEMA_VIOLATION, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun unknownEquipmentFixtureNamesTheIdAndItsPath() {
        assertEquals(
            listOf("exercises[2].equipment: kettlebell is not in the equipment list"),
            CoachFixtures.errorsOf(CoachFixtures.UNKNOWN_EQUIPMENT, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun inactiveEquipmentFixtureIsRejectedAsNotInTheEquipmentList() {
        // The context carries active keys only, so the deactivated `rowing-machine` reads as any other
        // unknown id: the same line, not a second message about deactivation.
        assertEquals(
            listOf("exercises[1].equipment: rowing-machine is not in the equipment list"),
            CoachFixtures.errorsOf(CoachFixtures.INACTIVE_EQUIPMENT, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun outlinePatternMismatchFixtureGivesItsErrors() {
        // Four sorties for a five-position pattern, and the third one is `lift` where RLRLR says `run`.
        // The entries that exist are checked even though the count already failed.
        assertEquals(
            listOf(
                "sorties: expected 5 sorties for the pattern RLRLR, got 4",
                "sorties[2].type: must be 'run' at position 2 of the pattern RLRLR",
            ),
            CoachFixtures.errorsOf(CoachFixtures.OUTLINE_PATTERN_MISMATCH, CoachFixtures.OUTLINE_CONTEXT),
        )
    }

    @Test
    fun repsAndSecondsFixtureGivesItsError() {
        assertEquals(
            listOf("exercises[0].sets[1]: reps and seconds cannot both be set"),
            CoachFixtures.errorsOf(CoachFixtures.REPS_AND_SECONDS, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun overLengthSortieFixtureGivesItsError() {
        // 80 minutes against a 60-minute sortie, and inside the schema's own 180 ceiling, so the budget
        // line is the only one.
        assertEquals(
            listOf("estimated_minutes: 80 is more than 75 (1.25 × the 60-minute sortie length)"),
            CoachFixtures.errorsOf(CoachFixtures.OVER_LENGTH_SORTIE, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun duplicateExerciseNamesFixtureGivesItsError() {
        // Normalized, `bench  press` is the exercise already at exercises[0], so the later index names
        // both spellings.
        assertEquals(
            listOf("exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]"),
            CoachFixtures.errorsOf(CoachFixtures.DUPLICATE_EXERCISE_NAMES, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun planTypeMismatchFixtureGivesItsError() {
        // A complete, valid run plan offered for the lift sortie: the one decisive mistake, with none of
        // the five schema errors that document would trigger against the lift-plan schema.
        assertEquals(
            listOf("type: 'run' does not match the lift sortie being planned"),
            CoachFixtures.errorsOf(CoachFixtures.PLAN_TYPE_MISMATCH, CoachFixtures.LIFT_CONTEXT),
        )
    }

    @Test
    fun everyFixtureFileIsCoveredByATest() {
        val dir = CoachFixtures.fixtureDirectory()
        val onDisk = (dir.listFiles { file -> file.isFile && file.name.endsWith(".txt") } ?: emptyArray<File>())
            .mapTo(mutableSetOf()) { it.name }

        val uncovered = onDisk - COVERED
        val missing = COVERED - onDisk
        assertTrue("$dir holds ${uncovered.sorted()} that no test reads: $uncovered", uncovered.isEmpty())
        assertTrue("$dir has no file named ${missing.sorted()}", missing.isEmpty())
    }

    private companion object {
        /** Every fixture a test in this class reads; `everyFixtureFileIsCoveredByATest` checks it against the directory. */
        val COVERED = setOf(
            CoachFixtures.OUTLINE, CoachFixtures.LIFT_PLAN, CoachFixtures.RUN_PLAN, CoachFixtures.LIFT_PLAN_FENCED,
            CoachFixtures.BAD_JSON, CoachFixtures.PROSE_AROUND_JSON, CoachFixtures.SCHEMA_VIOLATION,
            CoachFixtures.UNKNOWN_EQUIPMENT, CoachFixtures.INACTIVE_EQUIPMENT,
            CoachFixtures.OUTLINE_PATTERN_MISMATCH, CoachFixtures.REPS_AND_SECONDS, CoachFixtures.OVER_LENGTH_SORTIE,
            CoachFixtures.DUPLICATE_EXERCISE_NAMES, CoachFixtures.PLAN_TYPE_MISMATCH,
        )
    }
}
