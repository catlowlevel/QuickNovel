package com.lagradost.quicknovel

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Counts every navigation tap; conflated signals only wake the single playback worker. */
class TTSPlaybackControl {
    private val pendingSkip = AtomicInteger()
    private val changes = Channel<Unit>(Channel.CONFLATED)

    val hasPendingSkip: Boolean get() = pendingSkip.get() != 0

    fun skip(offset: Int) {
        pendingSkip.addAndGet(offset)
        signal()
    }

    fun takeSkip(): Int = pendingSkip.getAndSet(0)

    fun reset() {
        pendingSkip.set(0)
        signal()
    }

    fun signal() {
        changes.trySend(Unit)
    }

    suspend fun awaitWhile(waiting: () -> Boolean) {
        // A signal arriving between the predicate and receive remains buffered.
        while (waiting()) changes.receive()
    }
}
