package com.liftoff.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The request the helper hands WorkManager, read off the request itself: `WorkInfo` carries neither
 * constraints nor backoff. The network constraint is left unmet, so no worker ever runs here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationEnqueuerTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Each test gets its own in-memory WorkDatabase, so a work name never holds an earlier test's item.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun theScheduledWorkRequiresAConnectedNetworkAndExponentialBackoffFromOneMinute() {
        val spec = GenerationEnqueuer(WorkManager.getInstance(context)).enqueue(GENERATION).workSpec

        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(60_000L, spec.backoffDelayDuration)
    }

    @Test
    fun theScheduledWorkCarriesTheGenerationIdInTheWorkersInputData() {
        val spec = GenerationEnqueuer(WorkManager.getInstance(context)).enqueue(GENERATION).workSpec

        assertEquals(GenerationWorker::class.java.name, spec.workerClassName)
        assertEquals(GENERATION, spec.input.getLong(GenerationWorker.GENERATION_ID, NO_SUCH_GENERATION))
    }

    @Test
    fun enqueueingOneGenerationTwiceLeavesASingleWorkItemUnderItsName() {
        val workManager = WorkManager.getInstance(context)
        val enqueuer = GenerationEnqueuer(workManager)
        val first = enqueuer.enqueue(GENERATION)
        enqueuer.enqueue(GENERATION)

        val items = workManager.itemsUnder(GENERATION_NAME)

        assertEquals(1, items.size)
        assertEquals(WorkInfo.State.ENQUEUED, items.single().state)
        // WorkManager reports no policy; the id the first call scheduled is what tells KEEP from REPLACE.
        assertEquals(first.id, items.single().id)
    }

    @Test
    fun twoGenerationsAreScheduledAsTwoWorkItemsUnderTheirOwnNames() {
        val workManager = WorkManager.getInstance(context)
        val enqueuer = GenerationEnqueuer(workManager)
        val first = enqueuer.enqueue(GENERATION)
        val second = enqueuer.enqueue(OTHER_GENERATION)

        val underFirst = workManager.itemsUnder(GENERATION_NAME)
        val underSecond = workManager.itemsUnder(OTHER_GENERATION_NAME)

        assertEquals(1, underFirst.size)
        assertEquals(1, underSecond.size)
        assertEquals(first.id, underFirst.single().id)
        assertEquals(second.id, underSecond.single().id)
        assertNotEquals(underFirst.single().id, underSecond.single().id)
    }

    /** What WorkManager actually stored under a unique name. The wait is bounded so a stuck read cannot eat the build's timeout. */
    private fun WorkManager.itemsUnder(name: String): List<WorkInfo> =
        runBlocking { withTimeout(READ_BOUND_MILLIS) { getWorkInfosForUniqueWork(name).await() } }

    private companion object {
        const val GENERATION = 42L
        const val OTHER_GENERATION = 43L

        /** The names spelled out here, so a unique name that stops carrying the generation id fails instead of matching. */
        const val GENERATION_NAME = "gen-42"
        const val OTHER_GENERATION_NAME = "gen-43"

        /** The id a missing key reads as, so a dropped input cannot pass as a real generation. */
        const val NO_SUCH_GENERATION = -1L

        const val READ_BOUND_MILLIS = 5_000L
    }
}
