package com.mrsep.musicrecognizer.core.data

import com.mrsep.musicrecognizer.core.domain.maintenance.DataMaintenanceState
import com.mrsep.musicrecognizer.core.domain.maintenance.DataMaintenance
import com.mrsep.musicrecognizer.core.domain.maintenance.DataMaintenanceOperation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writer-preferring non-reentrant lock that separates user-data mutations from backup/restore.
 *
 * - [withShared]: repository mutations. Several may run at once.
 * - [withExclusive]: backup/restore. Waits for in-flight shared work, then blocks new shared work.
 *
 * Reads (queries and Flows) do not take this lock.
 * Exclusive is not reentrant and must not call [withShared] (deadlock).
 *
 * Writer preference follows the two-mutex scheme discussed in
 * [kotlinx.coroutines#94](https://github.com/Kotlin/kotlinx.coroutines/issues/94):
 * exclusive holds [admitShared] for its whole section so new shared callers queue behind it
 * instead of starving backup.
 */
@Singleton
class PersistentStoreLock @Inject constructor() : DataMaintenance {

    // Exclusive holds this for its entire section, which blocks new shared acquires
    private val admitShared = Mutex()

    // Locked iff at least one shared section is running
    private val noSharedHolders = Mutex()

    private val sharedCountGuard = Mutex()
    private var sharedCount = 0
    private val exclusiveWaiters = AtomicInteger(0)

    private val _state = MutableStateFlow<DataMaintenanceState>(DataMaintenanceState.Idle)
    override val state: StateFlow<DataMaintenanceState> = _state.asStateFlow()

    suspend fun <T> withShared(block: suspend () -> T): T {
        acquireShared()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { releaseShared() }
        }
    }

    suspend fun <T> withExclusive(reason: DataMaintenanceOperation, block: suspend () -> T): T {
        exclusiveWaiters.incrementAndGet()
        _state.value = DataMaintenanceState.Exclusive(reason)
        try {
            admitShared.lock()
            try {
                noSharedHolders.lock()
                try {
                    return block()
                } finally {
                    noSharedHolders.unlock()
                }
            } finally {
                admitShared.unlock()
            }
        } finally {
            if (exclusiveWaiters.decrementAndGet() == 0) {
                _state.value = DataMaintenanceState.Idle
            }
        }
    }

    private suspend fun acquireShared() {
        admitShared.withLock {
            sharedCountGuard.withLock {
                if (sharedCount++ == 0) {
                    check(noSharedHolders.tryLock()) {
                        "noSharedHolders must be free when the first shared acquirer enters"
                    }
                }
            }
        }
    }

    private suspend fun releaseShared() {
        sharedCountGuard.withLock {
            check(sharedCount > 0) { "releaseShared without matching acquire" }
            if (--sharedCount == 0) {
                noSharedHolders.unlock()
            }
        }
    }
}
