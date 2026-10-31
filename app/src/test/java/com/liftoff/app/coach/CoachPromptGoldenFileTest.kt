package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SetStatus
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.WeightUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * §7.4's three prompts, compared byte for byte with the golden files under
 * `app/src/test/resources/prompts/`.
 *
 * The inputs below are the fixed ones the golden files were generated from, so they are the prompt
 * the owner would see for a mid-Mission week: a five-position `RLRLR` pattern, three of its sorties
 * already carrying an outline focus, and three landed or scrubbed sorties of history. A golden file
 * is what fixes the eight sections, their wording and the §7.4 order for one generation kind; the
 * individual formatting rules are in `CoachPromptBuilderTest`, so a failure here names the prompt,
 * not one line of it.
 *
 * The comparison is on UTF-8 bytes, so a stray CR fails as loudly as a wrong word.
 */
class CoachPromptGoldenFileTest {

    /** The OUTLINE prompt: Task "Write the outline for this week", no sortie being planned, outline schema. */
    @Test
    fun outlinePromptMatchesGoldenFile() {
        val golden = String(golden(OUTLINE_GOLDEN), Charsets.UTF_8)
        assertMatchesGolden(OUTLINE_GOLDEN, CoachPromptBuilder.prompt(outlineInput()))
        // The three ways this prompt differs from a Flight Plan's, named rather than left to the diff.
        assertTrue(golden.contains("## Task\n\nWrite the outline for this week\n"))
        assertFalse("an outline prompt plans no one sortie", golden.contains("planning:"))
        assertFalse("an outline prompt shows no focus", golden.contains("focus:"))
        assertTrue("the outline schema is the last section", golden.contains(CoachSchemas.text(CoachSchemas.OUTLINE).trimEnd('\n')))
    }

    /** The FLIGHT_PLAN prompt for the lift sortie, with an outline stored: lift-plan schema at the end. */
    @Test
    fun liftFlightPlanPromptWithOutlineMatchesGoldenFile() {
        assertMatchesGolden(LIFT_GOLDEN, CoachPromptBuilder.prompt(liftFlightPlanInput()))
    }

    /** The FLIGHT_PLAN prompt for the run sortie: run-plan schema at the end. */
    @Test
    fun runFlightPlanPromptMatchesGoldenFile() {
        assertMatchesGolden(RUN_GOLDEN, CoachPromptBuilder.prompt(runFlightPlanInput()))
    }

    /** §7.4: "the same inputs MUST produce the same text" — no clock, no random, no locale formatting. */
    @Test
    fun buildingTheSamePromptTwiceGivesIdenticalText() {
        for (input in listOf(outlineInput(), liftFlightPlanInput(), runFlightPlanInput())) {
            val first = CoachPromptBuilder.prompt(input)
            val second = CoachPromptBuilder.prompt(input)
            assertArrayEquals(
                "building ${input.kind} twice gave different bytes",
                first.toByteArray(Charsets.UTF_8),
                second.toByteArray(Charsets.UTF_8),
            )
        }
    }

    /**
     * §7.4 item 8: the Output schema section is the schema file for that kind, byte for byte — the
     * same bytes `CoachReplyValidator` will check the reply against.
     */
    @Test
    fun outputSchemaSectionIsTheResourceFileVerbatim() {
        val cases = listOf(
            CoachSchemas.OUTLINE to CoachPromptBuilder.prompt(outlineInput()),
            CoachSchemas.LIFT_PLAN to CoachPromptBuilder.prompt(liftFlightPlanInput()),
            CoachSchemas.RUN_PLAN to CoachPromptBuilder.prompt(runFlightPlanInput()),
        )
        for ((resource, prompt) in cases) {
            assertTrue("$resource: no ## Output schema section in:\n$prompt", prompt.contains(SECTION_HEAD))
            // The heading appears once, so the schema really is the tail of the prompt.
            assertEquals(
                "$resource: ## Output schema appears more than once, so the schema is not the last section",
                prompt.indexOf(SECTION_HEAD),
                prompt.lastIndexOf(SECTION_HEAD),
            )
            val section = prompt.substringAfter(SECTION_HEAD).toByteArray(Charsets.UTF_8)
            // The builder drops the file's own final newline so a prompt never ends in a blank line;
            // every other byte of the schema file is in the prompt.
            val fileBytes = resourceBytes(resource).dropLastWhile { it == LF || it == CR }.toByteArray()
            assertArrayEquals("$resource is not the Output schema section byte for byte", fileBytes, section)
        }
    }

    // ─── the fixed inputs the golden files were generated from ──────────────────

    private fun outlineInput() = CoachPromptInput(
        kind = GenerationKind.OUTLINE,
        profile = profile,
        equipment = equipment,
        week = week,
        history = history,
    )

    private fun liftFlightPlanInput() = CoachPromptInput(
        kind = GenerationKind.FLIGHT_PLAN,
        profile = profile,
        equipment = equipment,
        week = week,
        history = history,
        sortieIndex = 1,
    )

    private fun runFlightPlanInput() = CoachPromptInput(
        kind = GenerationKind.FLIGHT_PLAN,
        profile = profile,
        equipment = equipment,
        week = week,
        history = history,
        sortieIndex = 2,
    )

    private val profile = PromptProfile(
        objectives = "Get stronger at the basics",
        constraints = "45 minutes at home, no pull-up bar",
        sortieLengthMinutes = 45,
        weightUnit = WeightUnit.LB,
        distanceUnit = DistanceUnit.MI,
    )

    private val equipment = listOf(
        PromptEquipment("bb", "barbell", "20 lb bar, plates to 45 lb"),
        PromptEquipment("db", "dumbbells", "pairs 5–50 lb in 5 lb steps"),
        PromptEquipment("kb", "kettlebell"),
    )

    /** An outline is stored for the first three positions; the last two carry no focus yet. */
    private val week = PromptWeek(
        pattern = "RLRLR",
        sorties = listOf(
            PromptSortie(0, SortieType.RUN, SortieState.LANDED, "Easy 5k"),
            PromptSortie(1, SortieType.LIFT, SortieState.PENDING, "Push + core"),
            PromptSortie(2, SortieType.RUN, SortieState.PENDING, "Tempo intervals"),
            PromptSortie(3, SortieType.LIFT, SortieState.PENDING),
            PromptSortie(4, SortieType.RUN, SortieState.PENDING),
        ),
    )

    /** Newest first, as the loader hands them over: landed, scrubbed with no reason of its own, landed. */
    private val history = listOf(
        PromptHistorySortie(
            date = LocalDate.parse("2026-10-05"),
            type = SortieType.LIFT,
            title = "Legs + pull",
            state = SortieState.LANDED,
            exercises = listOf(
                PromptHistoryExercise(
                    "Back squat",
                    listOf(done(5, 185.0), done(5, 185.0), done(5, 185.0)),
                ),
                PromptHistoryExercise(
                    "Barbell row",
                    listOf(done(8, 135.0), done(8, 135.0), done(8, 135.0), done(6, 135.0, plannedReps = 8)),
                    note = "lower back tired",
                ),
                PromptHistoryExercise("Plank", listOf(done(seconds = 45), done(seconds = 45), done(seconds = 45))),
                PromptHistoryExercise("Push-up", listOf(done(12), done(12), done(12))),
                // A skipped set has no actuals of its own, so it reads on its planned values.
                PromptHistoryExercise(
                    "Kettlebell swing",
                    listOf(unfinished(SetStatus.SKIPPED, 15, 35.0), unfinished(SetStatus.SKIPPED, 15, 35.0)),
                ),
            ),
            notes = "left shoulder twinge on last set",
        ),
        PromptHistorySortie(
            date = LocalDate.parse("2026-10-03"),
            type = SortieType.RUN,
            title = "Easy 5k",
            state = SortieState.SCRUBBED,
            scrubReason = "week ended",
            notes = "knee sore",
            runDistance = 3.1,
            runMinutes = 28.0,
        ),
        PromptHistorySortie(
            date = LocalDate.parse("2026-09-28"),
            type = SortieType.LIFT,
            title = "Full body",
            state = SortieState.LANDED,
            exercises = listOf(
                PromptHistoryExercise(
                    "Bench press",
                    listOf(done(8, 135.0), done(8, 135.0), done(8, 135.0), unfinished(SetStatus.OPEN, 8, 135.0)),
                )
            ),
        ),
    )

    private fun done(
        reps: Int? = null,
        weight: Double? = null,
        seconds: Int? = null,
        plannedReps: Int? = null,
        plannedWeight: Double? = null,
    ) = PromptHistorySet(reps, seconds, weight, SetStatus.DONE, plannedReps, null, plannedWeight)

    private fun unfinished(status: SetStatus, reps: Int? = null, weight: Double? = null, seconds: Int? = null) =
        PromptHistorySet(reps, seconds, weight, status, reps, seconds, weight)

    // ─── the loader ─────────────────────────────────────────────────────────────

    private fun assertMatchesGolden(name: String, prompt: String) {
        val expected = golden(name)
        val actual = prompt.toByteArray(Charsets.UTF_8)
        if (!expected.contentEquals(actual)) {
            // The byte comparison alone would only say "not equal", so show the text it disagreed with.
            throw AssertionError(
                "$name does not match the built prompt (${expected.size} expected bytes, ${actual.size} actual):\n" +
                    String(actual, Charsets.UTF_8)
            )
        }
    }

    /** The golden text, read the way `CoachReplyFixtureTest` reads its fixtures. */
    private fun golden(name: String): ByteArray {
        javaClass.classLoader?.getResourceAsStream("$GOLDEN_PACKAGE/$name")?.use {
            return it.readBytes()
        }
        // app/src/test/resources is not proven on this project's test classpath, so fall back to the
        // working-directory route, reading bytes rather than text so a CR cannot be hidden by decoding.
        val file = goldenSourceDirs().map { File(it, name) }.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "$name: no golden file on the classpath or under ${goldenSourceDirs().joinToString(" or ")}"
            )
        return file.readBytes()
    }

    /** The two working directories a unit test runs from — `app/`, and the module dir one level up. */
    private fun goldenSourceDirs(): List<File> =
        listOf(File("../$GOLDEN_SOURCE_DIR"), File(GOLDEN_SOURCE_DIR))

    /** The schema file's own bytes, read the way `CoachSchemaFilesTest` reads them off the classpath. */
    private fun resourceBytes(resource: String): ByteArray =
        javaClass.classLoader?.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw AssertionError("$resource missing from the test classpath")

    private companion object {
        const val GOLDEN_PACKAGE = "prompts"
        const val GOLDEN_SOURCE_DIR = "src/test/resources/prompts"

        const val OUTLINE_GOLDEN = "outline.txt"
        const val LIFT_GOLDEN = "lift-flight-plan-with-outline.txt"
        const val RUN_GOLDEN = "run-flight-plan.txt"

        const val SECTION_HEAD = "## Output schema\n\n"

        const val LF: Byte = '\n'.code.toByte()
        const val CR: Byte = '\r'.code.toByte()
    }
}
