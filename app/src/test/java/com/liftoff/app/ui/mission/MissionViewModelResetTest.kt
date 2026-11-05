package com.liftoff.app.ui.mission

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionManager
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MissionViewModelResetTest {

    private lateinit var db: LiftoffDatabase
    private lateinit var manager: MissionManager
    private val screenScope = CoroutineScope(Dispatchers.Default + Job())
    private val actionScope = CoroutineScope(Dispatchers.Default + Job())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LiftoffDatabase::class.java)
            .allowMainThreadQueries().build()
        val dataStore = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create {
            java.io.File(context.cacheDir, "settings.preferences_pb")
        }
        manager = MissionManager(
            db, SettingsStore(dataStore),
            clock = Clock.fixed(Instant.parse("2026-01-14T00:00:00Z"), ZoneOffset.UTC),
            zone = { ZoneOffset.UTC }
        )
    }

    @After
    fun tearDown() {
        screenScope.cancel()
        actionScope.cancel()
        db.close()
    }

    // The owner leaves the tab straight after a reset, which cancels the screen's scope, and one session stop
    // waits on the daemon. Every open generation must still be cancelled.
    @Test
    fun `every open generation is cancelled even when the tab goes away and one stop hangs`() = runBlocking {
        val missionId = db.missionDao().insert(Mission(
            weekStart = LocalDate.of(2026, 1, 12), pattern = "RLR", status = MissionStatus.ACTIVE, outlineNotes = null
        ))
        val ids = (1..3).map {
            db.generationDao().insert(Generation(
                kind = GenerationKind.FLIGHT_PLAN, missionId = missionId, status = GenerationStatus.RUNNING,
                createdAt = it.toLong()
            ))
        }
        manager.onAppOpen()

        val cancelled = Collections.synchronizedList(mutableListOf<Long>())
        val viewModel = MissionViewModel(
            db, manager, screenScope,
            cancelGeneration = { id ->
                cancelled += id
                if (id == ids.last()) awaitCancellation()
            },
            actionScope = actionScope,
        )
        viewModel.state.first { it is MissionTabState.Week }

        viewModel.requestReset()
        viewModel.confirmReset()
        screenScope.cancel()

        withTimeout(10_000) {
            while (cancelled.size < ids.size) delay(20)
        }
        assertEquals(ids.toSet(), cancelled.toSet())
    }
}
