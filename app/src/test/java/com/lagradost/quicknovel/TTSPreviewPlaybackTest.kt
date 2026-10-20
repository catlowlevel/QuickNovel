package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSPreviewPlaybackTest {
    private class Output(override val maxInputLength: Int = 20) : TTSPreviewOutput {
        lateinit var ready: (Boolean) -> Unit
        lateinit var finished: (Boolean) -> Unit
        val spoken = mutableListOf<String>()
        var closes = 0

        override fun prepare(ready: (Boolean) -> Unit) {
            this.ready = ready
        }

        override fun speak(text: String, finished: (Boolean) -> Unit) {
            spoken += text
            this.finished = finished
        }

        override fun close() {
            closes++
        }
    }

    private class Fixture(val limit: Int = 20) {
        val outputs = mutableListOf<Output>()
        var pauses = 0
        var failures = 0
        val states = mutableListOf<Boolean>()
        val player =
            TTSPreviewPlayback(
                createOutput = { Output(limit).also(outputs::add) },
                beforeRead = { pauses++ },
                changed = states::add,
                failed = { failures++ },
            )
    }

    @Test
    fun emptyPreviewDoesNotPauseNarrationOrCreateAnEngine() {
        val f = Fixture()
        f.player.read(" \n\t")
        assertEquals(0, f.pauses)
        assertTrue(f.outputs.isEmpty())
        assertFalse(f.player.isReading)
    }

    @Test
    fun readPausesNarrationWaitsForInitializationAndReleasesTheEngineOnCompletion() {
        val f = Fixture()
        f.player.read("Lee spoke.")
        val output = f.outputs.single()
        assertEquals(1, f.pauses)
        assertTrue(f.player.isReading)
        assertTrue(output.spoken.isEmpty())
        output.ready(true)
        assertEquals(listOf("Lee spoke."), output.spoken)
        output.finished(true)
        assertFalse(f.player.isReading)
        assertEquals(1, output.closes)
        assertEquals(0, f.failures)
    }

    @Test
    fun stoppingDuringInitializationCannotStartSpeechFromALateCallback() {
        val f = Fixture()
        f.player.read("Test")
        val output = f.outputs.single()
        f.player.stop()
        output.ready(true)
        assertTrue(output.spoken.isEmpty())
        assertFalse(f.player.isReading)
        assertEquals(1, output.closes)
        assertEquals(0, f.failures)
    }

    @Test
    fun anOldCompletionCannotStopOrReportAnErrorForANewerPreview() {
        val f = Fixture()
        f.player.read("Old")
        val old = f.outputs.last()
        old.ready(true)
        val stale = old.finished
        f.player.read("New")
        val current = f.outputs.last()
        current.ready(true)
        stale(false)
        assertTrue(f.player.isReading)
        assertEquals(0, f.failures)
        assertEquals(0, current.closes)
        current.finished(true)
        assertFalse(f.player.isReading)
    }

    @Test
    fun initializationAndSpeechErrorsRestoreTheButtonAndCloseTheEngine() {
        val f = Fixture()
        f.player.read("Test")
        f.outputs.last().ready(false)
        assertFalse(f.player.isReading)
        assertEquals(1, f.outputs.last().closes)
        assertEquals(1, f.failures)
        f.player.read("Again")
        f.outputs.last().ready(true)
        f.outputs.last().finished(false)
        assertFalse(f.player.isReading)
        assertEquals(1, f.outputs.last().closes)
        assertEquals(2, f.failures)
    }

    @Test
    fun closingThePreviewStopsItAndIgnoresLaterClicksAndCallbacks() {
        val f = Fixture()
        f.player.read("Test")
        val output = f.outputs.single()
        output.ready(true)
        val stale = output.finished
        f.player.close()
        stale(true)
        f.player.read("Do not read")
        assertFalse(f.player.isReading)
        assertEquals(1, f.outputs.size)
        assertEquals(1, output.closes)
        assertEquals(1, f.pauses)
    }

    @Test
    fun longPreviewReadsEveryChunkSequentiallyWithinTheEngineLimit() {
        val f = Fixture(limit = 8)
        val text = "one two three four"
        f.player.read(text)
        val output = f.outputs.single()
        output.ready(true)
        assertEquals(listOf("one two "), output.spoken)
        while (f.player.isReading) output.finished(true)
        assertEquals(text, output.spoken.joinToString(""))
        assertTrue(output.spoken.all { it.length <= 8 && it.isNotBlank() })
        assertEquals(1, output.closes)
    }

    @Test
    fun chunkBoundariesPreserveUnicodePairsAndNeverQueueWhitespaceOnlyText() {
        val text = "abc😀def 🤖ghi"
        val chunks = splitTTSPreviewText(text, 4)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 4 })
        assertTrue(chunks.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        assertEquals(emptyList<String>(), splitTTSPreviewText("        ", 4))
    }
}
