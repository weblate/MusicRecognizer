package com.mrsep.musicrecognizer.feature.recognition.scheduler

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Wakes the queue when a per-item backoff elapses. Same constraints as the queue worker. */
@HiltWorker
internal class RecognitionTimerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val scheduler: EnqueuedRecognitionSchedulerImpl,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "$TAG fired")
        scheduler.onTimerFired()
        return Result.success()
    }

    companion object {
        const val TAG = "RecognitionTimerWorker"
        const val UNIQUE_NAME = "recognition_timer"

        fun buildRequest(initialDelayMs: Long): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<RecognitionTimerWorker>()
                .addTag(TAG)
                .setConstraints(RecognitionQueueWorker.buildConstraints())
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
