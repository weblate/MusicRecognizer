package com.mrsep.musicrecognizer.feature.backup

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface AutoBackupScheduler {

    fun schedule(intervalDays: Int, treeUri: Uri)

    fun ensureScheduled(intervalDays: Int, treeUri: Uri)

    suspend fun overrideNextRun(intervalDays: Int, treeUri: Uri, nextRunAt: Instant)

    fun cancel()

    fun isAutoBackupRunning(): Flow<Boolean>
}
