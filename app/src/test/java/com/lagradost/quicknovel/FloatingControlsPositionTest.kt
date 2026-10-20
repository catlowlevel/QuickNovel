package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingControlsPositionTest {
    private val portrait = FloatingControlsBounds.forArea(12f, 70f, 348f, 700f, 152f, 56f)

    @Test
    fun savedPositionSurvivesStorageAndFitsARotatedViewport() {
        val position = FloatingControlsPosition.fromOffset(104f, 350f, portrait)
        val restored =
            DataStore.mapper.readValue(
                DataStore.mapper.writeValueAsString(position),
                FloatingControlsPosition::class.java,
            )
        assertEquals(position, restored)
        val landscape = FloatingControlsBounds.forArea(40f, 70f, 760f, 320f, 152f, 56f)
        val (x, y) = restored.place(landscape)
        assertEquals(324f, x, 0.01f)
        assertEquals(164.634f, y, 0.01f)
        assertEquals(x to y, landscape.constrain(x, y))
        val (originalX, originalY) = restored.place(portrait)
        assertEquals(104f, originalX, 0.01f)
        assertEquals(350f, originalY, 0.01f)
    }

    @Test
    fun draggingPastEdgesKeepsTheWholePanelInsideTheUsableArea() {
        val (left, top) = portrait.constrain(-100f, -100f)
        assertEquals(12f to 70f, left to top)
        val (right, bottom) = portrait.constrain(10_000f, 10_000f)
        assertEquals(196f to 644f, right to bottom)
        assertEquals(196f to 644f, FloatingControlsPosition.fromOffset(10_000f, 10_000f, portrait).place(portrait))
        // Opening the toolbar/playback controls reduces the usable movement range.
        val withControls = FloatingControlsBounds.forArea(12f, 126f, 348f, 640f, 152f, 56f)
        assertEquals(196f to 584f, FloatingControlsPosition().place(withControls))
    }

    @Test
    fun tinyWindowsHaveAStablePositionAndCanExpandAgainWithoutDivisionByZero() {
        val tiny = FloatingControlsBounds.forArea(12f, 70f, 150f, 100f, 152f, 56f)
        assertEquals(12f to 70f, tiny.constrain(90f, 200f))
        val position = FloatingControlsPosition.fromOffset(12f, 70f, tiny)
        assertEquals(12f to 70f, position.place(tiny))
        assertEquals(196f to 644f, position.place(portrait))
    }
}
