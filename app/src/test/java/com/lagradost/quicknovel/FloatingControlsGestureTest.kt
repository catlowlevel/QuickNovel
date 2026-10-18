package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingControlsGestureTest {
    private val gesture = FloatingControlsGesture(touchSlop = 8f)

    @Test
    fun stationaryPressWithFingerJitterProducesOnlyOneClickOnRelease() {
        gesture.down(100f, 100f, 20f, 30f)
        repeat(20) {
            assertNull(gesture.move(103f, 104f))
            assertNull(gesture.move(100f, 100f))
        }
        assertFalse(gesture.isDragging)
        assertTrue(gesture.up())
        assertFalse(gesture.up())
    }

    @Test
    fun movingWithinAButtonStartsDraggingAndSuppressesItsClick() {
        gesture.down(100f, 100f, 20f, 30f)
        // Cross touch slop while still well inside a 48dp button.
        assertEquals(29f to 30f, gesture.move(109f, 100f))
        assertTrue(gesture.isDragging)
        assertFalse(gesture.up())
    }

    @Test
    fun draggingUsesTheOriginalFingerOffsetAndCannotBecomeAClickWhenReturning() {
        gesture.down(100f, 100f, 20f, 30f)
        assertEquals(40f to 40f, gesture.move(120f, 110f))
        assertEquals(60f to 50f, gesture.move(140f, 120f))
        assertEquals(20f to 30f, gesture.move(100f, 100f))
        assertTrue(gesture.isDragging)
        assertFalse(gesture.up())
    }

    @Test
    fun cancellationPreventsMovementAndClicksUntilANewPressStarts() {
        gesture.down(100f, 100f, 20f, 30f)
        gesture.move(130f, 100f)
        gesture.cancel()
        assertFalse(gesture.isActive)
        assertNull(gesture.move(160f, 100f))
        assertFalse(gesture.up())
        gesture.down(200f, 200f, 50f, 60f)
        assertTrue(gesture.up())
    }
}
