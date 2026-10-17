package com.mrsep.musicrecognizer.feature.recognition.scheduler

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class EnqueuedRecognitionRunRegistry @Inject constructor() {

    private val mutex = Mutex()
    private val sessions = mutableMapOf<Int, Job>()

    suspend fun cancelIfProcessing(ids: Collection<Int>) {
        mutex.withLock {
            ids.forEach { id -> sessions.remove(id)?.cancel() }
        }
    }

    suspend fun cancelAllProcessing() {
        mutex.withLock {
            sessions.values.forEach(Job::cancel)
            sessions.clear()
        }
    }

    /**
     * Runs [block] on a child job so cancelling this item does not cancel the worker.
     * Worker-level cancellation (stop, unique work replace) still propagates.
     */
    suspend fun <T> runSession(
        id: Int,
        onItemCancelled: T,
        block: suspend () -> T,
    ): T = supervisorScope {
        cancelIfProcessing(listOf(id))
        val deferred = async(start = CoroutineStart.LAZY) { block() }
        mutex.withLock {
            sessions[id] = deferred
            deferred.start()
        }
        try {
            deferred.await()
        } catch (e: CancellationException) {
            if (!isActive) throw e
            onItemCancelled
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    sessions.remove(id)
                }
            }
        }
    }
}
