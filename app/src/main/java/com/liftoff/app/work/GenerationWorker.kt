package com.liftoff.app.work

import android.content.Context
import androidx.room.withTransaction
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
import com.liftoff.app.rho.protocol.PROTOCOL_VERSION
import com.liftoff.app.settings.Settings
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * One generation: the row named by [GENERATION_ID] is asked for a coach turn and, if that turn finishes
 * with a reply that passes validation, the reply is stored and the row is marked SUCCEEDED. A reply that
 * fails validation is put back to the same session with the validator's own error lines, up to
 * [MAX_REPAIRS] times for the generation.
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

        // A cancel or a late duplicate attempt that landed after the read above: the guarded write refused
        // the row, so there is no generation left to open a turn for.
        if (generations.markRunning(generationId) == 0) return Result.success()

        // The prompt and the validator both need the Mission's pattern and the Sortie's type, so a row
        // that outlived them has nothing left to resume.
        val mission = container.database.missionDao().get(row.missionId)
            ?: return failGeneration(generations, generationId, "Mission ${row.missionId} no longer exists", client = null, sessionId = null)
        val sortie = row.sortieId?.let { container.database.sortieDao().get(it) }
        if (row.kind == GenerationKind.FLIGHT_PLAN && sortie == null) {
            return failGeneration(generations, generationId, "Sortie ${row.sortieId} no longer exists", client = null, sessionId = null)
        }

        return when (val connection = container.daemonConnectionCheck().check()) {
            // The daemon answered, so whatever the row carried about Mission Control is out of date.
            is ConnectionOutcome.Connected -> {
                generations.setCoachOfflineMark(generationId, null)
                runGeneration(container, generations, row, mission, sortie)
            }

            // A major this client doesn't speak answers the same way every time, and the check used its
            // own socket, so there is no session here to give up.
            is ConnectionOutcome.ProtocolMismatch -> failGeneration(
                generations,
                generationId,
                mismatchMessage(connection),
                client = null,
                sessionId = null,
            )

            // Nothing is listening, which is the one thing the row can say while it waits for a coach — until
            // the 8th attempt says it for good.
            is ConnectionOutcome.Unreachable -> coachUnreachable(generations, generationId)

            // A host or a token that isn't set, or a token the daemon won't take, is a setting of Liftoff's
            // own, not Mission Control being down, so the row carries no claim about the coach.
            else -> Result.retry()
        }
    }

    /** Connects, runs the turn and any repairs it leads to, and hangs up whatever the last one ended as. */
    private suspend fun runGeneration(
        container: AppContainer,
        generations: GenerationDao,
        row: Generation,
        mission: Mission,
        sortie: Sortie?,
    ): Result {
        val client = container.daemonClient()
        // A socket that never opened is a daemon that isn't there, not a turn that dropped mid-way, so it is
        // answered here rather than falling into the catch below with the turns.
        try {
            client.connect()
        } catch (_: DaemonException) {
            client.close()
            return coachUnreachable(generations, row.id)
        }
        // The id this turn actually ran on, held here so the catch can give it up even when the turn threw
        // before it could report an outcome.
        var reportedSessionId: String? = null
        return try {
            val settings = container.settingsStore.settings.first()
            // Held here because a repair turn runs on the same session, with the same labels and description.
            val options = CoachSessionOptions(
                workspace = settings.coachWorkspacePath,
                labels = labelsFor(row.kind),
                description = descriptionOf(row, sortie),
            )
            val outcome = savingProgressTo(generations, row.id) { onSessionId, onCursor ->
                runCoachTurn(
                    client = client,
                    sessionId = row.nabuSessionId,
                    options = options,
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
            storeFinishedTurn(container, generations, client, row, mission, sortie, settings, options, outcome)
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
     * A coach nothing can reach. WorkManager's own attempt count decides how long the row waits: every attempt
     * before the 8th leaves the offline mark on it and comes back, the 8th names the coach on the row and stops.
     * The row's [Generation.attempt] counts this generation's repairs, not WorkManager's runs, so it stays out
     * of the decision.
     */
    private suspend fun coachUnreachable(generations: GenerationDao, generationId: Long): Result =
        if (runAttemptCount < GIVE_UP_AT_ATTEMPT) {
            generations.setCoachOfflineMark(generationId, Generation.COACH_OFFLINE)
            Result.retry()
        } else {
            failGeneration(generations, generationId, COACH_UNREACHABLE, client = null, sessionId = null)
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
     * anything else hands the session back to the daemon and leaves the row where the turn stopped, for the
     * next attempt to pick up.
     *
     * A reply that fails validation is handed back to the same session with the validator's own error
     * lines (§7.3 step 5), and the turn that comes back is judged exactly like this one. The cap is the
     * row's [Generation.attempt], so the repairs belong to the generation rather than to this run.
     *
     * A valid reply is written only while the row is still the generation its target is waiting for
     * (§7.3's regeneration path): the currency check, the store and the mark share one transaction, so a
     * cancel or a newer generation is either entirely before it or entirely after it, never in between.
     */
    private suspend fun storeFinishedTurn(
        container: AppContainer,
        generations: GenerationDao,
        client: DaemonClient,
        row: Generation,
        mission: Mission,
        sortie: Sortie?,
        settings: Settings,
        options: CoachSessionOptions,
        outcome: TurnOutcome,
    ): Result {
        val context = GenerationContext(
            kind = row.kind,
            pattern = mission.pattern,
            sortieType = sortie?.type,
            equipmentKeys = container.database.equipmentDao().observeActive().first().map { it.key },
            sortieLengthMinutes = settings.sortieLengthMinutes,
        )

        var turn = outcome
        while (true) {
            // A coach that asked for input is a generation that will not answer itself: retrying asks the same
            // question and gets the same question back.
            if (turn.end == TurnEnd.Blocked) {
                return failGeneration(generations, row.id, "coach needed input", client, turn.sessionId)
            }
            // A turn that stopped part-way is handed back to the daemon to carry on: the row keeps its session
            // id and cursor, and the next attempt reads from where this one stopped (invariant 6).
            if (turn.end != TurnEnd.Idle && turn.end != TurnEnd.Completed) {
                handSessionBack(client, turn.sessionId)
                return Result.retry()
            }

            // A coach that said nothing has answered, just badly, so it is judged like any other reply rather
            // than waited out again under the client_id the daemon already dedupes.
            val raw = turn.lastAssistantText.orEmpty()
            val valid = when (val reply = CoachReplyValidator.validate(raw, context)) {
                is CoachReplyResult.Valid -> reply
                is CoachReplyResult.Invalid -> {
                    // Read off the row, so a run that resumes part-way through a repair asks no more than the
                    // two this generation is allowed in total.
                    if (requireNotNull(generations.get(row.id)).attempt >= MAX_REPAIRS) {
                        return failGeneration(
                            generations,
                            row.id,
                            "invalid output: " + reply.errors.joinToString("; "),
                            client,
                            turn.sessionId,
                        )
                    }
                    generations.bumpAttempt(row.id)
                    // The attempt as the row holds it now: the daemon dedupes a prompt by client_id, so the id
                    // the refused turn went out under would have played no turn at all.
                    val attempt = requireNotNull(generations.get(row.id)).attempt
                    val refused = turn
                    turn = savingProgressTo(generations, row.id) { onSessionId, onCursor ->
                        runCoachTurn(
                            client = client,
                            sessionId = refused.sessionId,
                            options = options,
                            prompt = CoachPromptBuilder.repairPrompt(reply.errors),
                            clientId = "${row.id}-$attempt",
                            cursor = refused.cursor,
                            limit = TURN_LIMIT,
                            onSessionId = onSessionId,
                            onCursor = onCursor,
                        )
                    }
                    continue
                }
            }

            // The id storeOutline reports arrives while this transaction is still open, so it is held here and
            // carried out in the decision rather than acted on where it lands.
            var queuedPlanId: Long? = null
            val decision = container.database.withTransaction {
                if (!isCurrentGeneration(generations, row)) {
                    if (generations.get(row.id)?.status != GenerationStatus.CANCELLED) {
                        generations.markCancelled(row.id, clock.millis())
                    }
                    StoreDecision.NotCurrent
                } else {
                    val stored = when (row.kind) {
                        GenerationKind.OUTLINE -> container.missionManager.storeOutline(row.missionId, valid) { queuedPlanId = it }
                        GenerationKind.FLIGHT_PLAN -> container.missionManager.storePlan(requireNotNull(row.sortieId), valid)
                    }
                    if (stored is StoreOutcome.Stored) generations.markSucceeded(row.id, clock.millis())
                    StoreDecision.Wrote(stored, queuedPlanId)
                }
            }

            // A generation nobody is waiting for any more is over rather than failed: nothing was written,
            // and a retry would only ask the coach the same question again.
            if (decision is StoreDecision.NotCurrent) {
                giveUpSession(client, turn.sessionId)
                return Result.success()
            }

            val stored = (decision as StoreDecision.Wrote).outcome
            // The store's own reason is the end of it: a target that has moved on does not move back, so a
            // retry would be refused for the same reason.
            if (stored is StoreOutcome.Refused) {
                return failGeneration(generations, row.id, stored.reason, client, turn.sessionId)
            }

            // Only a generation that stored its answer gives up its session; every other end resumes it (invariant 6).
            // A stop the daemon refuses changes nothing: the outcome was already decided before it was attempted.
            giveUpSession(client, turn.sessionId)
            decision.queuedPlanId?.let { enqueueQueuedPlan(container, it) }
            return Result.success()
        }
    }

    /**
     * Whether this row is still the generation its target is waiting for: not cancelled, and the newest
     * non-cancelled generation for its sortie (Flight Plan) or for its Mission's outline. Read inside the
     * store's transaction, which is what makes it a decision about now rather than about the row as this
     * attempt found it.
     */
    private suspend fun isCurrentGeneration(generations: GenerationDao, row: Generation): Boolean {
        val now = generations.get(row.id) ?: return false
        if (now.status == GenerationStatus.CANCELLED) return false
        val newest = when (row.kind) {
            GenerationKind.OUTLINE -> generations.getNewestNonCancelledOutlineForMission(row.missionId)
            GenerationKind.FLIGHT_PLAN -> generations.getNewestNonCancelledForSortie(requireNotNull(row.sortieId))
        }
        return newest?.id == row.id
    }

    /** What the store transaction decided: either the store ran and this is what it did, or nothing ran. */
    private sealed interface StoreDecision {
        /** [queuedPlanId] is the Flight Plan id the store queued, read before this transaction committed it. */
        data class Wrote(val outcome: StoreOutcome, val queuedPlanId: Long?) : StoreDecision
        data object NotCurrent : StoreDecision
    }

    /**
     * Schedule the Flight Plan row this worker's own store queued, asked for only after that transaction has
     * committed — work for a row WorkManager cannot see yet is work for nothing. A process with no WorkManager
     * to ask is not a reason to fail a reply that is already stored, so anything but cancellation is swallowed:
     * the QUEUED row is the source of truth for what needs generating.
     */
    private fun enqueueQueuedPlan(container: AppContainer, generationId: Long) {
        try {
            container.generationEnqueuer().enqueue(generationId)
        } catch (cause: CancellationException) {
            throw cause
        } catch (_: Exception) {
        }
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
     *
     * A FAILED write the row refused because it already reads CANCELLED is a generation the user ended first:
     * the row keeps the record the cancel left on it, the session is still given up, and the work is done.
     */
    private suspend fun failGeneration(
        generations: GenerationDao,
        generationId: Long,
        message: String,
        client: DaemonClient?,
        sessionId: String?,
    ): Result {
        val written = generations.markFailed(generationId, message, clock.millis())
        if (client != null && sessionId != null) giveUpSession(client, sessionId)
        // A row the user cancelled has already decided its outcome: the cancel ended the generation, not this.
        if (written == 0 && generations.get(generationId)?.status == GenerationStatus.CANCELLED) {
            return Result.success()
        }
        return Result.failure()
    }

    /** The stop on its own: a session the daemon won't give up is the daemon's to clean up. */
    private suspend fun giveUpSession(client: DaemonClient, sessionId: String) {
        try {
            stopCoachSession(client, sessionId)
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (_: Exception) {
        }
    }

    /**
     * The resume on its own: a session the daemon won't hand back is the daemon's to sort out, and the next
     * attempt reads its real state anyway. Swallowed exactly as [giveUpSession] swallows a refused stop, so a
     * refusal here — permanent or not — can never reach the catch that fails the generation.
     */
    private suspend fun handSessionBack(client: DaemonClient, sessionId: String) {
        try {
            client.callOrThrow("rho.session.resume", buildJsonObject { put("session_id", sessionId) })
        } catch (refused: CancellationException) {
            throw refused
        } catch (_: Exception) {
        }
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

/** §7.5.3's two repairs, counted on the row so they belong to the generation and not to one run. */
private const val MAX_REPAIRS = 2

/** WorkManager counts attempts from 0, so 7 is the 8th attempt to reach for the coach. */
private const val GIVE_UP_AT_ATTEMPT = 7

/** What a generation that never got a coach says on the row. */
private const val COACH_UNREACHABLE = "coach unreachable"

/** DESIGN 10 compares the major only, so the row names majors: the client's 1.0 reads "1.x". */
private val liftoffProtocolMajor: String = majorOf(PROTOCOL_VERSION)?.let { "$it.x" } ?: PROTOCOL_VERSION

/**
 * The sentence Mission Control's Test connection shows for a major this client doesn't speak, so a
 * generation that died this way reads the same as the row that says why. A daemon that named no major
 * keeps its own refusal's words rather than being given a blank or invented one.
 */
private fun mismatchMessage(mismatch: ConnectionOutcome.ProtocolMismatch): String {
    val daemonMajor = majorOf(mismatch.daemonVersion) ?: return mismatch.message
    return "Liftoff speaks rho protocol $liftoffProtocolMajor, the daemon speaks $daemonMajor.x"
}

private fun majorOf(version: String): Int? = version.trim().substringBefore('.').toIntOrNull()
