package com.mrsep.musicrecognizer.feature.backup.scheduler

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupPreferences
import com.mrsep.musicrecognizer.feature.backup.AutoBackupScheduler
import com.mrsep.musicrecognizer.feature.backup.data.BackupTreeDocuments
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject

internal class AutoBackupSchedulerImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val treeDocuments: BackupTreeDocuments,
) : AutoBackupScheduler {

    private val workManager get() = WorkManager.getInstance(appContext)

    override fun schedule(intervalDays: Int, treeUri: Uri) {
        val request = buildRequest(intervalDays, treeUri)
        workManager.enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    override fun ensureScheduled(intervalDays: Int, treeUri: Uri) {
        val request = buildRequest(intervalDays, treeUri)
        workManager.enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    override suspend fun overrideNextRun(
        intervalDays: Int,
        treeUri: Uri,
        nextRunAt: Instant,
    ) {
        val request = buildRequest(
            intervalDays = intervalDays,
            treeUri = treeUri,
            nextScheduleTimeOverrideMillis = nextRunAt.toEpochMilli(),
        )
        val operation = workManager.enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        operation.await()
    }

    override fun cancel() {
        workManager.cancelUniqueWork(AutoBackupWorker.UNIQUE_NAME)
    }

    override fun isAutoBackupRunning(): Flow<Boolean> {
        return workManager.getWorkInfosForUniqueWorkFlow(AutoBackupWorker.UNIQUE_NAME)
            .map { listWorkInfo -> listWorkInfo.lastOrNull()?.state == WorkInfo.State.RUNNING }
            .conflate()
    }

    private fun buildRequest(
        intervalDays: Int,
        treeUri: Uri,
        nextScheduleTimeOverrideMillis: Long? = null,
    ): PeriodicWorkRequest {
        val days = intervalDays
            .takeIf { it in AutoBackupPreferences.INTERVAL_DAYS }
            ?.toLong()
            ?: AutoBackupPreferences.DEFAULT_INTERVAL_DAYS.toLong()
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .apply {
                if (treeDocuments.requiresNetwork(treeUri)) {
                    setRequiredNetworkType(NetworkType.CONNECTED)
                }
            }
            .build()
        return PeriodicWorkRequestBuilder<AutoBackupWorker>(days, TimeUnit.DAYS)
            .setConstraints(constraints)
            .addTag(AutoBackupWorker.TAG)
            .apply {
                if (nextScheduleTimeOverrideMillis != null) {
                    setNextScheduleTimeOverride(nextScheduleTimeOverrideMillis)
                }
            }
            .build()
    }
}
