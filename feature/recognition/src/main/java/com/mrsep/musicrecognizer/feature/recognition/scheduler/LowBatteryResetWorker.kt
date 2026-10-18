package com.mrsep.musicrecognizer.feature.recognition.scheduler

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
internal class LowBatteryResetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val scheduler: EnqueuedRecognitionSchedulerImpl,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "$TAG fired")
        scheduler.onLowBatteryRecovered()
        return Result.success()
    }

    companion object {
        const val TAG = "LowBatteryResetWorker"
        const val UNIQUE_NAME = "low_battery_reset"

        fun buildRequest(): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            return OneTimeWorkRequestBuilder<LowBatteryResetWorker>()
                .addTag(TAG)
                .setConstraints(constraints)
                .build()
        }
    }
}
