package com.liftoff.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.liftoff.app.AppContainer
import com.liftoff.app.LiftoffApplication
import com.liftoff.app.coach.CoachPromptBuilder
import com.liftoff.app.coach.CoachReplyResult
import com.liftoff.app.coach.CoachReplyValidator
import com.liftoff.app.coach.GenerationContext
import com.liftoff.app.data.Generation
import com.liftoff.app.data.GenerationDao
import com.liftoff.app.data.GenerationKind
import com.liftoff.app.data.GenerationStatus
import com.liftoff.app.data.Mission
import com.liftoff.app.data.PromptInputLoader
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.StoreOutcome
import com.liftoff.app.rho.ConnectionOutcome
import com.liftoff.app.rho.net.DaemonClient
import com.liftoff.app.rho.net.DaemonException
import com.liftoff.app.settings.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One generation: the row named by [GENERATION_ID] is asked for one coach turn and, if that turn
 * finishes with a reply that passes validation, the reply is stored and the row is marked SUCCEEDED.
 *
 * Everything about talking to the daemon is [runCoachTurn]'s; this owns the row, the schedule and the
 * storage. The plain `(context, params)` constructor is what lets WorkManager build it without a
 * `WorkerFactory`, tests included.
 */
class GenerationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val clock: Clock = Clock.systemUTC()

    override suspend fun doWork(): Result {
        val container = (applicationContext as LiftoffApplication).container
        val generations = container.database.generationDao()
        val generationId = inputData.getLong(GENERATION_ID, NO_SUCH_GENERATION)

        val row = generations.get(generationId) ?: return Result.failure()
        // A row that already finished is a generation that already happened; re-running it would ask
        // the coach for a second answer to the same question.
        if (row.status in FINISHED_STATUSES) return Result.success()

        generations.markRunning(generationId)

        // The prompt and the validator both need the Mission's pattern and the Sortie's type, so a row
        // that outlived them has nothing left to resume.
        val mission = container.database.missionDao().get(row.missionId)
            ?: return failGeneration(generations, generationId, "Mission ${row.missionId} no longer exists", client = null, sessionId = null)
        val sortie = row.sortieId?.let { container.database.sortieDao().get(it) }
        if (row.kind == GenerationKind.FLIGHT_PLAN && sortie == null) {
            return failGeneration(generations, generationId, "Sortie ${row.sortieId} no longer exists", client = null, sessionId = null)
        }

        return when (container.daemonConnectionCheck().check()) {
            is ConnectionOutcome.Connected -> runGeneration(container, generations, row, mission, sortie)
            else -> Result.retry()
        }
    }

    /** Connects, runs one turn, and hangs up whatever the turn did or didn't end as. */
    private suspend fun runGeneration(
        container: AppContainer,
        generations: GenerationDao,
        row: Generation,
        mission: Mission,
        sortie: Sortie?,
    ): Result {
        val client = container.daemonClient()
        // The id this turn actually ran on, held here so the catch can give it up even when the turn threw
        // before it could report an outcome.
        var reportedSessionId: String? = null
        return try {
            client.connect()
            val settings = container.settingsStore.settings.first()
            val outcome = savingProgressTo(generations, row.id) { onSessionId, onCursor ->
                runCoachTurn(
                    client = client,
                    sessionId = row.nabuSessionId,
                    options = CoachSessionOptions(
                        workspace = settings.coachWorkspacePath,
                        labels = labelsFor(row.kind),
                        description = descriptionOf(row, sortie),
                    ),
                    prompt = promptFor(container, row, sortie),
                    clientId = "${row.id}-${row.attempt}",
                    cursor = row.lastEventId,
                    limit = TURN_LIMIT,
                    onSessionId = { id ->
                        reportedSessionId = id
                        onSessionId(id)
                    },
                    onCursor = onCursor,
                )
            }
            storeFinishedTurn(container, generations, client, row, mission, sortie, settings, outcome)
        } catch (dropped: DaemonException) {
            // A refusal the daemon will give the same answer to every time is a generation that is over.
            // Anything else — a connection that went away, a call it might take next time — is a turn that
            // didn't get to finish, and the progress already saved is where it resumes.
            if (dropped.isPermanent) {
                failGeneration(
                    generations,
                    row.id,
                    dropped.message.orEmpty(),
                    client,
                    reportedSessionId ?: row.nabuSessionId,
                )
            } else {
                Result.retry()
            }
        } finally {
            client.close()
        }
    }

    /**
     * The runner reports the session id and each forward cursor move through plain callbacks, while the
     * row writes are suspend. Each write goes onto a channel and one coroutine works through it in
     * order, so the cursor never lands before the session it belongs to, and the drain is joined before
     * this caller reads or marks the row — including when the turn threw on the way out.
     */
    private suspend fun savingProgressTo(
        generations: GenerationDao,
        generationId: Long,
        turn: suspend (onSessionId: (String) -> Unit, onCursor: (String) -> Unit) -> TurnOutcome,
    ): TurnOutcome = coroutineScope {
        val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        val drain = launch { for (write in writes) write() }
        try {
            turn(
                { sessionId -> writes.trySend { generations.saveSessionId(generationId, sessionId) } },
                { cursor -> writes.trySend { generations.saveLastEventId(generationId, cursor) } },
            )
        } finally {
            writes.close()
            drain.join()
        }
    }

    /**
     * Validate, store, mark SUCCEEDED, then give up the session. A turn that ends blocked, or a store that
     * refuses its target, is over for the same reason every time, so it ends through [failGeneration];
     * anything else leaves the row and the session for the retry.
     */
    private suspend fun storeFinishedTurn(
        container: AppContainer,
        generations: GenerationDao,
        client: DaemonClient,
        row: Generation,
        mission: Mission,
        sortie: Sortie?,
        settings: Settings,
        outcome: TurnOutcome,
    ): Result {
        // A coach that asked for input is a generation that will not answer itself: retrying asks the same
        // question and gets the same question back.
        if (outcome.end == TurnEnd.Blocked) {
            return failGeneration(generations, row.id, "coach needed input", client, outcome.sessionId)
        }
        if (outcome.end != TurnEnd.Idle && outcome.end != TurnEnd.Completed) return Result.retry()
        val raw = outcome.lastAssistantText ?: return Result.retry()

        val context = GenerationContext(
            kind = row.kind,
            pattern = mission.pattern,
            sortieType = sortie?.type,
            equipmentKeys = container.database.equipmentDao().observeActive().first().map { it.key },
            sortieLengthMinutes = settings.sortieLengthMinutes,
        )
        val valid = when (val reply = CoachReplyValidator.validate(raw, context)) {
            is CoachReplyResult.Invalid -> return Result.retry()
            is CoachReplyResult.Valid -> reply
        }

        val stored = when (row.kind) {
            GenerationKind.OUTLINE -> container.missionManager.storeOutline(row.missionId, valid)
            GenerationKind.FLIGHT_PLAN -> container.missionManager.storePlan(requireNotNull(row.sortieId), valid)
        }
        // The store's own reason is the end of it: a target that has moved on does not move back, so a
        // retry would be refused for the same reason.
        if (stored is StoreOutcome.Refused) {
            return failGeneration(generations, row.id, stored.reason, client, outcome.sessionId)
        }

        generations.markSucceeded(row.id, clock.millis())
        // Only a generation that stored its answer gives up its session; every other end resumes it (invariant 6).
        stopCoachSession(client, outcome.sessionId)
        return Result.success()
    }

    private suspend fun promptFor(container: AppContainer, row: Generation, sortie: Sortie?): String {
        val loader = PromptInputLoader(container.database, container.settingsStore)
        val input = when (row.kind) {
            GenerationKind.OUTLINE -> loader.outlineInput(row.missionId)
            GenerationKind.FLIGHT_PLAN -> loader.flightPlanInput(row.missionId, requireNotNull(sortie).index)
        }
        return CoachPromptBuilder.prompt(input)
    }

    /**
     * The one permanent end: the row is FAILED with the reason it ended for, the session this generation got
     * is given up, and nothing is retried. A stop the daemon refuses changes nothing — the outcome was already
     * decided before it was attempted.
     */
    private suspend fun failGeneration(
        generations: GenerationDao,
        generationId: Long,
        message: String,
        client: DaemonClient?,
        sessionId: String?,
    ): Result {
        generations.markFailed(generationId, message, clock.millis())
        if (client != null && sessionId != null) {
            try {
                stopCoachSession(client, sessionId)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (_: Exception) {
                // A session the daemon won't give up is the daemon's to clean up.
            }
        }
        return Result.failure()
    }

    companion object {
        /** The `Generation` row this worker is asked to run. */
        const val GENERATION_ID = "generationId"

        /** A missing key reads as an id no row has, which is the same outcome as a deleted row. */
        private const val NO_SUCH_GENERATION = -1L

        // Invariant 7: rho's runner acts on any label starting with `run:`, so ours never do.
        private fun labelsFor(kind: GenerationKind): List<String> = when (kind) {
            GenerationKind.OUTLINE -> listOf("liftoff", "liftoff:outline")
            GenerationKind.FLIGHT_PLAN -> listOf("liftoff", "liftoff:flight-plan")
        }

        /** Names which generation a session belongs to; carries no prompt text and no user data. */
        private fun descriptionOf(row: Generation, sortie: Sortie?): String =
            if (row.kind == GenerationKind.OUTLINE) {
                "liftoff generation ${row.id}: outline for mission ${row.missionId}"
            } else {
                "liftoff generation ${row.id}: flight plan for sortie ${row.sortieId}"
            }

        private val FINISHED_STATUSES = setOf(
            GenerationStatus.SUCCEEDED,
            GenerationStatus.FAILED,
            GenerationStatus.CANCELLED,
        )
    }
}

/**
 * The turn's own deadline, not WorkManager's: being stopped at the ~10-minute mark is just an early
 * retry, and the stored session id and cursor make that retry a continuation of the same turn.
 */
private val TURN_LIMIT: Duration = 20.minutes
