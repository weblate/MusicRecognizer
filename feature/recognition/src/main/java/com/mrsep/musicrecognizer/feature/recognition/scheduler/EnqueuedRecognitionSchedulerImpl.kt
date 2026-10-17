package com.mrsep.musicrecognizer.feature.recognition.scheduler

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.mrsep.musicrecognizer.core.common.di.ApplicationScope
import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionRepository
import com.mrsep.musicrecognizer.core.domain.recognition.EnqueuedRecognitionScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class EnqueuedRecognitionSchedulerImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: EnqueuedRecognitionRepository,
    private val runRegistry: EnqueuedRecognitionRunRegistry,
    @ApplicationScope private val appScope: CoroutineScope,
) : EnqueuedRecognitionScheduler {

    private val workManager get() = WorkManager.getInstance(appContext)

    private val persistentCoroutineContext = appScope.coroutineContext

    private val wakeupMutex = Mutex()

    override suspend fun enqueue(recognitionIds: List<Int>) {
        if (recognitionIds.isEmpty()) return
        withContext(persistentCoroutineContext) {
            repository.markQueued(recognitionIds, Instant.now())
            scheduleIfNeeded()
        }
    }

    override suspend fun cancel(recognitionIds: List<Int>) {
        if (recognitionIds.isEmpty()) return
        withContext(persistentCoroutineContext) {
            repository.cancelWork(recognitionIds)
            runRegistry.cancelIfProcessing(recognitionIds)
            // Dropped rows may have been the earliest backoff, update a stale timer
            scheduleIfNeeded()
        }
    }

    override suspend fun cancelAll() {
        withContext(persistentCoroutineContext) {
            repository.cancelAllWork()
            runRegistry.cancelAllProcessing()
            wakeupMutex.withLock {
                workManager.cancelUniqueWork(RecognitionQueueWorker.UNIQUE_NAME)
                workManager.cancelUniqueWork(RecognitionTimerWorker.UNIQUE_NAME)
            }
        }
    }

    override suspend fun scheduleIfNeededOnAppStart() {
        repository.resetStaleRunningOnAppStart()
        scheduleIfNeeded()
    }

    /**
     * Schedules the queue worker with no delay, or the backoff timer.
     * Backoff is a separate unique work.
     */
    suspend fun scheduleIfNeeded() {
        applyWakeup(fromTimer = false)
    }

    suspend fun onTimerFired() {
        applyWakeup(fromTimer = true)
    }

    private suspend fun applyWakeup(fromTimer: Boolean) {
        wakeupMutex.withLock {
            val nextAttemptAt = repository.queuedNextAttempt()
            val delayMs = nextAttemptAt?.let { it.toEpochMilli() - Instant.now().toEpochMilli() }
            when {
                delayMs == null -> if (!fromTimer) cancelTimer()
                delayMs > 0L -> scheduleTimer(delayMs)
                else -> {
                    scheduleQueueWorker()
                    if (!fromTimer) cancelTimer()
                }
            }
        }
    }

    private fun scheduleQueueWorker() {
        workManager.enqueueUniqueWork(
            RecognitionQueueWorker.UNIQUE_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            RecognitionQueueWorker.buildRequest(),
        )
    }

    private fun scheduleTimer(delayMs: Long) {
        workManager.enqueueUniqueWork(
            RecognitionTimerWorker.UNIQUE_NAME,
            ExistingWorkPolicy.REPLACE,
            RecognitionTimerWorker.buildRequest(delayMs),
        )
    }

    private fun cancelTimer() {
        workManager.cancelUniqueWork(RecognitionTimerWorker.UNIQUE_NAME)
    }
}
