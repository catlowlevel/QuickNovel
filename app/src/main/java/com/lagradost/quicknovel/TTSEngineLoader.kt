package com.lagradost.quicknovel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shares background warm-up with the first read; readiness is callback-driven, never polled. */
internal class TTSEngineLoader<T : Any>(
    private val create: (ready: (Boolean) -> Unit) -> T,
    private val configure: (T) -> Boolean,
    private val dispose: (T) -> Unit,
) {
    private val mutex = Mutex()
    @Volatile private var current: T? = null
    private var released = false
    private var pending: CompletableDeferred<Boolean>? = null

    val initialized: Boolean get() = current != null
    val engine: T? get() = current

    suspend fun acquire(): T? = mutex.withLock {
        val ready = CompletableDeferred<Boolean>()
        synchronized(this) {
            if (released) return@withLock null
            current?.let { return@withLock it }
            pending = ready
        }
        var engine: T? = null
        var retained = false
        try {
            // A synchronous callback is safe: the signal can complete before create returns.
            engine = create { ready.complete(it) }
            if (!ready.await()) return@withLock null
            synchronized(this) { if (released) return@withLock null }
            if (!configure(engine)) return@withLock null
            synchronized(this) {
                if (released) return@withLock null
                current = engine
                retained = true
            }
            engine
        } finally {
            synchronized(this) { if (pending === ready) pending = null }
            if (!retained) engine?.let(dispose)
        }
    }

    fun release() {
        val engine = synchronized(this) {
            released = true
            pending?.complete(false)
            current.also { current = null }
        }
        engine?.let(dispose)
    }
}
