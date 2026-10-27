package com.liftoff.app.work

import androidx.room.withTransaction
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.LiftoffDatabase
import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.domain.currentSortie
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reconciles every open (QUEUED or RUNNING) `Generation` row with what the app is pointing at, at each app open
 * once the week rollover has run. A row whose target is still current goes to [enqueue] under the row it already
 * has — that is what resumes a generation the app asked for but lost to a process kill between its commit and
 * its enqueue. A row whose Mission or sortie has moved on goes to [cancel].
 *
 * [weekStart] is the week the rollover just used, handed over by the caller rather than worked out again here, so
 * a row is never judged against a different week from the one that scrubbed its sortie. The three conditions are
 * the ones `MissionManager.storePlan` and `storeOutline` already enforce before storing a result, so the sweep
 * re-checks the app's existing authority over what a generation may still write rather than adding a new rulebook.
 *
 * The rows and their Missions and sorties are read in one Room transaction so every decision is made against one
 * snapshot; the helpers are called after it commits, never inside it. Nothing is handed to either lambda when the
 * table holds no open row, which is what keeps WorkManager out of an app open that has nothing to repair. The
 * sweep reads no settings: a row asked for while Mission Control was configured is still resumed under the
 * worker's offline handling when it isn't. And it never queues a generation the app did not already ask for.
 */
class AppOpenSweep(
    private val db: LiftoffDatabase,
    /** Hands one wanted row's id to the enqueue helper. Asked for per row, never at construction. */
    private val enqueue: (Long) -> Unit,
    /** Hands one unwanted row's id to the cancel helper: its work, its row, and its session. */
    private val cancel: suspend (Long) -> Unit,
) {

    /** Decide every open row against [weekStart], then hand each one to its helper. */
    suspend fun run(weekStart: LocalDate) {
        // The reads can fail the same way a row can — a database that is gone is not something an app open can
        // repair — so they are wrapped too. Per-row wrapping alone would not cover them.
        val (wanted, unwanted) = try {
            db.withTransaction { decide(weekStart) }
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (_: Exception) {
            return
        }

        // One bad row must not stop the rest, and the app must not crash on one. Cancellation is the only
        // failure that travels out, so a sweep that is being stopped still stops.
        for (id in wanted) {
            try {
                enqueue(id)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (_: Exception) {
            }
        }
        for (id in unwanted) {
            try {
                cancel(id)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (_: Exception) {
            }
        }
    }

    /** The open rows split into the ids to enqueue and the ids to cancel, in the order the table lists them. */
    private suspend fun decide(weekStart: LocalDate): Pair<List<Long>, List<Long>> {
        val rows = db.generationDao().getOpen()
        if (rows.isEmpty()) return emptyList<Long>() to emptyList()

        val missions = db.missionDao().getByIds(rows.map { it.missionId }.distinct()).associateBy { it.id }
        val sorties = missions.keys.associateWith { db.sortieDao().getForMission(it) }

        val wanted = mutableListOf<Long>()
        val unwanted = mutableListOf<Long>()
        for (row in rows) {
            if (isWanted(row, missions[row.missionId], sorties[row.missionId], weekStart)) {
                wanted += row.id
            } else {
                unwanted += row.id
            }
        }
        return wanted to unwanted
    }

    /**
     * Whether [row] is still asking for something the app wants. A Mission or sortie that no longer exists is
     * unwanted rather than skipped: a row nothing can resolve keeps its WorkManager slot and its "generating"
     * state forever.
     */
    private fun isWanted(
        row: Generation,
        mission: Mission?,
        sorties: List<Sortie>?,
        weekStart: LocalDate,
    ): Boolean {
        if (mission == null || mission.weekStart != weekStart) return false
        if (row.kind == GenerationKind.OUTLINE) return mission.status == MissionStatus.DRAFT

        if (mission.status != MissionStatus.ACTIVE) return false
        val sortieId = row.sortieId ?: return false
        val list = sorties ?: return false
        val sortie = list.firstOrNull { it.id == sortieId } ?: return false
        val current = currentSortie(list) ?: return false
        return current.id == sortieId && sortie.state in WANTED_SORTIE_STATES
    }

    private companion object {
        // A plan for a sortie the owner has already launched, landed or scrubbed is one nobody acts on.
        val WANTED_SORTIE_STATES = setOf(SortieState.PENDING, SortieState.PLANNED)
    }
}
