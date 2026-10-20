package com.lagradost.quicknovel

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TTSPlaybackControlTest {
    @Test
    fun rapidTapsAreCountedIndividuallyEvenWhenWakeupsAreConflated() {
        val control = TTSPlaybackControl()
        repeat(100) { control.skip(1) }
        repeat(7) { control.skip(-1) }
        assertEquals(93, control.takeSkip())
        assertEquals(0, control.takeSkip())
        assertFalse(control.hasPendingSkip)
    }

    @Test
    fun pausedWorkerWakesOnNavigationWithoutAPollingInterval() = runBlocking {
        val control = TTSPlaybackControl()
        val worker =
            async(start = CoroutineStart.UNDISPATCHED) {
                control.awaitWhile { !control.hasPendingSkip }
                control.takeSkip()
            }
        assertFalse(worker.isCompleted)
        control.skip(1)
        assertEquals(1, withTimeout(5_000) { worker.await() })
    }

    @Test
    fun inputBetweenCheckingTheConditionAndSuspendingCannotBeLost() = runBlocking {
        val control = TTSPlaybackControl()
        var firstCheck = true
        withTimeout(5_000) {
            control.awaitWhile {
                if (firstCheck) {
                    firstCheck = false
                    control.skip(1) // Arrives after the caller decides to wait, before receive.
                    true
                } else !control.hasPendingSkip
            }
        }
        assertEquals(1, control.takeSkip())
    }

    @Test
    fun statusAndCompletionSignalsRecheckStateWithoutInventingNavigation() = runBlocking {
        val control = TTSPlaybackControl()
        var waiting = true
        val worker = async(start = CoroutineStart.UNDISPATCHED) { control.awaitWhile { waiting } }
        control.signal() // A stale utterance callback must not end a paused wait.
        yield()
        assertFalse(worker.isCompleted)
        waiting = false // Resume, stop, or the utterance being awaited completes.
        control.signal()
        withTimeout(5_000) { worker.await() }
        assertEquals(0, control.takeSkip())

        control.skip(3)
        control.reset() // Stopping must discard queued taps before the next session.
        assertEquals(0, control.takeSkip())
        assertFalse(control.hasPendingSkip)
    }
}
