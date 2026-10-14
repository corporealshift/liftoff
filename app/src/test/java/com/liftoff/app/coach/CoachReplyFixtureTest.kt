package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
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
 * The context is the plan's: pattern `RLRLR`, the four active equipment keys, a 60-minute sortie.
 * Lift fixtures use the sortie at position 1, run fixtures the one at position 0.
 *
 * This class also owns the fixture loader every later fixture test reuses.
 */
class CoachReplyFixtureTest {

    @Test
    fun outlineFixtureParsesIntoTheExpectedOutline() {
        val plan = validPlan(OUTLINE, OUTLINE_CONTEXT) as CoachOutline

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
        val plan = validPlan(LIFT_PLAN, LIFT_CONTEXT) as CoachLiftPlan

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
        val plan = validPlan(RUN_PLAN, RUN_CONTEXT) as CoachRunPlan

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
        val raw = fixture(LIFT_PLAN_FENCED)
        assertTrue("$LIFT_PLAN_FENCED is not fenced", raw.trimStart().startsWith("```json"))

        val plan = validPlan(LIFT_PLAN_FENCED, LIFT_CONTEXT) as CoachLiftPlan
        assertEquals(validPlan(LIFT_PLAN, LIFT_CONTEXT), plan)
        assertEquals("Push + core", plan.title)
        assertEquals(4, plan.exercises.size)
    }

    // ─── the invalid replies, each with its own message ─────────────────────────

    @Test
    fun badJsonFixtureGivesOneNotValidJsonLine() {
        // Braces balance and a trailing comma follows the last exercise, so the object is found and
        // kotlinx is the one that rejects it. The reason is kotlinx's sentence, so only the prefix is pinned.
        val errors = errorsOf(BAD_JSON, LIFT_CONTEXT)
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
            errorsOf(PROSE_AROUND_JSON, LIFT_CONTEXT),
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
            errorsOf(SCHEMA_VIOLATION, LIFT_CONTEXT),
        )
    }

    @Test
    fun unknownEquipmentFixtureNamesTheIdAndItsPath() {
        assertEquals(
            listOf("exercises[2].equipment: kettlebell is not in the equipment list"),
            errorsOf(UNKNOWN_EQUIPMENT, LIFT_CONTEXT),
        )
    }

    @Test
    fun inactiveEquipmentFixtureIsRejectedAsNotInTheEquipmentList() {
        // The context carries active keys only, so the deactivated `rowing-machine` reads as any other
        // unknown id: the same line, not a second message about deactivation.
        assertEquals(
            listOf("exercises[1].equipment: rowing-machine is not in the equipment list"),
            errorsOf(INACTIVE_EQUIPMENT, LIFT_CONTEXT),
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
            errorsOf(OUTLINE_PATTERN_MISMATCH, OUTLINE_CONTEXT),
        )
    }

    @Test
    fun repsAndSecondsFixtureGivesItsError() {
        assertEquals(
            listOf("exercises[0].sets[1]: reps and seconds cannot both be set"),
            errorsOf(REPS_AND_SECONDS, LIFT_CONTEXT),
        )
    }

    @Test
    fun overLengthSortieFixtureGivesItsError() {
        // 80 minutes against a 60-minute sortie, and inside the schema's own 180 ceiling, so the budget
        // line is the only one.
        assertEquals(
            listOf("estimated_minutes: 80 is more than 75 (1.25 × the 60-minute sortie length)"),
            errorsOf(OVER_LENGTH_SORTIE, LIFT_CONTEXT),
        )
    }

    @Test
    fun duplicateExerciseNamesFixtureGivesItsError() {
        // Normalized, `bench  press` is the exercise already at exercises[0], so the later index names
        // both spellings.
        assertEquals(
            listOf("exercises[3].name: 'bench  press' duplicates 'Bench Press' at exercises[0]"),
            errorsOf(DUPLICATE_EXERCISE_NAMES, LIFT_CONTEXT),
        )
    }

    @Test
    fun planTypeMismatchFixtureGivesItsError() {
        // A complete, valid run plan offered for the lift sortie: the one decisive mistake, with none of
        // the five schema errors that document would trigger against the lift-plan schema.
        assertEquals(
            listOf("type: 'run' does not match the lift sortie being planned"),
            errorsOf(PLAN_TYPE_MISMATCH, LIFT_CONTEXT),
        )
    }

    @Test
    fun everyFixtureFileIsCoveredByATest() {
        val dir = fixtureDirectory()
        val onDisk = (dir.listFiles { file -> file.isFile && file.name.endsWith(".txt") } ?: emptyArray<File>())
            .mapTo(mutableSetOf()) { it.name }

        val uncovered = onDisk - COVERED
        val missing = COVERED - onDisk
        assertTrue("$dir holds ${uncovered.sorted()} that no test reads: $uncovered", uncovered.isEmpty())
        assertTrue("$dir has no file named ${missing.sorted()}", missing.isEmpty())
    }

    // ─── the loader every fixture test uses ─────────────────────────────────────

    /** The fixture text, read the way `CoachSchemaFilesTest` reads `schemas/outline.json`. */
    private fun fixture(name: String): String {
        val resource = "$FIXTURE_PACKAGE/$name"
        javaClass.classLoader?.getResourceAsStream(resource)?.use {
            return it.readBytes().toString(Charsets.UTF_8)
        }
        // app/src/test/resources is not yet proven on this project's test classpath, so fall back to
        // the working-directory route ExerciseNamesTest.coachPackageHasNoAndroidImports uses.
        val file = fixtureSourceDirs().map { File(it, name) }.firstOrNull { it.isFile }
            ?: throw AssertionError("$name: no fixture on the classpath or under ${fixtureSourceDirs().joinToString(" or ")}")
        return file.readText()
    }

    private fun fixtureDirectory(): File =
        fixtureSourceDirs().firstOrNull { it.isDirectory }
            ?: throw AssertionError("no fixture directory at ${fixtureSourceDirs().joinToString(" or ")}")

    /** The two working directories a unit test runs from — `app/`, and the module dir one level up. */
    private fun fixtureSourceDirs(): List<File> =
        listOf(File("../$FIXTURE_SOURCE_DIR"), File(FIXTURE_SOURCE_DIR))

    private fun validPlan(fixture: String, context: GenerationContext): CoachPlan {
        val result = CoachReplyValidator.validate(fixture(fixture), context)
        assertTrue(
            "$fixture should have parsed, got ${(result as? CoachReplyResult.Invalid)?.errors}",
            result is CoachReplyResult.Valid,
        )
        return (result as CoachReplyResult.Valid).plan
    }

    /** The lines an invalid fixture must produce, so a fixture that parses, or produces an extra line, fails. */
    private fun errorsOf(fixture: String, context: GenerationContext): List<String> {
        val result = CoachReplyValidator.validate(fixture(fixture), context)
        assertTrue("expected $fixture to be rejected, got $result", result is CoachReplyResult.Invalid)
        return (result as CoachReplyResult.Invalid).errors
    }

    private companion object {
        const val FIXTURE_PACKAGE = "fixtures/coach"
        const val FIXTURE_SOURCE_DIR = "src/test/resources/fixtures/coach"

        const val OUTLINE = "outline-valid.txt"
        const val LIFT_PLAN = "lift-plan-valid.txt"
        const val RUN_PLAN = "run-plan-valid.txt"
        const val LIFT_PLAN_FENCED = "lift-plan-fenced.txt"

        const val BAD_JSON = "bad-json.txt"
        const val PROSE_AROUND_JSON = "prose-around-json.txt"
        const val SCHEMA_VIOLATION = "schema-violation.txt"
        const val UNKNOWN_EQUIPMENT = "unknown-equipment.txt"
        const val INACTIVE_EQUIPMENT = "inactive-equipment.txt"
        const val OUTLINE_PATTERN_MISMATCH = "outline-pattern-mismatch.txt"
        const val REPS_AND_SECONDS = "reps-and-seconds.txt"
        const val OVER_LENGTH_SORTIE = "over-length-sortie.txt"
        const val DUPLICATE_EXERCISE_NAMES = "duplicate-exercise-names.txt"
        const val PLAN_TYPE_MISMATCH = "plan-type-mismatch.txt"

        /** Every fixture a test in this class reads; `everyFixtureFileIsCoveredByATest` checks it against the directory. */
        val COVERED = setOf(
            OUTLINE, LIFT_PLAN, RUN_PLAN, LIFT_PLAN_FENCED,
            BAD_JSON, PROSE_AROUND_JSON, SCHEMA_VIOLATION, UNKNOWN_EQUIPMENT, INACTIVE_EQUIPMENT,
            OUTLINE_PATTERN_MISMATCH, REPS_AND_SECONDS, OVER_LENGTH_SORTIE, DUPLICATE_EXERCISE_NAMES,
            PLAN_TYPE_MISMATCH,
        )

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
    }
}
