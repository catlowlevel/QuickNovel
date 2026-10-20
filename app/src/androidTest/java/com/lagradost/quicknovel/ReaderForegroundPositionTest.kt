package com.lagradost.quicknovel

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityIndex
import com.lagradost.quicknovel.ui.TextVisualLine
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the actual ViewModel lifecycle and delayed narration application, not a mock flag. */
@RunWith(AndroidJUnit4::class)
class ReaderForegroundPositionTest {
    @Test
    fun repeatedResumesRestoreForegroundOwnershipAndRejectDelayedNarrationPositions() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            scenario.onActivity {
                val model = ReadActivityViewModel()
                val readerPosition = ScrollIndex(2, 3, 40)
                val narrationPosition = ScrollIndex(8, 1, 20)
                model.desiredIndex = readerPosition
                assertFalse(model.followsBackgroundNarration)
                assertFalse(model.followBackgroundNarration(narrationPosition))
                repeat(3) {
                    model.leftApp()
                    assertTrue(model.followsBackgroundNarration)
                    model.resumedApp()
                    assertFalse(model.followsBackgroundNarration)
                    // A background layout query completed after resume: it must not overwrite reading.
                    assertFalse(model.followBackgroundNarration(narrationPosition))
                    assertEquals(readerPosition, model.desiredIndex)
                    assertEquals(Int.MIN_VALUE, model.currentIndex)
                }
            }
        }
    }

    @Test
    fun aLateViewportCallbackCannotOverwriteBackgroundNarrationPosition() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            scenario.onActivity {
                val model = ReadActivityViewModel()
                val narrationPosition = ScrollIndex(8, 1, 20)
                model.desiredIndex = narrationPosition
                model.leftApp()
                val oldLine = TextVisualLine(0, 10, 2, 0, 480, 520)
                model.onScroll(ScrollVisibilityIndex(oldLine, oldLine, oldLine, oldLine, oldLine, oldLine))
                assertEquals(narrationPosition, model.desiredIndex)
                assertEquals(Int.MIN_VALUE, model.currentIndex)
            }
        }
    }
}
