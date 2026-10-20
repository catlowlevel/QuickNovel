package com.lagradost.quicknovel.ai

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.ReaderAiProgressBinding
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat

/** Real window/touch regression: a progress dialog would prevent the reader target receiving clicks. */
@RunWith(AndroidJUnit4::class)
class ReaderAiProgressOverlayTest {
    @Test
    fun generationDismissesTheModalAndReaderTouchesAndStopStillWork() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val reply = CompletableDeferred<AiSummaryOutput>()
            var dialog: ReaderSummaryDialog? = null
            lateinit var controller: AiSummaryController
            lateinit var overlay: ReaderAiProgressOverlay
            lateinit var binding: ReaderAiProgressBinding
            lateinit var readerUi: ReadMainBinding
            lateinit var progress: (Long) -> Unit
            var readerTaps = 0
            var expectedCount = ""
            try {
                scenario.onActivity { activity ->
                    readerUi = ReadMainBinding.inflate(activity.layoutInflater)
                    readerUi.readNormalLayout.visibility = View.VISIBLE
                    readerUi.readNormalLayout.alpha = 1f
                    val reader = Button(activity).apply {
                        text = "Reader touch target"
                        setOnClickListener { readerTaps++ }
                    }
                    readerUi.readNormalLayout.addView(reader,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    binding = readerUi.readerAiProgress
                    binding.root.layoutParams = (binding.root.layoutParams as FrameLayout.LayoutParams).apply {
                        topMargin = (96 * activity.resources.displayMetrics.density).toInt()
                    }
                    activity.setContentView(readerUi.root)
                    val store = object : AiSettingsPersistence {
                        override suspend fun load() = AiSettings().withConfig(AiProviderConfig("fixture-key", "fixture-model"))
                        override suspend fun save(settings: AiSettings) = error("No settings writes expected")
                    }
                    val api = object : AiSummaryApi {
                        override suspend fun summarize(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String): AiSummaryOutput =
                            error("Streaming is expected")

                        override suspend fun streamSummary(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String,
                                                           onProgress: (Long) -> Unit): AiSummaryOutput {
                            progress = onProgress
                            return reply.await()
                        }
                    }
                    controller = AiSummaryController(scope, store, api)
                    overlay = ReaderAiProgressOverlay(binding, onStop = {
                        controller.close()
                        overlay.render(controller.state.value)
                    }, onVisibilityChanged = {})
                    dialog = ReaderSummaryDialog(activity, controller)
                    controller.open(AiChapter("book", "chapter", "Novel", "Chapter 4", "Original text"), "en")
                    dialog!!.render(controller.state.value)
                    controller.generate()
                    overlay.render(controller.state.value)
                    dialog!!.render(controller.state.value)
                    assertTrue(controller.state.value is AiSummaryState.Running)
                }
                onView(withText("Reader touch target")).perform(click())
                scenario.onActivity { activity ->
                    assertEquals(1, readerTaps)
                    assertTrue(controller.state.value is AiSummaryState.Running)
                    progress(23)
                    overlay.render(controller.state.value)
                    expectedCount = activity.resources.getQuantityString(R.plurals.ai_generated_token_count, 23,
                        NumberFormat.getIntegerInstance().format(23))
                }
                onView(withId(R.id.count)).check(matches(withText(expectedCount)))
                scenario.onActivity {
                    // Actual reader layout hides this panel while loading or showing a chapter error.
                    readerUi.readNormalLayout.visibility = View.GONE
                    assertTrue(binding.root.isShown)
                }
                onView(withId(R.id.stop)).check(matches(isDisplayed())).perform(click())
                scenario.onActivity {
                    assertEquals(AiSummaryState.Closed, controller.state.value)
                    assertFalse(binding.root.isShown)
                    readerUi.readNormalLayout.visibility = View.VISIBLE
                }
                onView(withText("Reader touch target")).perform(click())
                scenario.onActivity { assertEquals(2, readerTaps) }
            } finally {
                scenario.onActivity {
                    dialog?.dismissForDestroy()
                    scope.cancel()
                }
            }
        }
    }
}
