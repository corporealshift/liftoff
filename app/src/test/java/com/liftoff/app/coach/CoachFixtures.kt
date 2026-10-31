package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertTrue
import java.io.File

/**
 * The reply fixtures under `app/src/test/resources/fixtures/coach/`, read once for every test that
 * hands a reply to `CoachReplyValidator` — the coach tests, and the data tests that store what a
 * valid reply parses into.
 *
 * Each fixture is the raw reply text as the coach would have sent it, run against one fixed context:
 * pattern `RLRLR`, the four active equipment keys, a 60-minute sortie. Lift fixtures use the sortie at
 * position 1, run fixtures the one at position 0.
 */
object CoachFixtures {

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

    /** The raw reply text, fence and all. */
    fun fixture(name: String): String {
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

    fun fixtureDirectory(): File =
        fixtureSourceDirs().firstOrNull { it.isDirectory }
            ?: throw AssertionError("no fixture directory at ${fixtureSourceDirs().joinToString(" or ")}")

    /** The two working directories a unit test runs from — `app/`, and the module dir one level up. */
    fun fixtureSourceDirs(): List<File> =
        listOf(File("../$FIXTURE_SOURCE_DIR"), File(FIXTURE_SOURCE_DIR))

    /** The plan and the validated JSON the fixture's reply produced. */
    fun validReply(fixture: String, context: GenerationContext): CoachReplyResult.Valid {
        val result = CoachReplyValidator.validate(fixture(fixture), context)
        assertTrue(
            "$fixture should have parsed, got ${(result as? CoachReplyResult.Invalid)?.errors}",
            result is CoachReplyResult.Valid,
        )
        return result as CoachReplyResult.Valid
    }

    fun validPlan(fixture: String, context: GenerationContext): CoachPlan = validReply(fixture, context).plan

    /** The lines an invalid fixture must produce, so a fixture that parses, or produces an extra line, fails. */
    fun errorsOf(fixture: String, context: GenerationContext): List<String> {
        val result = CoachReplyValidator.validate(fixture(fixture), context)
        assertTrue("expected $fixture to be rejected, got $result", result is CoachReplyResult.Invalid)
        return (result as CoachReplyResult.Invalid).errors
    }

    private const val FIXTURE_PACKAGE = "fixtures/coach"
    private const val FIXTURE_SOURCE_DIR = "src/test/resources/fixtures/coach"
}
