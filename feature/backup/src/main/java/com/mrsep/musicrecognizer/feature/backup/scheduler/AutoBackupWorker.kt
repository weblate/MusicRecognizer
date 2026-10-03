package com.mrsep.musicrecognizer.feature.backup.scheduler

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupError
import com.mrsep.musicrecognizer.core.domain.preferences.AutoBackupResult
import com.mrsep.musicrecognizer.core.domain.preferences.PreferencesRepository
import com.mrsep.musicrecognizer.core.domain.recognition.RecognitionInteractor
import com.mrsep.musicrecognizer.core.domain.recognition.model.RecognitionStatus
import com.mrsep.musicrecognizer.feature.backup.AppBackupManager
import com.mrsep.musicrecognizer.feature.backup.AutoBackupScheduler
import com.mrsep.musicrecognizer.feature.backup.BackupEntry
import com.mrsep.musicrecognizer.feature.backup.BackupNotificationHelper
import com.mrsep.musicrecognizer.feature.backup.BackupResult
import com.mrsep.musicrecognizer.feature.backup.data.BackupTreeDocuments
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

@HiltWorker
internal class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val preferencesRepository: PreferencesRepository,
    private val scheduler: AutoBackupScheduler,
    private val appBackupManager: AppBackupManager,
    private val treeDocuments: BackupTreeDocuments,
    private val notificationHelper: BackupNotificationHelper,
    private val recognitionInteractor: RecognitionInteractor,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "$TAG started with attempt #$runAttemptCount")
        val prefs = preferencesRepository.userPreferencesFlow.first()
        val autoBackup = prefs.autoBackup
        if (!autoBackup.enabled) {
            scheduler.cancel()
            return Result.success()
        }
        if (autoBackup.treeUri.isEmpty()) {
            return failCritical(AutoBackupError.FileNotFound)
        }
        val treeUri = autoBackup.treeUri.toUri()
        if (!treeDocuments.hasWritePermission(treeUri)) {
            return failCritical(AutoBackupError.AccessDenied)
        }
        if (recognitionInteractor.status.value is RecognitionStatus.Recognizing) {
            return overrideNextRunForRecognition(autoBackup.intervalDays, treeUri)
        }
        val destination = try {
            treeDocuments.createBackupDocument(treeUri)
        } catch (e: SecurityException) {
            Log.e(TAG, "No permission to create backup document", e)
            return failCritical(AutoBackupError.AccessDenied, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup document", e)
            return retryThenFail(AutoBackupError.FileNotFound, e.message)
        }
        if (destination == null) {
            return retryThenFail(AutoBackupError.FileNotFound)
        }
        val backupResult = appBackupManager.backup(
            destination = destination,
            entries = setOf(BackupEntry.Data, BackupEntry.Preferences)
        )
        return when (backupResult) {
            BackupResult.Success -> {
                treeDocuments.enforceRetention(
                    treeUri = treeUri,
                    keepCount = autoBackup.keepCount,
                    justCreatedUri = destination,
                )
                preferencesRepository.setAutoBackupLastResult(
                    AutoBackupResult.Success(Instant.now())
                )
                notificationHelper.cancelFailure()
                Result.success()
            }
            BackupResult.FileNotFound -> {
                retryThenFail(AutoBackupError.FileNotFound)
            }
            is BackupResult.UnhandledError -> {
                retryThenFail(AutoBackupError.Unhandled, backupResult.message)
            }
        }
    }

    private suspend fun overrideNextRunForRecognition(intervalDays: Int, treeUri: Uri): Result {
        Log.d(TAG, "Recognition in progress, deferring auto backup worker")
        return try {
            scheduler.overrideNextRun(
                intervalDays = intervalDays,
                treeUri = treeUri,
                nextRunAt = Instant.now().plusMillis(RECOGNITION_DEFER.inWholeMilliseconds),
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to defer auto backup worker", e)
            Result.retry()
        }
    }

    private suspend fun retryThenFail(
        reason: AutoBackupError,
        message: String? = null,
    ): Result {
        return if (runAttemptCount >= MAX_ATTEMPTS) {
            failScheduled(reason, message)
        } else {
            Result.retry()
        }
    }

    private suspend fun failScheduled(
        reason: AutoBackupError,
        message: String? = null,
    ): Result {
        val failure = AutoBackupResult.Failure(Instant.now(), reason, message)
        preferencesRepository.setAutoBackupLastResult(failure)
        val consecutive = preferencesRepository.userPreferencesFlow.first()
            .autoBackupConsecutiveFailures
        val disable = consecutive >= FAILURES_BEFORE_DISABLE
        if (disable) {
            preferencesRepository.setAutoBackupEnabled(false)
            scheduler.cancel()
        }
        notificationHelper.notifyFailure(failure, featureDisabled = disable)
        return Result.failure()
    }

    private suspend fun failCritical(
        reason: AutoBackupError,
        message: String? = null,
    ): Result {
        val failure = AutoBackupResult.Failure(Instant.now(), reason, message)
        preferencesRepository.setAutoBackupLastResult(failure)
        preferencesRepository.setAutoBackupEnabled(false)
        scheduler.cancel()
        notificationHelper.notifyFailure(failure, featureDisabled = true)
        return Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "auto_backup"
        const val TAG = "AutoBackupWorker"
        private const val MAX_ATTEMPTS = 3
        private const val FAILURES_BEFORE_DISABLE = 3
        private val RECOGNITION_DEFER = 30.minutes
    }
}
