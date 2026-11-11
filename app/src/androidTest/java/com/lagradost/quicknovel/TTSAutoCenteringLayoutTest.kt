package com.lagradost.quicknovel

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableString
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.ui.ScrollVisibilityItem
import com.lagradost.quicknovel.ui.TextAdapter
import com.lagradost.quicknovel.ui.TextConfig
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise actual reader geometry and animation without starting a speech engine. */
@RunWith(AndroidJUnit4::class)
class TTSAutoCenteringLayoutTest {
    private class Fixture(activity: AiSettingsActivity) {
        val reader = RecyclerView(activity)
        val layout = LinearLayoutManager(activity)
        val rows = List(30) { index ->
            val text = SpannableString("This is paragraph ${index + 1}.")
            TextSpan(text, index * 100, index * 100 + text.length, 3, index)
        }
        val adapter = TextAdapter(
            ReadActivityViewModel(),
            TextConfig(0, Color.WHITE, 22, "", Typeface.DEFAULT, Color.BLACK, false, false, 8f),
        )
        fun viewport(): TTSCenteringViewport {
            val location = IntArray(2)
            reader.getLocationInWindow(location)
            return TTSCenteringViewport(
                TTSVerticalBounds(location[1], location[1] + reader.height),
                location[1] + reader.height / 2,
            )
        }
        val centering = TTSAutoCentering(reader, layout, adapter, { rows }, ::viewport, { true })

        init {
            reader.layoutManager = layout
            reader.adapter = adapter
            reader.itemAnimator = null
            activity.setContentView(reader)
            adapter.submitList(rows)
            centering.onReaderVisible()
        }

        fun speech(sequence: Long, row: Int): TTSSentenceStart = rows[row].let {
            TTSSentenceStart(sequence, TTSHelper.TTSLine(it.text.toString(), it.start, it.end, it.index))
        }

        fun isCentered(speech: TTSSentenceStart): Boolean {
            if (reader.scrollState != RecyclerView.SCROLL_STATE_IDLE || reader.isLayoutRequested) return false
            val position = rows.indexOfFirst { it.start == speech.line.startChar }
            val holder = reader.findViewHolderForAdapterPosition(position) ?: return false
            val target = ttsCenteringTarget(adapter.getLines(ScrollVisibilityItem(position, holder)), speech.line)
                ?: return false
            return kotlin.math.abs(target.scrollBy(viewport())) <= 2
        }
    }

    private fun await(scenario: ActivityScenario<AiSettingsActivity>, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        do {
            var done = false
            scenario.onActivity { done = condition() }
            if (done) return
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Reader condition was not reached")
    }

    @Test
    fun visibleUnfocusedReaderCentersSuccessiveSentencesWithoutUndoingLaterManualScrolling() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            lateinit var dialog: Dialog
            scenario.onActivity { activity ->
                fixture = Fixture(activity)
                dialog = Dialog(activity).apply {
                    setContentView(TextView(activity).apply { text = "Another window" })
                    show()
                    window!!.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window!!.setLayout(180, 100)
                    window!!.setGravity(Gravity.TOP or Gravity.END)
                }
            }
            try {
                await(scenario) { fixture.reader.isShown && !fixture.reader.hasWindowFocus() }
                for ((sequence, row) in listOf(1L to 10, 2L to 20)) {
                    val speech = fixture.speech(sequence, row)
                    scenario.onActivity { fixture.centering.onSentenceStarted(speech) }
                    await(scenario) { fixture.isCentered(speech) }
                }
                scenario.onActivity {
                    fixture.centering.onReaderTouchStarted()
                    fixture.layout.scrollToPositionWithOffset(0, 0)
                    fixture.centering.onReaderTouchEnded()
                    fixture.centering.onContentChanged()
                    fixture.centering.onWindowFocusRestored()
                }
                await(scenario) { fixture.layout.findFirstVisibleItemPosition() == 0 }
                SystemClock.sleep(350)
                scenario.onActivity { assertEquals(0, fixture.layout.findFirstVisibleItemPosition()) }
            } finally {
                scenario.onActivity { dialog.dismiss() }
            }
        }
    }

    @Test
    fun interruptedScrollResumesEvenWhenTheSameSpeechStartWasAlreadyConsumed() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it) }
            val speech = fixture.speech(1, 20)
            scenario.onActivity { fixture.centering.onSentenceStarted(speech) }
            await(scenario) { fixture.reader.scrollState == RecyclerView.SCROLL_STATE_SETTLING }
            scenario.onActivity {
                fixture.centering.onReaderHidden()
                assertEquals(RecyclerView.SCROLL_STATE_IDLE, fixture.reader.scrollState)
                fixture.centering.onSentenceStarted(speech)
                fixture.centering.onReaderVisible()
            }
            await(scenario) { fixture.isCentered(speech) }
        }
    }

    @Test
    fun delayedLayoutEventuallyCentersWithoutAnotherSpeechOrContentEvent() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it) }
            await(scenario) { fixture.reader.childCount > 0 }
            val speech = fixture.speech(1, 20)
            scenario.onActivity {
                fixture.reader.suppressLayout(true)
                fixture.reader.requestLayout()
                fixture.centering.onSentenceStarted(speech)
            }
            // More than the old three-frame retry budget.
            SystemClock.sleep(200)
            scenario.onActivity { fixture.reader.suppressLayout(false) }
            await(scenario) { fixture.isCentered(speech) }
        }
    }
}
