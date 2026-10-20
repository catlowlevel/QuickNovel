package com.lagradost.quicknovel

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.databinding.FloatingTtsControlsBinding
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise real touch listeners and Android relayouts without starting a speech engine. */
@RunWith(AndroidJUnit4::class)
class FloatingTTSControlsLayoutTest {
    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private inner class Fixture(activity: AiSettingsActivity) {
        val container = FrameLayout(activity)
        val binding = FloatingTtsControlsBinding.inflate(activity.layoutInflater, container, true)
        val saved = mutableListOf<FloatingControlsPosition>()
        var playbackClicks = 0

        fun bounds() =
            FloatingControlsBounds.forArea(
                0f,
                0f,
                container.width.toFloat(),
                container.height.toFloat(),
                binding.root.width.toFloat(),
                binding.root.height.toFloat(),
            )

        val controls =
            FloatingTTSControls(
                binding,
                FloatingControlsPosition(0f, 0f),
                ::bounds,
                savePosition = { saved += it },
                togglePlayback = { playbackClicks++ },
                previousSentence = { error("Dragging must not navigate") },
                nextSentence = { error("Dragging must not navigate") },
            )

        init {
            controls.watchContainerLayout(container, controls::reposition)
            activity.setContentView(container)
            controls.update(true, TTSHelper.TTSStatus.IsRunning)
        }

        fun beginDrag() {
            assertTrue(binding.root.hasWindowFocus())
            controls.reposition()
            touch(binding.pausePlay, MotionEvent.ACTION_DOWN, 100f, 100f)
            touch(binding.pausePlay, MotionEvent.ACTION_MOVE, 164f, 148f)
            assertEquals(64f, binding.root.x, 0.01f)
            assertEquals(48f, binding.root.y, 0.01f)
        }
    }

    @Test
    fun playbackRelayoutKeepsTheDragUnderTheFingerAndSavesTheFinalPosition() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it) }
            onView(withId(R.id.pause_play)).check(matches(isDisplayed()))
            scenario.onActivity {
                with(fixture) {
                    beginDrag()
                    repeat(3) {
                        controls.update(true, TTSHelper.TTSStatus.IsRunning)
                        // A sentence/progress update requests layout without resizing the reader.
                        container.forceLayout()
                        container.layout(container.left, container.top, container.right, container.bottom)
                        assertEquals(64f, binding.root.x, 0.01f)
                        assertEquals(48f, binding.root.y, 0.01f)
                    }
                    touch(binding.pausePlay, MotionEvent.ACTION_MOVE, 180f, 180f)
                    assertEquals(80f, binding.root.x, 0.01f)
                    assertEquals(80f, binding.root.y, 0.01f)
                    touch(binding.pausePlay, MotionEvent.ACTION_UP, 180f, 180f)
                    assertEquals(1, saved.size)
                    val (x, y) = saved.single().place(bounds())
                    assertEquals(80f, x, 0.01f)
                    assertEquals(80f, y, 0.01f)
                    controls.reposition()
                    assertEquals(x, binding.root.x, 0.01f)
                    assertEquals(y, binding.root.y, 0.01f)
                    assertEquals(0, playbackClicks)
                }
            }
        }
    }

    @Test
    fun actualContainerResizeStillCancelsTheOldGestureAndConstrainsThePanel() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it) }
            onView(withId(R.id.pause_play)).check(matches(isDisplayed()))
            scenario.onActivity {
                with(fixture) {
                    beginDrag()
                    container.layout(container.left, container.top, container.right - 10, container.bottom - 10)
                    val expected = FloatingControlsPosition(0f, 0f).place(bounds())
                    touch(binding.pausePlay, MotionEvent.ACTION_MOVE, 220f, 220f)
                    touch(binding.pausePlay, MotionEvent.ACTION_UP, 220f, 220f)
                    assertEquals(expected.first, binding.root.x, 0.01f)
                    assertEquals(expected.second, binding.root.y, 0.01f)
                    assertEquals(expected, bounds().constrain(binding.root.x, binding.root.y))
                    assertTrue(saved.isEmpty())
                    assertEquals(0, playbackClicks)
                }
            }
        }
    }
}
