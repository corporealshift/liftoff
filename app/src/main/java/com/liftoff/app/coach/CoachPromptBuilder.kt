package com.liftoff.app.coach

import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.SetStatus
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.WeightUnit
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * §7.4's prompt builder: a pure function from [CoachPromptInput] to prompt text.
 *
 * No clock, no random, no locale-sensitive formatting, so the same inputs give byte-identical text.
 * The eight §7.4 sections are always emitted, in order, as `##` headings with one blank line between
 * them and no title line; a section with nothing in it says `none` (plan decision 10) so a
 * first-week prompt reads as a prompt rather than as a malformed one.
 */
object CoachPromptBuilder {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    /** §7.4 item 2, worded as §7.4 words it. */
    private const val INSTRUCTIONS =
        "Follow COACH.md in this workspace. Your final message must be only a JSON object matching the schema below."

    private const val NONE = "none"

    fun prompt(input: CoachPromptInput): String =
        listOf(
            task(input),
            instructions(),
            athleteProfile(input.profile),
            equipment(input.equipment),
            thisWeek(input),
            history(input.history, input.profile),
            exercisesUsedBefore(input.history),
            outputSchema(input),
        ).joinToString(separator = "\n\n")

    // ─── 1. Task ────────────────────────────────────────────────────────────────

    private fun task(input: CoachPromptInput): String =
        when (input.kind) {
            GenerationKind.OUTLINE -> section("Task", "Write the outline for this week")
            // Plan decision 2: the stored 0-based index, which is the numbering the coach has to write
            // in its reply and the validator already reports in.
            GenerationKind.FLIGHT_PLAN ->
                section("Task", "Write the Flight Plan for sortie ${input.sortieIndex} of this week")
        }

    // ─── 2. Instructions ────────────────────────────────────────────────────────

    private fun instructions(): String = section("Instructions", INSTRUCTIONS)

    // ─── 3. Athlete profile ─────────────────────────────────────────────────────

    private fun athleteProfile(profile: PromptProfile): String =
        section(
            "Athlete profile",
            listOf(
                "objectives: ${blankToNone(profile.objectives)}",
                "constraints: ${blankToNone(profile.constraints)}",
                "sortie length: ${profile.sortieLengthMinutes} minutes",
                "units: ${word(profile.weightUnit)}, ${word(profile.distanceUnit)}",
            ),
        )

    // ─── 4. Equipment ───────────────────────────────────────────────────────────

    private fun equipment(items: List<PromptEquipment>): String {
        val lines = items.map { item ->
            // Plan decision 14: no notes, no trailing dash — a dangling dash reads as a lost value.
            if (item.notes.isBlank()) "${item.key} — ${item.name}"
            else "${item.key} — ${item.name} — ${item.notes}"
        }
        return section(
            "Equipment",
            lines.ifEmpty { listOf(NONE) } +
                "" +
                "Exercises may use only these ids. Bodyweight needs no id.",
        )
    }

    // ─── 5. This week ───────────────────────────────────────────────────────────

    private fun thisWeek(input: CoachPromptInput): String {
        val lines = mutableListOf("pattern: ${input.week.pattern}")
        lines += input.week.sorties.map { sortie ->
            val base = "index ${sortie.index} — ${word(sortie.type)} — ${word(sortie.state)}"
            // Plan decision 0: an OUTLINE prompt shows no focus, because before the outline exists the
            // only focus a row can carry is the "Continue without outline" placeholder.
            val focus = sortie.focus?.takeIf { it.isNotBlank() }
            if (input.kind == GenerationKind.FLIGHT_PLAN && focus != null) "$base — focus: $focus" else base
        }
        input.plannedSortie?.let {
            lines += "planning: index ${it.index} — ${word(it.type)}"
        }
        return section("This week", lines)
    }

    // ─── 6. History ─────────────────────────────────────────────────────────────

    private fun history(sorties: List<PromptHistorySortie>, profile: PromptProfile): String {
        if (sorties.isEmpty()) return section("History", NONE)

        val blocks = sorties.map { sortie ->
            val lines = mutableListOf(header(sortie))
            runLine(sortie, profile)?.let { lines += it }
            lines += sortie.exercises.map { exercise ->
                // Plan decision 7: the owner's note, inline on its own exercise line.
                val note = exercise.note?.takeIf { it.isNotBlank() }?.let { " — note: \"$it\"" } ?: ""
                "  ${exercise.name}: " +
                    groupSets(exercise.sets, profile).joinToString(separator = ", ") +
                    note
            }
            sortie.notes?.takeIf { it.isNotBlank() }?.let { lines += "  notes: \"$it\"" }
            lines.joinToString(separator = "\n")
        }
        return section("History", blocks.joinToString(separator = "\n\n"))
    }

    /** The type is uppercase here so the header reads differently from a `index 0 — run — …` week line. */
    private fun header(sortie: PromptHistorySortie): String =
        "${sortie.date.format(DATE)} ${sortie.type.name} \"${sortie.title}\" — ${word(sortie.state)}" +
            // The reason is the one thing that says why this sortie is in the history at all.
            (sortie.scrubReason?.takeIf { it.isNotBlank() }?.let { " (\"$it\")" } ?: "")

    /** Plan decision 8: distance and duration are a run's only actuals; segments are planned values. */
    private fun runLine(sortie: PromptHistorySortie, profile: PromptProfile): String? {
        if (sortie.runDistance == null && sortie.runMinutes == null) return null
        val parts = listOfNotNull(
            sortie.runDistance?.let { "${number(it)} ${word(profile.distanceUnit)}" },
            sortie.runMinutes?.let { "${number(it)} min" },
        )
        return "  run: " + parts.joinToString(separator = " in ")
    }

    /**
     * Plan decision 3: a run of consecutive sets agreeing in status, actuals *and* planned values
     * collapses to `N×…`, which is how §7.4's own example writes it.
     */
    private fun groupSets(sets: List<PromptHistorySet>, profile: PromptProfile): List<String> {
        val groups = mutableListOf<Pair<Int, PromptHistorySet>>()
        for (set in sets) {
            val last = groups.lastOrNull()
            if (last != null && sameSet(last.second, set)) {
                groups[groups.lastIndex] = last.first + 1 to set
            } else {
                groups += 1 to set
            }
        }
        return groups.map { (count, set) -> setLine(count, set, profile) }
    }

    private fun sameSet(a: PromptHistorySet, b: PromptHistorySet): Boolean =
        a == b

    private fun setLine(count: Int, set: PromptHistorySet, profile: PromptProfile): String {
        val timed = (set.seconds ?: set.plannedSeconds) != null
        val reps = if (timed) null else (set.reps ?: set.plannedReps)
        val seconds = if (timed) (set.seconds ?: set.plannedSeconds) else null
        // A set that was not done has no actuals, so it reads on the plan's values. A done set reads on
        // what was recorded, and a missing weight on a done reps set means bodyweight (§5.2).
        val weight = if (set.status == SetStatus.DONE) set.weight else set.weight ?: set.plannedWeight

        val value = if (timed) "${number(seconds)} s" else number(reps)
        val load = when {
            // Plan decision 6: a hold's load is bodyweight by definition, so it gets no word.
            weight == null && !timed -> " bodyweight"
            weight == null -> ""
            else -> " @${number(weight)} ${word(profile.weightUnit)}"
        }
        return "$count×$value$load${tail(set, reps, seconds, weight, profile)}"
    }

    /**
     * Plan decision 5: `skipped` and `not done` are the words for the two unfinished states, because
     * they are different rows. A done set that differed from its plan carries no tick and names the
     * planned value instead, which is how §7.4's own example writes it.
     */
    private fun tail(
        set: PromptHistorySet,
        reps: Int?,
        seconds: Int?,
        weight: Double?,
        profile: PromptProfile,
    ): String {
        if (set.status != SetStatus.DONE) {
            return if (set.status == SetStatus.SKIPPED) " skipped" else " not done"
        }
        val planned = plannedClause(set, reps, seconds, weight, profile)
        return if (planned.isEmpty()) " ✓" else planned
    }

    /** Plan decision 4: the planned value is named only where the actual differs from it. */
    private fun plannedClause(
        set: PromptHistorySet,
        reps: Int?,
        seconds: Int?,
        weight: Double?,
        profile: PromptProfile,
    ): String {
        val repsDiffer = reps != null && set.plannedReps != null && set.plannedReps != reps
        val secondsDiffer = seconds != null && set.plannedSeconds != null && set.plannedSeconds != seconds
        val weightDiffer = weight != null && set.plannedWeight != null && set.plannedWeight != weight
        if (!repsDiffer && !secondsDiffer && !weightDiffer) return ""

        val value = when {
            repsDiffer -> number(set.plannedReps)
            secondsDiffer -> "${number(set.plannedSeconds)} s"
            else -> ""
        }
        val plannedWeight =
            if (weightDiffer) (if (value.isEmpty()) "" else " ") + "@${number(set.plannedWeight)} ${word(profile.weightUnit)}"
            else ""
        return " (planned $value$plannedWeight)"
    }

    // ─── 7. Exercises used before ───────────────────────────────────────────────

    /** Plan decision 12: §7.7's identity is the normalized name, so two spellings are one line. */
    private fun exercisesUsedBefore(sorties: List<PromptHistorySortie>): String {
        val byNormalized = LinkedHashMap<String, String>()
        for (sortie in sorties) {
            for (exercise in sortie.exercises) {
                val normalized = ExerciseNames.normalize(exercise.name)
                if (normalized.isEmpty()) continue
                byNormalized.putIfAbsent(normalized, exercise.name)
            }
        }
        val names = byNormalized.entries
            .sortedWith(compareBy({ it.key }, { it.value }))
            .map { it.value }
        return section("Exercises used before", names.ifEmpty { listOf(NONE) })
    }

    // ─── 8. Output schema ───────────────────────────────────────────────────────

    /** Read from the resource rather than passed in, so the prompt carries the schema the validator will use. */
    private fun outputSchema(input: CoachPromptInput): String {
        val resource = when (input.kind) {
            GenerationKind.OUTLINE -> CoachSchemas.OUTLINE
            GenerationKind.FLIGHT_PLAN ->
                if (input.plannedSortie?.type == SortieType.RUN) CoachSchemas.RUN_PLAN else CoachSchemas.LIFT_PLAN
        }
        return section("Output schema", CoachSchemas.text(resource).trimEnd('\n', '\r'))
    }

    // ─── sections and formatting ────────────────────────────────────────────────

    private fun section(heading: String, vararg lines: String) = section(heading, lines.toList())

    /** `## Heading`, a blank line, then the section's lines; the blank line between sections is the join. */
    private fun section(heading: String, lines: List<String>): String =
        buildString {
            append("## ").append(heading).append("\n\n")
            append(lines.joinToString(separator = "\n"))
        }

    private fun blankToNone(text: String): String = if (text.isBlank()) NONE else text

    private fun number(value: Int?): String = value?.toString() ?: ""

    /**
     * Locale.ROOT, so a load never reads `135,0`. Whole numbers are written whole (`135`, not
     * `135.0`); a fraction keeps it (`22.5`).
     */
    private fun number(value: Double?): String {
        if (value == null) return ""
        if (value == value.toLong().toDouble()) return String.format(Locale.ROOT, "%d", value.toLong())
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    private fun word(unit: WeightUnit): String = when (unit) {
        WeightUnit.LB -> "lb"
        WeightUnit.KG -> "kg"
    }

    private fun word(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.MI -> "mi"
        DistanceUnit.KM -> "km"
    }

    private fun word(type: SortieType): String = when (type) {
        SortieType.LIFT -> "lift"
        SortieType.RUN -> "run"
    }

    private fun word(state: SortieState): String = when (state) {
        SortieState.PENDING -> "pending"
        SortieState.PLANNED -> "planned"
        SortieState.IN_FLIGHT -> "in flight"
        SortieState.LANDED -> "landed"
        SortieState.SCRUBBED -> "scrubbed"
    }
}
