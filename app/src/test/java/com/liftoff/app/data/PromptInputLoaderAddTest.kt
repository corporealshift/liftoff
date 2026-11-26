package com.liftoff.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.coach.CoachPromptBuilder
import com.liftoff.app.settings.SettingsStore
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #88: the other half of DESIGN.md §4.1 (amended 2026-11-26) — the sortie the owner added reaching
 * the coach.
 *
 * `MissionManager.addSortie` writes rows and `PromptInputLoader` reads rows, so nothing on the prompt side had
 * to change: the prompt is built from the Mission's pattern and its sortie rows, and an added sortie is an
 * ordinary pair of those. This is the proof — one real add on real rows, then the input the loader builds out
 * of that same database.
 *
 * The fixture is PromptInputLoaderSwapTest's, so the manager's current-week gate and the loader's `today` agree
 * on the same Mission.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptInputLoaderAddTest {

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
            File(context.cacheDir, "prompt_add_${UUID.randomUUID()}.preferences_pb")
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

    /**
     * A confirmed week that has already done both of its planned sorties, which is the case the brief is
     * really about: the owner got a third workout in that the week was never planned to hold.
     */
    private suspend fun finishedWeek(): Long {
        val missionId = insertMission("LR", MissionStatus.ACTIVE)
        insertSortie(missionId, 0, SortieType.LIFT, "full body", SortieState.LANDED)
        insertSortie(missionId, 1, SortieType.RUN, "easy", SortieState.LANDED)
        return missionId
    }

    @Test
    fun addedSortieJoinsThePatternAndThePromptSentForIt() = runBlocking {
        settingsStore.setDaemonHost("coach.local")
        settingsStore.setDaemonToken("t0ken")

        val missionId = finishedWeek()
        val queued = mutableListOf<Long>()
        manager.addSortie(missionId, SortieType.LIFT) { queued += it }

        // The rows first: the prompt reads them, so a pattern the rows did not carry would show up here.
        assertEquals("LRL", missionDao.get(missionId)!!.pattern)
        assertEquals(MissionStatus.ACTIVE, missionDao.get(missionId)!!.status)
        val added = sortieDao.getForMission(missionId).single { it.index == 2 }

        // The generation the add queued is for the added sortie, so the prompt below is the one that gets sent.
        // advance hands back the generation row's id, which is what the UI hands to the enqueuer.
        val generation = generationDao.getAll().single()
        assertEquals(queued, listOf(generation.id))
        assertEquals(GenerationKind.FLIGHT_PLAN, generation.kind)
        assertEquals(GenerationStatus.QUEUED, generation.status)
        assertEquals(added.id, generation.sortieId)
        assertEquals(missionId, generation.missionId)

        val plan = loader.flightPlanInput(missionId, 2)
        assertEquals(GenerationKind.FLIGHT_PLAN, plan.kind)
        assertEquals(2, plan.sortieIndex)
        assertEquals(2, plan.plannedSortie?.index)
        assertEquals(SortieType.LIFT, plan.plannedSortie?.type)
        assertEquals(SortieState.PENDING, plan.plannedSortie?.state)
        assertEquals("full body", plan.plannedSortie?.focus)
        assertEquals("LRL", plan.week.pattern)
        assertEquals(
            listOf(
                Triple(0, SortieType.LIFT, SortieState.LANDED),
                Triple(1, SortieType.RUN, SortieState.LANDED),
                Triple(2, SortieType.LIFT, SortieState.PENDING),
            ),
            plan.week.sorties.map { Triple(it.index, it.type, it.state) },
        )
        assertEquals(listOf("full body", "easy", "full body"), plan.week.sorties.map { it.focus })

        val rendered = CoachPromptBuilder.prompt(plan)
        assertEquals(
            listOf(
                "pattern: LRL",
                "sortie 1 (index 0) — lift — landed — focus: full body",
                "sortie 2 (index 1) — run — landed — focus: easy",
                "sortie 3 (index 2) — lift — pending — focus: full body",
                "planning: sortie 3 (index 2) — lift",
                "In your notes, name a sortie by its sortie number (as in \"sortie 2\") or by its type and " +
                    "focus, never by its index.",
            ),
            sectionLines(rendered, "This week"),
        )
    }

    // ─── fixtures, in PromptInputLoaderSwapTest's shape ─────────────────────────

    private suspend fun insertMission(pattern: String, status: MissionStatus): Long =
        missionDao.insert(
            Mission(weekStart = thisWeekStart, pattern = pattern, status = status, outlineNotes = null)
        )

    private suspend fun insertSortie(
        missionId: Long,
        index: Int,
        type: SortieType,
        focus: String?,
        state: SortieState,
    ): Long {
        val at = thisWeekStart.atTime(8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return sortieDao.insert(
            Sortie(
                missionId = missionId, index = index, type = type, focus = focus,
                focusRationale = null, state = state,
                launchedAt = if (state == SortieState.LANDED) at else null,
                landedAt = if (state == SortieState.LANDED) at else null,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null,
            )
        )
    }

    /** CoachPromptBuilderTest's reader: the lines of one `##` section, so the week lines are asserted whole. */
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
