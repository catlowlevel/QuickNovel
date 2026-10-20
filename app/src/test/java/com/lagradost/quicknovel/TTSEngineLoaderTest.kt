package com.lagradost.quicknovel

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSEngineLoaderTest {
    private class Engine(val ready: (Boolean) -> Unit) {
        var configurations = 0
        var releases = 0
    }

    private class Fixture {
        val engines = mutableListOf<Engine>()
        var supported = true
        val loader =
            TTSEngineLoader(
                create = { ready -> Engine(ready).also(engines::add) },
                configure = { engine ->
                    engine.configurations++
                    supported
                },
                dispose = { engine -> engine.releases++ },
            )
    }

    @Test
    fun warmUpAndTheFirstReadShareOneInitializationAndWakeDirectlyFromItsCallback() = runBlocking {
        val f = Fixture()
        val warmUp = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        assertEquals(1, f.engines.size)
        assertFalse(warmUp.isCompleted)
        assertFalse(read.isCompleted)
        assertFalse(f.loader.initialized)
        val engine = f.engines.single()
        engine.ready(true)
        withTimeout(5_000) {
            assertSame(engine, warmUp.await())
            assertSame(engine, read.await())
        }
        assertSame(engine, f.loader.engine)
        assertEquals(1, engine.configurations)
        assertEquals(0, engine.releases)
        f.loader.release()
    }

    @Test
    fun synchronousInitializationCannotLoseItsReadyCallback() = runBlocking {
        val engine = Any()
        val loader =
            TTSEngineLoader(
                create = { ready ->
                    ready(true)
                    engine
                },
                configure = { true },
                dispose = {},
            )
        assertSame(engine, loader.acquire())
        assertSame(engine, loader.acquire())
        loader.release()
    }

    @Test
    fun leavingDuringInitializationReleasesThePendingEngineAndIgnoresLateSuccess() = runBlocking {
        val f = Fixture()
        val warmUp = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        val engine = f.engines.single()
        f.loader.release()
        assertNull(withTimeout(5_000) { warmUp.await() })
        engine.ready(true)
        assertFalse(f.loader.initialized)
        assertNull(f.loader.engine)
        assertEquals(0, engine.configurations)
        assertEquals(1, engine.releases)
        assertNull(f.loader.acquire())
        assertEquals(1, f.engines.size)
    }

    @Test
    fun cancellingInitializationDisposesItAndAllowsAFreshRetry() = runBlocking {
        val f = Fixture()
        val warmUp = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        val cancelled = f.engines.single()
        warmUp.cancelAndJoin()
        assertEquals(1, cancelled.releases)
        cancelled.ready(true)
        val retry = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        val current = f.engines.last()
        assertEquals(2, f.engines.size)
        current.ready(true)
        assertSame(current, withTimeout(5_000) { retry.await() })
        assertEquals(0, cancelled.configurations)
        f.loader.release()
    }

    @Test
    fun initializationFailureDisposesTheEngineWithoutPoisoningLaterReads() = runBlocking {
        val f = Fixture()
        val attempt = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        val failed = f.engines.single()
        failed.ready(false)
        assertNull(withTimeout(5_000) { attempt.await() })
        assertEquals(1, failed.releases)
        assertEquals(0, failed.configurations)
        val retry = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        f.engines.last().ready(true)
        assertSame(f.engines.last(), withTimeout(5_000) { retry.await() })
        f.loader.release()
    }

    @Test
    fun unsupportedConfigurationIsReleasedAndNeverPublishedAsReady() = runBlocking {
        val f = Fixture()
        f.supported = false
        val attempt = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        f.engines.single().ready(true)
        assertNull(withTimeout(5_000) { attempt.await() })
        assertFalse(f.loader.initialized)
        assertEquals(1, f.engines.single().releases)
        f.loader.release()
    }

    @Test
    fun cancellingAWaitingReadDoesNotCancelTheSharedWarmUp() = runBlocking {
        val f = Fixture()
        val warmUp = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        val read = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        read.cancelAndJoin()
        val engine = f.engines.single()
        assertEquals(0, engine.releases)
        engine.ready(true)
        assertSame(engine, withTimeout(5_000) { warmUp.await() })
        assertSame(engine, f.loader.acquire())
        assertEquals(1, f.engines.size)
        f.loader.release()
    }

    @Test
    fun releaseClosesAReadyEngineOnceAndPreventsReopening() = runBlocking {
        val f = Fixture()
        val warmUp = async(start = CoroutineStart.UNDISPATCHED) { f.loader.acquire() }
        val engine = f.engines.single()
        engine.ready(true)
        warmUp.await()
        assertTrue(f.loader.initialized)
        f.loader.release()
        f.loader.release()
        assertEquals(1, engine.releases)
        assertNull(f.loader.acquire())
        assertFalse(f.loader.initialized)
    }
}
