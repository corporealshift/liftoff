package com.liftoff.app.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
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

    private companion object {
        const val GENERATION = 42L

        /** The id a missing key reads as, so a dropped input cannot pass as a real generation. */
        const val NO_SUCH_GENERATION = -1L
    }
}
