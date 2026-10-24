package com.liftoff.app.work

import androidx.room.withTransaction
import androidx.work.WorkManager
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.rho.net.DaemonClient
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Cancels one generation: the work scheduled for it, its `Generation` row, and the rho session the row was
 * running on.
 *
 * The local half comes first and whatever the daemon does afterwards, because the row is the part the app acts
 * on. The session stop is the last thing attempted, and a daemon that isn't there — or a session it won't give
 * up — cannot turn a cancel that already happened into an error.
 */
class GenerationCanceller(
    private val workManager: WorkManager,
    private val database: LiftoffDatabase,
    /**
     * Asked for at stop time rather than built up front: the container hands out a client through a suspend
     * call that reads the daemon's settings, and a cancel is asked for at moments when nothing is connected.
     */
    private val openClient: suspend () -> DaemonClient,
) {
    private val clock: Clock = Clock.systemUTC()

    /** Cancels [generationId]'s work and row, then gives up its session. A generation with no row is only work. */
    suspend fun cancel(generationId: Long) {
        workManager.cancelUniqueWork(uniqueName(generationId))

        // The read and the mark share one transaction, so a worker storing at the same moment is either
        // entirely before this or entirely after it.
        val cancelled: Generation? = database.withTransaction {
            val row = database.generationDao().get(generationId)
            // A row that already decided its outcome is a generation that already happened, and its stored
            // result is something the user can still read.
            if (row == null || row.status !in CANCELLABLE_STATUSES) return@withTransaction null
            database.generationDao().markCancelled(generationId, clock.millis())
            row
        }

        val sessionId = cancelled?.nabuSessionId ?: return
        giveUpSession(sessionId)
    }

    /**
     * The stop on its own, swallowing exactly what the worker's own give-up swallows: a connect that never
     * opened and a session the daemon refuses are both the daemon's to clean up, and neither changes a row
     * that is already CANCELLED.
     */
    private suspend fun giveUpSession(sessionId: String) {
        var client: DaemonClient? = null
        try {
            client = openClient()
            client.connect()
            stopCoachSession(client, sessionId)
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (_: Exception) {
        } finally {
            client?.close()
        }
    }

    private companion object {
        val CANCELLABLE_STATUSES = setOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)
    }
}
