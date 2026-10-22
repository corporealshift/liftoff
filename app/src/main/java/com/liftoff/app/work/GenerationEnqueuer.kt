package com.liftoff.app.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.time.Duration

/**
 * Schedules a [GenerationWorker] run for one generation, under DESIGN §7.3's rules: unique work
 * `gen-<generationId>` kept rather than replaced, network connected, exponential backoff from 1 minute.
 * The `Generation` row belongs to the caller; this creates none.
 *
 * The built request is returned because constraints and backoff are readable there, not on `WorkInfo`.
 */
class GenerationEnqueuer(private val workManager: WorkManager) {

    fun enqueue(generationId: Long): OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<GenerationWorker>()
            .setInputData(workDataOf(GenerationWorker.GENERATION_ID to generationId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
            .build()
        workManager.enqueueUniqueWork(uniqueName(generationId), ExistingWorkPolicy.KEEP, request)
        return request
    }

    private fun uniqueName(generationId: Long) = "gen-$generationId"
}
