package com.mrsep.musicrecognizer.feature.recognition.scheduler

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionRepository
import com.mrsep.musicrecognizer.core.domain.recognition.model.ClaimedEnqueuedRecognition
import com.mrsep.musicrecognizer.core.domain.recognition.model.RemoteRecognitionResult
import com.mrsep.musicrecognizer.feature.recognition.scheduler.RecognitionQueueWorker.Companion.MAX_IN_WORKER_WAIT
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One [doWork] runs every defer recognition that is due, one by one.
 * The pause between runs, and a retry wait of at most [MAX_IN_WORKER_WAIT], stay in this work.
 * A longer retry wait is [RecognitionTimerWorker].
 *
 * Don't return [Result.failure]. That fails this job and every APPEND job added after it.
 * Don't return [Result.retry]. This job would stay unfinished, so APPEND job only waits behind it.
 */
@HiltWorker
internal class RecognitionQueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val enqueuedRecognitionRepository: EnqueuedRecognitionRepository,
    private val runEnqueuedRecognition: RunEnqueuedRecognition,
    private val runRegistry: EnqueuedRecognitionRunRegistry,
    private val scheduler: EnqueuedRecognitionSchedulerImpl,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = doWorkMutex.withLock {
        Log.d(TAG, "$TAG started")
        var claimed: ClaimedEnqueuedRecognition? = null
        try {
            enqueuedRecognitionRepository.resetStaleRunningBeforeClaim()
            while (true) {
                // false: nothing to run, or the next retry wait is longer than MAX_IN_WORKER_WAIT
                if (!awaitReadyToClaim()) break
                withContext(NonCancellable) {
                    claimed = enqueuedRecognitionRepository.claimNext(now = Instant.now())
                }
                val claimedNow = claimed ?: continue
                runRegistry.runSession(
                    id = claimedNow.id,
                    onItemCancelled = Unit,
                ) {
                    runEnqueuedRecognition(claimedNow)
                }
                claimed = null
                markRunFinished()
            }
            scheduler.scheduleIfNeeded()
            Result.success()
        } catch (e: CancellationException) {
            if (claimed != null) markRunFinished()
            try {
                returnToQueuedAfterStop(claimed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to return the claim to QUEUED after worker stop", e)
            }
            // Worker stop, the same request is rescheduled
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Queue worker invocation failed", e)
            if (claimed != null) markRunFinished()
            try {
                finishAfterException(claimed, e)
                scheduler.scheduleIfNeeded()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to finish the claim after exception", e)
            }
            Result.success()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitReadyToClaim(): Boolean {
        return enqueuedRecognitionRepository.queuedNextAttemptFlow()
            .distinctUntilChanged()
            .transformLatest { nextAttemptAt ->
                val untilDue = nextAttemptAt?.untilDue()
                if (untilDue == null || untilDue > MAX_IN_WORKER_WAIT) {
                    emit(false)
                } else {
                    delay(maxOf(untilDue, interRunDelayRemaining()))
                    emit(true)
                }
            }
            .first()
    }

    private fun Instant.untilDue(): Duration {
        return (toEpochMilli() - Instant.now().toEpochMilli())
            .milliseconds
            .coerceAtLeast(Duration.ZERO)
    }

    private suspend fun returnToQueuedAfterStop(claimed: ClaimedEnqueuedRecognition?) {
        val claim = claimed ?: return
        withContext(NonCancellable) {
            enqueuedRecognitionRepository.returnToQueued(claim.id)
        }
    }

    private suspend fun finishAfterException(
        claimed: ClaimedEnqueuedRecognition?,
        exception: Exception,
    ) {
        val claim = claimed ?: return
        withContext(NonCancellable) {
            enqueuedRecognitionRepository.finishRun(
                recognitionId = claim.id,
                result = RemoteRecognitionResult.Error.UnhandledError(
                    message = exception.message ?: "", cause = exception
                ),
                resultDate = Instant.now(),
            )
        }
    }

    companion object {
        const val TAG = "RecognitionQueueWorker"
        const val UNIQUE_NAME = "recognition_queue"

        // WorkManager cancels a stopped doWork and doesn't join it,
        // so the next instance can start while previous is still writing
        private val doWorkMutex = Mutex()

        // Waiting wastes background-time quota, but saves the session-count quota
        private val MAX_IN_WORKER_WAIT = 90.seconds

        // Min delay between recognitions to avoid provider rate limits
        private val MIN_DELAY_BETWEEN_RUNS = 3.seconds

        @Volatile
        private var lastRunFinishedNanos: Long? = null

        private fun markRunFinished() {
            lastRunFinishedNanos = System.nanoTime()
        }

        private fun interRunDelayRemaining(): Duration {
            val finishedAt = lastRunFinishedNanos ?: return Duration.ZERO
            val idleFor = (System.nanoTime() - finishedAt).nanoseconds
            return (MIN_DELAY_BETWEEN_RUNS - idleFor).coerceAtLeast(Duration.ZERO)
        }

        fun buildConstraints(): Constraints {
            return Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        }

        fun buildRequest(): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<RecognitionQueueWorker>()
                .addTag(TAG)
                .setConstraints(buildConstraints())
                .build()
        }
    }
}
