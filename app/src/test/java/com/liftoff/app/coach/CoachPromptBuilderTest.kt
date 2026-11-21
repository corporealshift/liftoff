package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SetStatus
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The §7.4 builder's formatting rules, asserted line by line against plan.md's set-line grammar.
 *
 * Plain JVM test: the inputs are written by hand here, so nothing about the prompt depends on a
 * database or a clock. The golden files (a later step) fix whole prompts; these tests fix the
 * individual rules, so a failure names the rule rather than a 60-line diff.
 */
class CoachPromptBuilderTest {

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

    private val week = PromptWeek(
        pattern = "RLRLR",
        sorties = listOf(
            PromptSortie(0, SortieType.RUN, SortieState.LANDED, "Easy 5k"),
            PromptSortie(1, SortieType.LIFT, SortieState.PENDING, "Push + core"),
            PromptSortie(2, SortieType.RUN, SortieState.PENDING, "Tempo intervals"),
        ),
    )

    // ─── §7.4 item 6: the history block ─────────────────────────────────────────

    /** A set done with different reps or weight from its plan names the planned value; one done as planned does not. */
    @Test
    fun historyLineMarksASetThatDifferedFromItsPlan() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    landed(
                        date = LocalDate.parse("2026-10-05"),
                        title = "Legs + pull",
                        exercises = listOf(
                            exercise(
                                "Bench press",
                                done(8, 135.0),
                                done(8, 135.0),
                                done(8, 135.0),
                                // 6 reps done against 8 planned.
                                done(6, 135.0, plannedReps = 8),
                            ),
                            exercise(
                                "Deadlift",
                                // 145 lb done against 135 planned, reps as planned.
                                done(6, 145.0, plannedWeight = 135.0),
                            ),
                            exercise(
                                "Back squat",
                                // both the reps and the weight differ.
                                done(6, 145.0, plannedReps = 8, plannedWeight = 135.0),
                            ),
                        ),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "2026-10-05 LIFT \"Legs + pull\" — landed",
                "  Bench press: 3×8 @135 lb ✓, 1×6 @135 lb (planned 8)",
                "  Deadlift: 1×6 @145 lb (planned @135 lb)",
                "  Back squat: 1×6 @145 lb (planned 8 @135 lb)",
            ),
            historyLines(prompt),
        )
    }

    /** SetStatus.SKIPPED reads as `skipped`. */
    @Test
    fun skippedSetsAreMarkedSkipped() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    landed(
                        date = LocalDate.parse("2026-10-05"),
                        title = "Legs + pull",
                        exercises = listOf(
                            // A skipped set has no actuals, so it reads on its planned values.
                            exercise(
                                "Kettlebell swing",
                                skipped(15, weight = 35.0),
                                skipped(15, weight = 35.0),
                            ),
                            exercise("Plank", skipped(seconds = 45)),
                        ),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "2026-10-05 LIFT \"Legs + pull\" — landed",
                "  Kettlebell swing: 2×15 @35 lb skipped",
                "  Plank: 1×45 s skipped",
            ),
            historyLines(prompt),
        )
    }

    /**
     * A set left open on a sortie scrubbed mid-flight reads as not done, in a different word from
     * skipped (plan decision 5): the two states are different rows, and `OPEN` survives only on a
     * scrub — `MissionManager.land` rewrites it to SKIPPED.
     */
    @Test
    fun openSetsOnAScrubbedSortieReadNotDone() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    scrubbed(
                        date = LocalDate.parse("2026-10-05"),
                        title = "Legs + pull",
                        reason = "coach away",
                        exercises = listOf(
                            exercise("Bench press", done(8, 135.0), open(8, 135.0), open(8, 135.0)),
                        ),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "2026-10-05 LIFT \"Legs + pull\" — scrubbed (\"coach away\")",
                "  Bench press: 1×8 @135 lb ✓, 2×8 @135 lb not done",
            ),
            historyLines(prompt),
        )
        assertFalse("a not-done set must not read as skipped", prompt.contains("skipped"))
    }

    /** A timed set is written in seconds, with the same planned-value rule as reps. */
    @Test
    fun timedSetsAreWrittenAsSeconds() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    landed(
                        date = LocalDate.parse("2026-10-05"),
                        title = "Core",
                        exercises = listOf(
                            exercise("Plank", done(seconds = 45), done(seconds = 45), done(seconds = 45), done(seconds = 40, plannedSeconds = 45)),
                            // A timed set with a load keeps both.
                            exercise("Weighted carry", done(seconds = 30, weight = 22.5)),
                        ),
                    )
                )
            )
        )

        assertEquals(
            listOf(
                "2026-10-05 LIFT \"Core\" — landed",
                "  Plank: 3×45 s ✓, 1×40 s (planned 45 s)",
                "  Weighted carry: 1×30 s @22.5 lb ✓",
            ),
            historyLines(prompt),
        )
    }

    /** A reps set with no recorded weight says bodyweight (§5.2); a timed set with no weight says nothing about load. */
    @Test
    fun aRepSetWithNoWeightIsMarkedBodyweight() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    landed(
                        date = LocalDate.parse("2026-10-05"),
                        title = "Bodyweight",
                        exercises = listOf(
                            exercise("Push-up", done(12), done(12), done(12)),
                            exercise("Plank", done(seconds = 45), done(seconds = 45)),
                        ),
                    )
                )
            )
        )

        val lines = historyLines(prompt)
        assertEquals(
            listOf(
                "2026-10-05 LIFT \"Bodyweight\" — landed",
                "  Push-up: 3×12 bodyweight ✓",
                "  Plank: 2×45 s ✓",
            ),
            lines,
        )
        // Only the reps line carries the load word; a hold's load is bodyweight by definition.
        assertEquals(1, lines.count { "bodyweight" in it })
    }

    /** The owner's note on an exercise and the note on the sortie both appear in that sortie's block. */
    @Test
    fun exerciseAndSortieNotesAppearInTheirBlocks() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    landed(
                        date = LocalDate.parse("2026-10-05"),
                        title = "Legs + pull",
                        exercises = listOf(
                            exercise("Barbell row", done(8, 135.0), note = "lower back tired"),
                            exercise("Back squat", done(5, 185.0)),
                        ),
                        notes = "left shoulder twinge on last set",
                    ),
                    landed(
                        date = LocalDate.parse("2026-10-03"),
                        title = "Full body",
                        exercises = listOf(exercise("Bench press", done(8, 135.0))),
                    ),
                )
            )
        )

        assertEquals(
            listOf(
                "2026-10-05 LIFT \"Legs + pull\" — landed",
                "  Barbell row: 1×8 @135 lb ✓ — note: \"lower back tired\"",
                "  Back squat: 1×5 @185 lb ✓",
                "  notes: \"left shoulder twinge on last set\"",
                "",
                "2026-10-03 LIFT \"Full body\" — landed",
                "  Bench press: 1×8 @135 lb ✓",
            ),
            historyLines(prompt),
        )
    }

    /** A scrubbed sortie is marked as scrubbed and carries the reason it was scrubbed. */
    @Test
    fun scrubbedSortieShowsItsReason() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    scrubbed(
                        date = LocalDate.parse("2026-10-03"),
                        title = "Easy 5k",
                        type = SortieType.RUN,
                        reason = "week ended",
                        runDistance = 3.1,
                        runMinutes = 28.0,
                        notes = "knee sore",
                    ),
                    scrubbed(
                        date = LocalDate.parse("2026-10-01"),
                        title = "Full body",
                        reason = null,
                    ),
                )
            )
        )

        assertEquals(
            listOf(
                "2026-10-03 RUN \"Easy 5k\" — scrubbed (\"week ended\")",
                "  run: 3.1 mi in 28 min",
                "  notes: \"knee sore\"",
                "",
                "2026-10-01 LIFT \"Full body\" — scrubbed",
            ),
            historyLines(prompt),
        )
    }

    // ─── §7.4 item 7 / §7.7: exercises used before ──────────────────────────────

    /** Every name in the history, once, sorted; two spellings of one exercise collapse (§7.7). */
    @Test
    fun exercisesUsedBeforeAreSortedAndDeDuplicated() {
        val prompt = CoachPromptBuilder.prompt(
            flightPlan(
                history = listOf(
                    landed(
                        date = LocalDate.parse("2026-10-05"),
                        title = "Full body",
                        exercises = listOf(
                            exercise("back squat", done(5, 185.0)),
                            exercise("Bench Press", done(8, 135.0)),
                            exercise("Plank", done(seconds = 45)),
                        ),
                    ),
                    landed(
                        date = LocalDate.parse("2026-10-03"),
                        title = "Push",
                        exercises = listOf(
                            // §7.7's identity is the normalized name, so this is the same exercise.
                            exercise("bench  press", done(8, 135.0)),
                            exercise("Push-up", done(12)),
                        ),
                    ),
                )
            )
        )

        val lines = sectionLines(prompt, "Exercises used before")
        assertEquals(
            listOf("back squat", "Bench Press", "Plank", "Push-up"),
            lines,
        )
        assertEquals(1, lines.count { ExerciseNames.normalize(it) == "bench press" })
    }

    // ─── §7.4 item 5: the week list ─────────────────────────────────────────────

    /**
     * Plan decision 0: an OUTLINE prompt lists each sortie's number and index, its type and its state
     * only. Before the outline exists the only focus a row can carry is the "Continue without outline"
     * placeholder, and showing it would anchor the coach on it.
     */
    @Test
    fun outlinePromptWeekListsNoFocus() {
        val prompt = CoachPromptBuilder.prompt(
            CoachPromptInput(
                kind = GenerationKind.OUTLINE,
                profile = profile,
                equipment = equipment,
                week = week,
                history = emptyList(),
            )
        )

        assertEquals(listOf("Write the outline for this week"), sectionLines(prompt, "Task"))
        assertEquals(
            listOf(
                "pattern: RLRLR",
                "sortie 1 (index 0) — run — landed",
                "sortie 2 (index 1) — lift — pending",
                "sortie 3 (index 2) — run — pending",
                "In your notes, name a sortie by the number printed beside it, or by its type and focus, never by its index.",
            ),
            sectionLines(prompt, "This week"),
        )
        assertFalse(prompt.contains("focus:"))
        assertFalse(prompt.contains("planning:"))
    }

    /** Plan decision 10: a section with nothing in it keeps its heading and says `none`. */
    @Test
    fun emptySectionsSayNone() {
        val prompt = CoachPromptBuilder.prompt(
            CoachPromptInput(
                kind = GenerationKind.OUTLINE,
                profile = PromptProfile(
                    objectives = "",
                    constraints = "   ",
                    sortieLengthMinutes = 30,
                    weightUnit = WeightUnit.KG,
                    distanceUnit = DistanceUnit.KM,
                ),
                equipment = emptyList(),
                week = PromptWeek("R", listOf(PromptSortie(0, SortieType.RUN, SortieState.PENDING))),
                history = emptyList(),
            )
        )

        assertEquals(
            listOf(
                "objectives: none",
                "constraints: none",
                "sortie length: 30 minutes",
                "units: kg, km",
            ),
            sectionLines(prompt, "Athlete profile"),
        )
        assertEquals("none", sectionLines(prompt, "Equipment").first())
        assertEquals(listOf("none"), sectionLines(prompt, "History"))
        assertEquals(listOf("none"), sectionLines(prompt, "Exercises used before"))
    }

    // ─── the eight §7.4 sections, in order ──────────────────────────────────────

    /** The shape every prompt has: eight `##` sections in §7.4 order, one blank line between them, no title line. */
    @Test
    fun promptCarriesTheEightSectionsInOrder() {
        val prompt = CoachPromptBuilder.prompt(flightPlan(history = listOf(landed(LocalDate.parse("2026-10-05"), "Legs"))))

        val headings = prompt.lines().filter { it.startsWith("## ") }
        assertEquals(
            listOf(
                "## Task",
                "## Instructions",
                "## Athlete profile",
                "## Equipment",
                "## This week",
                "## History",
                "## Exercises used before",
                "## Output schema",
            ),
            headings,
        )
        assertTrue(prompt.startsWith("## Task\n"))
        assertFalse("no blank line before the first heading", prompt.startsWith("\n"))
        for (heading in headings) {
            assertEquals("$heading is followed by a blank line", 1, Regex(Regex.escape(heading) + "\n\n").findAll(prompt).count())
        }
    }

    /** §7.4 item 4: `key — name — notes`, with no trailing dash when an item has no notes. */
    @Test
    fun equipmentLinesNameTheKeyTheNameAndTheNotes() {
        val prompt = CoachPromptBuilder.prompt(flightPlan(history = emptyList()))

        assertEquals(
            listOf(
                "bb — barbell — 20 lb bar, plates to 45 lb",
                "db — dumbbells — pairs 5–50 lb in 5 lb steps",
                "kb — kettlebell",
                "",
                "Exercises may use only these ids. Bodyweight needs no id.",
            ),
            sectionLines(prompt, "Equipment"),
        )
    }

    /** §7.4 item 5, for a Flight Plan: the week list numbers each sortie and names which one is being planned. */
    @Test
    fun flightPlanWeekShowsFocusAndNamesTheSortieBeingPlanned() {
        val prompt = CoachPromptBuilder.prompt(flightPlan(history = emptyList()))

        assertEquals(
            listOf(
                "pattern: RLRLR",
                "sortie 1 (index 0) — run — landed — focus: Easy 5k",
                "sortie 2 (index 1) — lift — pending — focus: Push + core",
                "sortie 3 (index 2) — run — pending — focus: Tempo intervals",
                "planning: sortie 2 (index 1) — lift",
                "In your notes, name a sortie by the number printed beside it, or by its type and focus, never by its index.",
            ),
            sectionLines(prompt, "This week"),
        )
        assertEquals(
            listOf("Write the Flight Plan for sortie 2 (index 1) of this week"),
            sectionLines(prompt, "Task"),
        )
    }

    /** A FLIGHT_PLAN input must name a sortie of its own week, or the prompt would plan nothing. */
    @Test(expected = IllegalArgumentException::class)
    fun flightPlanInputRejectsASortieThatIsNotInThisWeek() {
        CoachPromptInput(
            kind = GenerationKind.FLIGHT_PLAN,
            profile = profile,
            equipment = equipment,
            week = week,
            history = emptyList(),
            sortieIndex = 7,
        )
    }

    // ─── issue 61: the numbering the coach is shown ─────────────────────────────

    /**
     * Issue 61: the coach's notes numbered a sortie by its 0-based index while every screen numbers it
     * from 1, so the athlete read "sortie 1" for the row the Mission tab calls 02. Every prompt now
     * prints both numbers for every sortie — the display number, which is the sortie's 1-based position
     * in the week, and the index the coach replies with — and the `planning:` line is the same sortie,
     * so it must carry the same two numbers.
     */
    @Test
    fun promptNumbersEachSortieByItsDisplayNumberAndKeepsItsIndex() {
        for ((input, prompt) in numberingCases()) {
            val label = caseLabel(input)
            val lines = sectionLines(prompt, "This week")
            val sortieLines = lines.filter { it.startsWith("sortie ") }
            assertEquals("$label: the week list does not print one line for each of the five positions", 5, sortieLines.size)
            assertEquals(
                "$label: the week list is not in index order",
                (0..4).toList(),
                sortieLines.map { numbersIn(it, label).second },
            )
            for (index in 0..4) {
                val line = sortieLines[index]
                val display = index + 1
                assertEquals(
                    "$label: the line for index $index does not name it $display with index $index beside it",
                    display to index,
                    numbersIn(line, label),
                )
                assertTrue(
                    "$label: \"$line\" does not put both numbers for index $index before the type and state",
                    line.startsWith("sortie $display (index $index)"),
                )
            }

            val planning = lines.filter { it.startsWith("planning: ") }
            val planned = input.sortieIndex
            if (planned == null) {
                assertEquals("$label: an OUTLINE prompt names a sortie it is planning", emptyList<String>(), planning)
                continue
            }
            assertEquals("$label: the week list does not name exactly one sortie as being planned", 1, planning.size)
            assertEquals(
                "$label: the planning line does not carry ${planned + 1} and index $planned",
                (planned + 1) to planned,
                numbersIn(planning.single(), label),
            )
            assertEquals(
                "$label: the planning line numbers the sortie being planned differently from its own week line",
                numbersIn(sortieLines[planned], label),
                numbersIn(planning.single(), label),
            )
        }
    }

    /**
     * The rule that fixes issue 61 is one line, in the section that holds the list, in every prompt
     * kind: a rule the coach is told in an outline prompt and not in a Flight Plan prompt is a rule the
     * coach does not have. It points at the number printed beside each sortie, so it says nothing about
     * the indices printed in the same parentheses — and the numbers it points at are the positions, 1…5,
     * not the 0-based indices the coach replies with.
     */
    @Test
    fun everyCoachPromptTellsTheCoachToNameSortiesByTheirNumberNotTheirIndex() {
        val rules = mutableListOf<String>()
        for ((input, prompt) in numberingCases()) {
            val label = caseLabel(input)
            val lines = sectionLines(prompt, "This week")
            val printed = lines.filter { it.startsWith("sortie ") }.map { numbersIn(it, label) }
            val rule = lines.filter {
                !it.startsWith("sortie ") && !it.startsWith("pattern:") && !it.startsWith("planning:")
            }
            assertEquals("$label: the This week section does not carry exactly one line for the coach to follow", 1, rule.size)
            val line = rule.single()
            assertTrue("$label: the line is not about the coach's own notes: $line", line.contains("notes"))
            assertTrue("$label: the line does not name a number as what the coach should write: $line", line.contains("number"))
            assertTrue("$label: the line does not rule the index out of the coach's notes: $line", line.contains("index") && line.contains("never"))
            assertFalse("$label: the line uses the focus field's own wording: $line", line.contains("focus:"))
            assertFalse("$label: the line uses the planning field's own wording: $line", line.contains("planning:"))
            assertFalse("$label: the line names a numbered sortie, so it is another entry in the list: $line", NUMBER_AND_INDEX.containsMatchIn(line))
            assertFalse("$label: the line carries a number of its own instead of pointing at the list's: $line", line.any { it.isDigit() })
            assertEquals("$label: the rule appears more than once in the prompt", 1, prompt.lines().count { it == line })

            assertEquals("$label: the numbers the coach is told to use are not 1..5", (1..5).toList(), printed.map { it.first })
            assertEquals("$label: the indices printed beside those numbers are not 0..4", (0..4).toList(), printed.map { it.second })
            assertTrue("$label: a number the coach is told to use is not its sortie's position + 1: $printed", printed.all { it.first == it.second + 1 })
            rules += line
        }
        assertEquals(3, rules.size)
        assertEquals("the three prompts do not tell the coach the same rule", 1, rules.distinct().size)
    }

    /**
     * The line the coach actually acts on, which the brief's example does not show: it used to read
     * `Write the Flight Plan for sortie 1 of this week` for the sortie its own list called 2 and the
     * Mission tab called 02, so the prompt disagreed with itself about the one sortie in question.
     */
    @Test
    fun flightPlanTaskLineNamesTheSortieByTheNumberItsWeekLinePrints() {
        for (planned in listOf(1, 2)) {
            val input = numberingFlightPlan(planned)
            val prompt = CoachPromptBuilder.prompt(input)
            val label = caseLabel(input)
            val task = sectionLines(prompt, "Task")
            assertEquals("$label: the Task section is not one line naming the session the coach is writing", 1, task.size)
            val line = task.single()
            assertTrue("$label: the Task line does not name the sortie being planned: $line", line.startsWith("Write the Flight Plan for sortie "))
            val fromTask = numbersIn(line, label)
            val weekLine = sectionLines(prompt, "This week").filter { it.startsWith("sortie ") }[planned]
            val fromWeek = numbersIn(weekLine, label)
            assertEquals(
                "$label: the Task line and this prompt's own week list number the sortie being planned differently",
                fromWeek,
                fromTask,
            )
            assertEquals("$label: the Task line does not name index $planned as sortie ${planned + 1}", (planned + 1) to planned, fromTask)
            assertTrue("$label: the Task line does not name index $planned beside the number: $line", line.contains("(index $planned)"))
            assertTrue("$label: the Task line prints the 0-based index under the word sortie: $line", fromTask.first != fromTask.second)
        }
    }

    // ─── §7.3 step 5 / §7.6: the repair prompt ──────────────────────────────────

    /**
     * The repair prompt carries the validator's own lines, unchanged: the errors here are read out of
     * a real `CoachReplyValidator` run on an invalid reply, not written by hand, so a change to the
     * validator's wording moves this test rather than hiding behind it.
     */
    @Test
    fun repairPromptCarriesEveryValidatorErrorAndAsksForOnlyTheCorrectedJson() {
        val context = GenerationContext(
            kind = GenerationKind.FLIGHT_PLAN,
            pattern = week.pattern,
            sortieType = SortieType.LIFT,
            // The same ids the prompt above lists, so the reply is answering that prompt.
            equipmentKeys = equipment.map { it.key },
            sortieLengthMinutes = profile.sortieLengthMinutes,
        )
        val result = CoachReplyValidator.validate(invalidLiftReply(), context)
        assertTrue("the reply should have been rejected, got $result", result is CoachReplyResult.Invalid)
        val errors = (result as CoachReplyResult.Invalid).errors

        // More than one line, so "every error" below is not vacuous, and each is the validator's `path: problem`.
        assertTrue("expected several errors, got $errors", errors.size > 1)
        assertTrue("the checker's own path is missing: $errors", errors.any { it.startsWith("exercises[") })
        assertTrue("the budget line is missing: $errors", errors.any { it.startsWith("estimated_minutes:") })

        val prompt = CoachPromptBuilder.repairPrompt(errors)

        assertEquals("Your reply was not valid:", prompt.lines().first())
        assertEquals("Reply again with only the corrected JSON object.", prompt.lines().last())
        // One line per error, word for word, with nothing added or dropped between the two sentences.
        assertEquals(errors, prompt.lines().drop(1).dropLast(1))
        assertEquals(errors.size + 2, prompt.lines().size)
        for (error in errors) {
            assertTrue("$error is not in the repair prompt:\n$prompt", prompt.contains(error))
        }
    }

    // ─── fixtures ───────────────────────────────────────────────────────────────

    /** A coach-shaped reply with a checker violation and several semantic ones, so the repair has to carry more than one line. */
    private fun invalidLiftReply() = """
        {
          "type": "lift",
          "title": "Push + core",
          "estimated_minutes": 80,
          "warmup": "3 minutes rowing, then band pull-aparts",
          "exercises": [
            {
              "name": "Bench Press",
              "equipment": ["bb"],
              "rest_seconds": 120,
              "sets": [
                { "reps": 5, "weight": 135 },
                { "reps": 5, "seconds": 30 }
              ]
            },
            {
              "name": "bench  press",
              "day": "Tuesday",
              "equipment": ["bb"],
              "sets": [ { "reps": 8, "weight": 135 } ]
            },
            {
              "name": "Row",
              "equipment": ["rowing-machine"],
              "sets": [ {} ]
            }
          ],
          "notes": "Add weight only if all three bench sets were hit."
        }
    """.trimIndent()

    private fun flightPlan(history: List<PromptHistorySortie>) = CoachPromptInput(
        kind = GenerationKind.FLIGHT_PLAN,
        profile = profile,
        equipment = equipment,
        week = week,
        history = history,
        sortieIndex = 1,
    )

    /**
     * The week the numbering tests read, built here rather than by widening the 3-sortie [week]
     * fixture above: five positions, `RLRLR`, indices 0–4, an outline focus stored for the first three —
     * the golden inputs' week.
     */
    private fun fivePositionWeek() = PromptWeek(
        pattern = "RLRLR",
        sorties = listOf(
            PromptSortie(0, SortieType.RUN, SortieState.LANDED, "Easy 5k"),
            PromptSortie(1, SortieType.LIFT, SortieState.PENDING, "Push + core"),
            PromptSortie(2, SortieType.RUN, SortieState.PENDING, "Tempo intervals"),
            PromptSortie(3, SortieType.LIFT, SortieState.PENDING),
            PromptSortie(4, SortieType.RUN, SortieState.PENDING),
        ),
    )

    private fun numberingOutline() = CoachPromptInput(
        kind = GenerationKind.OUTLINE,
        profile = profile,
        equipment = equipment,
        week = fivePositionWeek(),
        history = emptyList(),
    )

    private fun numberingFlightPlan(sortieIndex: Int) = CoachPromptInput(
        kind = GenerationKind.FLIGHT_PLAN,
        profile = profile,
        equipment = equipment,
        week = fivePositionWeek(),
        history = emptyList(),
        sortieIndex = sortieIndex,
    )

    /** The three prompts every numbering test reads: the outline, and a Flight Plan for index 1 and for index 2. */
    private fun numberingCases(): List<Pair<CoachPromptInput, String>> =
        listOf(numberingOutline(), numberingFlightPlan(1), numberingFlightPlan(2))
            .map { it to CoachPromptBuilder.prompt(it) }

    private fun caseLabel(input: CoachPromptInput) =
        if (input.sortieIndex == null) "OUTLINE prompt" else "FLIGHT_PLAN prompt for index ${input.sortieIndex}"

    /** `sortie 2 (index 1)` — the display number and the 0-based index, in the order the builder prints them. */
    private val NUMBER_AND_INDEX = Regex("sortie (\\d+) \\(index (\\d+)\\)")

    /** The two numbers one line prints for one sortie, as `display to index`; a line printing neither fails. */
    private fun numbersIn(line: String, label: String): Pair<Int, Int> {
        val match = NUMBER_AND_INDEX.find(line)
            ?: throw AssertionError("$label: \"$line\" prints no `sortie N (index M)` pair")
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun landed(
        date: LocalDate,
        title: String,
        type: SortieType = SortieType.LIFT,
        exercises: List<PromptHistoryExercise> = emptyList(),
        notes: String? = null,
        runDistance: Double? = null,
        runMinutes: Double? = null,
    ) = PromptHistorySortie(
        date = date,
        type = type,
        title = title,
        state = SortieState.LANDED,
        exercises = exercises,
        notes = notes,
        runDistance = runDistance,
        runMinutes = runMinutes,
    )

    private fun scrubbed(
        date: LocalDate,
        title: String,
        type: SortieType = SortieType.LIFT,
        reason: String? = "week ended",
        exercises: List<PromptHistoryExercise> = emptyList(),
        notes: String? = null,
        runDistance: Double? = null,
        runMinutes: Double? = null,
    ) = PromptHistorySortie(
        date = date,
        type = type,
        title = title,
        state = SortieState.SCRUBBED,
        scrubReason = reason,
        exercises = exercises,
        notes = notes,
        runDistance = runDistance,
        runMinutes = runMinutes,
    )

    private fun exercise(name: String, vararg sets: PromptHistorySet, note: String? = null) =
        PromptHistoryExercise(name, sets.toList(), note)

    /**
     * A set the owner checked off. The In-Flight screen records the actuals as the planned values on a
     * one-tap done, and as what was entered when the set was edited, so the planned triple is kept
     * separate rather than defaulted to the actuals.
     */
    private fun done(
        reps: Int? = null,
        weight: Double? = null,
        seconds: Int? = null,
        plannedReps: Int? = null,
        plannedSeconds: Int? = null,
        plannedWeight: Double? = null,
    ) = PromptHistorySet(reps, seconds, weight, SetStatus.DONE, plannedReps, plannedSeconds, plannedWeight)

    /** An unfinished set reads on its planned values, because it has no actuals of its own. */
    private fun unfinished(
        status: SetStatus,
        reps: Int? = null,
        weight: Double? = null,
        seconds: Int? = null,
    ) = PromptHistorySet(reps, seconds, weight, status, reps, seconds, weight)

    private fun skipped(reps: Int? = null, weight: Double? = null, seconds: Int? = null) =
        unfinished(SetStatus.SKIPPED, reps, weight, seconds)

    private fun open(reps: Int? = null, weight: Double? = null, seconds: Int? = null) =
        unfinished(SetStatus.OPEN, reps, weight, seconds)

    /** The lines of one section, past the blank line after its heading, stopping at the next heading. */
    private fun sectionLines(prompt: String, heading: String): List<String> {
        val lines = prompt.lines()
        val start = lines.indexOf("## $heading")
        assertTrue("no ## $heading section in:\n$prompt", start >= 0)
        val out = mutableListOf<String>()
        for (line in lines.drop(start + 2)) {
            if (line.startsWith("## ")) break
            out += line
        }
        // The blank line the join puts before the next heading is not part of this section.
        return out.dropLastWhile { it.isEmpty() }
    }

    private fun historyLines(prompt: String) = sectionLines(prompt, "History")
}
