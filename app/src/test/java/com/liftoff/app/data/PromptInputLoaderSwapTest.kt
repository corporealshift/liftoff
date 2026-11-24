package com.liftoff.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachPromptBuilder
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * The other half of DESIGN.md §9 (amended 2026-11-23): the swapped row reaching the coach's prompt.
 *
 * `MissionManager.swapSortieType` writes rows and `PromptInputLoader` reads rows, so this is the seam between
 * them — one real swap on real rows, then the inputs the loader builds out of that same database. Nothing here
 * is a fixture written beside the test: the type, the focus and the pattern letter the prompt ends up carrying
 * are the ones the manager wrote.
 *
 * The fixture is PromptInputLoaderTest's (in-memory Room, a real SettingsStore, the date and zone injected) with
 * MissionManagerSwapTest's clock, so the manager's current-week gate and the loader's `today` agree on the same
 * Mission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptInputLoaderSwapTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var missionDao: MissionDao
    private lateinit var sortieDao: SortieDao
    private lateinit var generationDao: GenerationDao
    private lateinit var settingsStore: SettingsStore
    private lateinit var manager: MissionManager
    private lateinit var loader: PromptInputLoader

    /** A Monday, so it is both the fixed 'today' and the weekStart the Mission carries. */
    private val thisWeekStart = LocalDate.of(2026, 1, 12)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        missionDao = db.missionDao()
        sortieDao = db.sortieDao()
        generationDao = db.generationDao()

        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
            File(context.cacheDir, "prompt_swap_${UUID.randomUUID()}.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)

        val clock = Clock.fixed(thisWeekStart.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
        val zone: () -> ZoneId = { ZoneOffset.UTC }
        manager = MissionManager(db, settingsStore, clock = clock, zone = zone)
        loader = PromptInputLoader(db, settingsStore, clock = clock, zone = zone)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // Must show: after a swap, the prompt inputs loaded from the real rows carry the swapped sortie with its new
    // type and default focus and no rationale, and the week's pattern shows the flipped letter.
    @Test
    fun promptInputsForASwappedSortieCarryItsNewTypeAndDefaultFocus() = runBlocking {
        settingsStore.setDaemonHost("coach.local")
        settingsStore.setDaemonToken("t0ken")
        settingsStore.setGenerateRunPlans(true)

        // A lift day the owner had already written a focus and a rationale for, at index 0 of this week, so the
        // swap replaces words the prompt would otherwise have carried.
        val missionId = insertMission(thisWeekStart, "LRLR", MissionStatus.ACTIVE)
        val sortieId = insertSortie(missionId, 0, SortieType.LIFT, "lats and shoulders", SortieState.PENDING)
        sortieDao.update(sortieDao.get(sortieId)!!.copy(focusRationale = "lower back was tight"))
        insertSortie(missionId, 1, SortieType.RUN, "easy", SortieState.PENDING)
        insertSortie(missionId, 2, SortieType.LIFT, "full body", SortieState.PENDING)
        insertSortie(missionId, 3, SortieType.RUN, "easy", SortieState.PENDING)

        manager.swapSortieType(sortieId)

        // The rows first, because PromptSortie carries a focus and has no rationale field to carry: the absence of
        // the rationale is a fact about the row the loader read.
        val row = sortieDao.get(sortieId)!!
        assertEquals(SortieType.RUN, row.type)
        assertEquals("easy", row.focus)
        assertNull("the rationale went with the focus it justified", row.focusRationale)
        assertEquals("RRLR", missionDao.get(missionId)!!.pattern)

        val outline = loader.outlineInput(missionId)
        assertEquals("RRLR", outline.week.pattern)
        assertEquals(
            listOf(
                Triple(0, SortieType.RUN, SortieState.PENDING),
                Triple(1, SortieType.RUN, SortieState.PENDING),
                Triple(2, SortieType.LIFT, SortieState.PENDING),
                Triple(3, SortieType.RUN, SortieState.PENDING),
            ),
            outline.week.sorties.map { Triple(it.index, it.type, it.state) },
        )
        assertEquals(listOf("easy", "easy", "full body", "easy"), outline.week.sorties.map { it.focus })

        // The FLIGHT_PLAN prompt for the swapped sortie is the one the swap queued, so it is the prompt that has
        // to read as a run day.
        val plan = loader.flightPlanInput(missionId, 0)
        assertEquals(GenerationKind.FLIGHT_PLAN, plan.kind)
        assertEquals(0, plan.sortieIndex)
        assertEquals(0, plan.plannedSortie?.index)
        assertEquals(SortieType.RUN, plan.plannedSortie?.type)
        assertEquals(SortieState.PENDING, plan.plannedSortie?.state)
        assertEquals("easy", plan.plannedSortie?.focus)
        assertEquals("RRLR", plan.week.pattern)

        val rendered = CoachPromptBuilder.prompt(plan)
        assertEquals(
            listOf(
                "pattern: RRLR",
                "index 0 — run — pending — focus: easy",
                "index 1 — run — pending — focus: easy",
                "index 2 — lift — pending — focus: full body",
                "index 3 — run — pending — focus: easy",
                "planning: index 0 — run",
            ),
            sectionLines(rendered, "This week"),
        )
        assertFalse("the focus the lift day held is gone from the prompt", rendered.contains("lats and shoulders"))
        assertFalse("and so is the rationale that justified it", rendered.contains("lower back was tight"))

        // The generation the swap queued is for the swapped sortie, so the prompt above is the one that gets sent.
        val queued = generationDao.getAll().single()
        assertEquals(GenerationKind.FLIGHT_PLAN, queued.kind)
        assertEquals(GenerationStatus.QUEUED, queued.status)
        assertEquals(sortieId, queued.sortieId)
        assertEquals(missionId, queued.missionId)
    }

    // ─── fixtures, in PromptInputLoaderTest's shape ─────────────────────────────

    private suspend fun insertMission(
        weekStart: LocalDate,
        pattern: String,
        status: MissionStatus,
    ): Long = missionDao.insert(Mission(weekStart = weekStart, pattern = pattern, status = status, outlineNotes = null))

    private suspend fun insertSortie(
        missionId: Long,
        index: Int,
        type: SortieType,
        focus: String?,
        state: SortieState,
    ): Long = sortieDao.insert(
        Sortie(
            missionId = missionId, index = index, type = type, focus = focus,
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        )
    )

    /** CoachPromptBuilderTest's reader: the lines of one `##` section, so the week line is asserted whole. */
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
}
