package com.mrsep.musicrecognizer.core.data

import com.mrsep.musicrecognizer.core.domain.maintenance.DataMaintenanceState
import com.mrsep.musicrecognizer.core.domain.maintenance.DataMaintenanceOperation
import com.mrsep.musicrecognizer.core.domain.maintenance.isExclusive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PersistentStoreLockTest {

    @Test
    fun sharedSectionsMayOverlap() = runBlocking(Dispatchers.Default) {
        val lock = PersistentStoreLock()
        val entered = AtomicInteger(0)
        val proceed = CompletableDeferred<Unit>()

        withTimeout(5.seconds) {
            val jobs = List(8) {
                launch {
                    lock.withShared {
                        if (entered.incrementAndGet() == 8) proceed.complete(Unit)
                        proceed.await()
                    }
                }
            }
            jobs.joinAll()
        }
        assertTrue(entered.get() == 8)
    }

    @Test
    fun exclusiveWaitsForInFlightShared() = runBlocking(Dispatchers.Default) {
        val lock = PersistentStoreLock()
        val sharedStarted = CompletableDeferred<Unit>()
        val releaseShared = CompletableDeferred<Unit>()
        val exclusiveRan = AtomicBoolean(false)

        val shared = launch {
            lock.withShared {
                sharedStarted.complete(Unit)
                releaseShared.await()
            }
        }
        sharedStarted.await()

        val exclusive = launch {
            lock.withExclusive(DataMaintenanceOperation.Backup) {
                exclusiveRan.set(true)
            }
        }
        delay(50.milliseconds)
        assertFalse(exclusiveRan.get())
        assertTrue(lock.isExclusive)
        assertEquals(DataMaintenanceOperation.Backup, (lock.state.value as DataMaintenanceState.Exclusive).operation)

        releaseShared.complete(Unit)
        exclusive.join()
        shared.join()
        assertTrue(exclusiveRan.get())
        assertFalse(lock.isExclusive)
        assertEquals(DataMaintenanceState.Idle, lock.state.value)
    }

    @Test
    fun exclusiveBlocksNewShared() = runBlocking(Dispatchers.Default) {
        val lock = PersistentStoreLock()
        val exclusiveStarted = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()
        val sharedEntered = AtomicBoolean(false)

        val exclusive = launch {
            lock.withExclusive(DataMaintenanceOperation.Restore) {
                exclusiveStarted.complete(Unit)
                releaseExclusive.await()
            }
        }
        exclusiveStarted.await()
        assertEquals(DataMaintenanceOperation.Restore, (lock.state.value as DataMaintenanceState.Exclusive).operation)

        val shared = launch {
            lock.withShared {
                sharedEntered.set(true)
            }
        }
        delay(50.milliseconds)
        assertFalse(sharedEntered.get())

        releaseExclusive.complete(Unit)
        exclusive.join()
        shared.join()
        assertTrue(sharedEntered.get())
    }

    @Test
    fun waitingExclusivePreventsNewShared() = runBlocking(Dispatchers.Default) {
        val lock = PersistentStoreLock()
        val firstSharedStarted = CompletableDeferred<Unit>()
        val releaseFirstShared = CompletableDeferred<Unit>()
        val exclusiveEntered = AtomicBoolean(false)
        val secondSharedEntered = AtomicBoolean(false)

        val firstShared = launch {
            lock.withShared {
                firstSharedStarted.complete(Unit)
                releaseFirstShared.await()
            }
        }
        firstSharedStarted.await()

        val exclusive = launch {
            lock.withExclusive(DataMaintenanceOperation.Backup) {
                exclusiveEntered.set(true)
            }
        }
        delay(30.milliseconds)

        val secondShared = launch {
            lock.withShared {
                secondSharedEntered.set(true)
            }
        }
        delay(50.milliseconds)
        assertFalse(exclusiveEntered.get())
        assertFalse(secondSharedEntered.get())

        releaseFirstShared.complete(Unit)
        exclusive.join()
        secondShared.join()
        firstShared.join()
        assertTrue(exclusiveEntered.get())
        assertTrue(secondSharedEntered.get())
    }

    @Test
    fun cancelledWaitingSharedDoesNotLeak() = runBlocking(Dispatchers.Default) {
        val lock = PersistentStoreLock()
        val exclusiveStarted = CompletableDeferred<Unit>()
        val releaseExclusive = CompletableDeferred<Unit>()

        val exclusive = launch {
            lock.withExclusive(DataMaintenanceOperation.Backup) {
                exclusiveStarted.complete(Unit)
                releaseExclusive.await()
            }
        }
        exclusiveStarted.await()

        val waitingShared = launch {
            lock.withShared { fail("cancelled shared must not run") }
        }
        delay(20.milliseconds)
        waitingShared.cancelAndJoin()

        releaseExclusive.complete(Unit)
        exclusive.join()

        withTimeout(1.seconds) {
            lock.withShared { yield() }
            lock.withExclusive(DataMaintenanceOperation.Restore) { yield() }
        }
        assertFalse(lock.isExclusive)
    }

    @Test
    fun exceptionReleasesExclusive() = runBlocking {
        val lock = PersistentStoreLock()
        try {
            lock.withExclusive(DataMaintenanceOperation.Restore) { error("") }
        } catch (_: IllegalStateException) {
        }
        assertFalse(lock.isExclusive)
        assertEquals(DataMaintenanceState.Idle, lock.state.value)
        lock.withShared { yield() }
    }

    @Test
    fun cancelledExclusiveWhileWaitingReleasesAdmit() = runBlocking(Dispatchers.Default) {
        val lock = PersistentStoreLock()
        val sharedStarted = CompletableDeferred<Unit>()
        val releaseShared = CompletableDeferred<Unit>()

        val shared = launch {
            lock.withShared {
                sharedStarted.complete(Unit)
                releaseShared.await()
            }
        }
        sharedStarted.await()

        val exclusive = launch {
            lock.withExclusive(DataMaintenanceOperation.Backup) { fail("cancelled exclusive must not run") }
        }
        delay(30.milliseconds)
        exclusive.cancelAndJoin()
        assertFalse(lock.isExclusive)

        releaseShared.complete(Unit)
        shared.join()
        lock.withShared { yield() }
    }

    @Test
    fun nestedSharedIsAllowed() = runBlocking {
        val lock = PersistentStoreLock()
        lock.withShared {
            lock.withShared { yield() }
        }
    }

    @Test
    fun overlappingExclusiveKeepsStateUntilLastReleases() = runBlocking(Dispatchers.Default) {
        val lock = PersistentStoreLock()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        val first = launch {
            lock.withExclusive(DataMaintenanceOperation.Backup) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        val second = launch {
            lock.withExclusive(DataMaintenanceOperation.Restore) {
                secondStarted.complete(Unit)
            }
        }
        delay(30.milliseconds)
        assertTrue(lock.state.value is DataMaintenanceState.Exclusive)

        releaseFirst.complete(Unit)
        first.join()
        second.join()
        secondStarted.await()
        assertEquals(DataMaintenanceState.Idle, lock.state.value)
    }
}
