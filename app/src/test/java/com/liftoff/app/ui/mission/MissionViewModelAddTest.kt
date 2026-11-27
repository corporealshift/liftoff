package com.liftoff.app.ui.mission

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections

/**
 * The Mission tab's Add sortie action (§4.1, amended 2026-11-26), against a real MissionManager: what the dialog
 * opens on, what a confirm writes, and what a refusal or a cancel leaves exactly as it found it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionViewModelAddTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var manager: MissionManager
    private lateinit var settingsStore: SettingsStore
    private val screenScope = CoroutineScope(Dispatchers.Default + Job())
    private val actionScope = CoroutineScope(Dispatchers.Default + Job())

    /** Generation row ids the view model handed to the enqueuer, in the order it asked. */
    private val queued = Collections.synchronizedList(mutableListOf<Long>())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "add-sortie-${System.nanoTime()}.preferences_pb")
        }
        settingsStore = SettingsStore(dataStore)
        manager = MissionManager(
            db, settingsStore,
            clock = Clock.fixed(Instant.parse("2026-01-14T00:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC },
        )
    }

    @After
    fun tearDown() {
        screenScope.cancel()
        actionScope.cancel()
        db.close()
    }

    private fun viewModel(): MissionViewModel = MissionViewModel(
        db, manager, screenScope,
        queueGeneration = { queued += it },
        actionScope = actionScope,
    )

    /**
     * Mission Control reachable and run plans generated, so a prepared sortie is a queued generation rather than
     * the simple run plan. `generateRunPlans` defaults off, so leaving it out would quietly take the generation
     * path out of the test.
     */
    private suspend fun coachIsConfigured() {
        settingsStore.setDaemonHost("127.0.0.1")
        settingsStore.setDaemonToken("test-token")
        settingsStore.setGenerateRunPlans(true)
    }

    /**
     * The fixture's week: onAppOpen's own row, re-read as [pattern] and [status], with sortie 0 landed and sortie
     * 1 pending, so the current sortie is never the one an add appends. Returns the stored row.
     */
    private suspend fun confirmedWeek(
        pattern: String,
        status: MissionStatus = MissionStatus.ACTIVE,
    ): Mission {
        val created = manager.onAppOpen()
        db.missionDao().update(created.copy(pattern = pattern, status = status))
        db.sortieDao().insert(Sortie(
            missionId = created.id, index = 0, type = SortieType.LIFT, focus = "full body",
            focusRationale = null, state = SortieState.LANDED, launchedAt = 1_000L, landedAt = 2_000L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        ))
        db.sortieDao().insert(Sortie(
            missionId = created.id, index = 1, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        ))
        return db.missionDao().getByWeekStart(WEEK_START)!!
    }

    /**
     * The fixture's finished week: every sortie in [pattern] landed, so nothing is current until an add appends
     * one and it becomes the current sortie. Returns the stored row.
     */
    private suspend fun finishedWeek(pattern: String): Mission {
        val created = manager.onAppOpen()
        db.missionDao().update(created.copy(pattern = pattern, status = MissionStatus.CLOSED))
        pattern.forEachIndexed { index, letter ->
            val lift = letter == 'L'
            db.sortieDao().insert(Sortie(
                missionId = created.id, index = index,
                type = if (lift) SortieType.LIFT else SortieType.RUN,
                focus = if (lift) "full body" else "easy",
                focusRationale = null, state = SortieState.LANDED,
                launchedAt = 1_000L + index * 2_000L, landedAt = 2_000L + index * 2_000L,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null,
            ))
        }
        return db.missionDao().getByWeekStart(WEEK_START)!!
    }

    /** The tab's Week once the view model's own subscription has produced one [where] accepts. */
    private suspend fun MissionViewModel.awaitWeek(
        where: (MissionTabState.Week) -> Boolean = { true },
    ): MissionTabState.Week = withTimeout(10_000) {
        state.first { it is MissionTabState.Week && where(it) } as MissionTabState.Week
    }

    /** Waits for the enqueuer's ids to arrive; a launch after a commit is not synchronous. */
    private suspend fun awaitQueued(count: Int) {
        withTimeout(10_000) { while (queued.size < count) delay(POLL_MS) }
    }

    /** Long enough for a write that should not have happened to land, so its absence is proven. */
    private suspend fun awaitNothing() = delay(SETTLE_MS)

    // ── The dialog opens on a confirmed week, not on a draft ───────────

    @Test
    fun requestAddOpensTheDialogOnAConfirmedWeekAndNotOnADraft() = runBlocking {
        val draft = manager.onAppOpen()
        val viewModel = viewModel()
        val draftWeek = viewModel.awaitWeek()
        assertEquals(MissionStatus.DRAFT, draftWeek.status)
        assertFalse("a draft has a pattern to edit, not a sortie to add", draftWeek.canAdd)

        viewModel.requestAdd()
        assertFalse(viewModel.adding)

        // Even a confirm asked out of turn writes nothing on a draft.
        viewModel.confirmAdd(SortieType.LIFT)
        awaitNothing()
        assertFalse(viewModel.adding)
        val stored = db.missionDao().getByWeekStart(WEEK_START)!!
        assertEquals(MissionStatus.DRAFT, stored.status)
        assertEquals(draft.pattern, stored.pattern)
        assertTrue(db.sortieDao().getForMission(draft.id).isEmpty())
        assertTrue(queued.isEmpty())

        confirmedWeek(pattern = "LR")
        val week = viewModel.awaitWeek { it.status == MissionStatus.ACTIVE }
        assertTrue(week.canAdd)
        viewModel.requestAdd()
        assertTrue("the confirmed week offers the dialog", viewModel.adding)
        viewModel.cancelAdd()
    }

    // ── A confirm appends the sortie; the current one is what gets prepared ──

    @Test
    fun confirmAddAppendsTheSortieAndPreparesTheCurrentSortieNotTheAppendedOne() = runBlocking {
        coachIsConfigured()
        val mission = confirmedWeek(pattern = "LR")
        val viewModel = viewModel()
        assertTrue(viewModel.awaitWeek { it.status == MissionStatus.ACTIVE }.canAdd)

        viewModel.requestAdd()
        assertTrue(viewModel.adding)
        viewModel.confirmAdd(SortieType.LIFT)
        awaitQueued(1)
        assertFalse("the dialog is closed once the week is written", viewModel.adding)

        val stored = db.missionDao().getByWeekStart(WEEK_START)!!
        assertEquals("the new type joins the end of the pattern", "LRL", stored.pattern)
        assertEquals(MissionStatus.ACTIVE, stored.status)

        val sorties = db.sortieDao().getForMission(mission.id)
        assertEquals(3, sorties.size)
        val added = sorties.single { it.index == 2 }
        assertEquals(SortieType.LIFT, added.type)
        assertEquals("full body", added.focus)
        assertEquals(SortieState.PENDING, added.state)
        assertTrue("it is not the current sortie, so nothing is prepared for it yet",
            db.flightPlanDao().getPlan(added.id) == null)
        assertTrue(db.generationDao().getOpenFlightPlanForSortie(added.id).isEmpty())

        // advance() prepares the week's current sortie, which is still the one at index 1.
        val current = sorties.single { it.index == 1 }
        assertEquals(SortieState.PENDING, current.state)
        val rows = db.generationDao().getOpenForMission(mission.id)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(GenerationKind.FLIGHT_PLAN, row.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertEquals(current.id, row.sortieId)
        assertEquals(mission.id, row.missionId)
        assertEquals(listOf(row.id), queued.toList())
    }

    // ── A finished week reopens, and the new sortie is the current one ─

    @Test
    fun confirmAddOnAFinishedWeekReopensItAndPreparesTheSortieItAppended() = runBlocking {
        coachIsConfigured()
        val mission = finishedWeek(pattern = "LL")
        val viewModel = viewModel()
        assertTrue("a finished week can still take a workout",
            viewModel.awaitWeek { it.status == MissionStatus.CLOSED }.canAdd)

        viewModel.requestAdd()
        assertTrue(viewModel.adding)
        viewModel.confirmAdd(SortieType.RUN)
        awaitQueued(1)

        val stored = db.missionDao().getByWeekStart(WEEK_START)!!
        assertEquals("LLR", stored.pattern)
        assertEquals("the week is open again so the new sortie can be launched",
            MissionStatus.ACTIVE, stored.status)

        val added = db.sortieDao().getForMission(mission.id).single { it.index == 2 }
        assertEquals(SortieType.RUN, added.type)
        assertEquals("easy", added.focus)
        assertEquals(SortieState.PENDING, added.state)

        // Appended after every other sortie, it is now the current one, so advance() prepares it.
        val rows = db.generationDao().getOpenFlightPlanForSortie(added.id)
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(GenerationKind.FLIGHT_PLAN, row.kind)
        assertEquals(GenerationStatus.QUEUED, row.status)
        assertEquals(added.id, row.sortieId)
        assertEquals(listOf(row.id), queued.toList())
    }

    // ── A week that already holds seven sorties writes nothing ─────────

    @Test
    fun addOnAWeekThatAlreadyHoldsItsSevenSortiesWritesNothing() = runBlocking {
        coachIsConfigured()
        val mission = confirmedWeek(pattern = "LLRLRLR")
        val before = db.sortieDao().getForMission(mission.id).map { it.id }.toSet()
        val viewModel = viewModel()
        assertFalse("seven chips is the cap",
            viewModel.awaitWeek { it.status == MissionStatus.ACTIVE }.canAdd)

        viewModel.requestAdd()
        assertFalse("no dialog on a full week", viewModel.adding)

        // A confirm asked out of turn is refused by the manager too.
        viewModel.confirmAdd(SortieType.RUN)
        awaitNothing()
        assertFalse(viewModel.adding)
        val stored = db.missionDao().getByWeekStart(WEEK_START)!!
        assertEquals("LLRLRLR", stored.pattern)
        assertEquals(MissionStatus.ACTIVE, stored.status)
        assertEquals(before, db.sortieDao().getForMission(mission.id).map { it.id }.toSet())
        assertTrue(queued.isEmpty())
        assertTrue(db.generationDao().getOpenForMission(mission.id).isEmpty())
    }

    // ── A cancel writes nothing ────────────────────────────────────────

    @Test
    fun cancelAddLeavesTheWeekExactlyAsItWas() = runBlocking {
        coachIsConfigured()
        val mission = confirmedWeek(pattern = "LLR")
        val viewModel = viewModel()
        assertTrue(viewModel.awaitWeek { it.status == MissionStatus.ACTIVE }.canAdd)

        viewModel.requestAdd()
        assertTrue(viewModel.adding)
        viewModel.cancelAdd()
        assertFalse(viewModel.adding)

        awaitNothing()
        val stored = db.missionDao().getByWeekStart(WEEK_START)!!
        assertEquals("LLR", stored.pattern)
        assertEquals(MissionStatus.ACTIVE, stored.status)
        assertEquals(2, db.sortieDao().getForMission(mission.id).size)
        assertTrue(queued.isEmpty())
        assertTrue(db.generationDao().getOpenForMission(mission.id).isEmpty())
    }

    private companion object {
        val WEEK_START = LocalDate.of(2026, 1, 12)
        const val POLL_MS = 20L
        const val SETTLE_MS = 250L
    }
}
