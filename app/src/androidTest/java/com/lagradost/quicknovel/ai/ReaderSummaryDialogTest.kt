package com.lagradost.quicknovel.ai

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real AlertDialog view ownership; JVM controller tests cannot detect this crash. */
@RunWith(AndroidJUnit4::class)
class ReaderSummaryDialogTest {
    private val chapter = AiChapter("test-book", "test-chapter", "Novel", "Chapter", "Original chapter text")

    private fun withDialog(test: (AiSummaryController, ReaderSummaryDialog) -> Unit) {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val store =
                    object : AiSettingsPersistence {
                        override suspend fun load() =
                            AiSettings().withConfig(AiProviderConfig("fixture-key", "fixture-model"))

                        override suspend fun save(settings: AiSettings) = error("No settings writes expected")
                    }
                val api =
                    object : AiSummaryApi {
                        override suspend fun summarize(
                            provider: AiProvider,
                            config: AiProviderConfig,
                            chapter: AiChapter,
                            language: String,
                        ) = AiSummaryOutput("Fixture summary")
                    }
                val controller = AiSummaryController(scope, store, api)
                val dialog = ReaderSummaryDialog(activity, controller)
                try {
                    test(controller, dialog)
                } finally {
                    dialog.dismissForDestroy()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun summarizeRestoreAndSummarizeAgainCanRepeatedlyReopenTheSavedSummaryPopup() = withDialog { controller, dialog ->
        val views = AiChapterTextViews<String>()
        controller.open(chapter, "en")
        dialog.render(controller.state.value)
        controller.generate()
        val generated = controller.state.value as AiSummaryState.Ready
        views.show(chapter, generated.output, generated.output.text, AiOriginalPosition(2, 10))
        controller.close()
        dialog.render(controller.state.value)
        repeat(3) {
            assertNotNull(views.restore(chapter))
            assertNull(views.displayed(chapter))
            controller.open(chapter, "en")
            assertTrue(controller.state.value is AiSummaryState.Saved)
            dialog.render(controller.state.value) // Previously: child already has a parent.
            controller.reuse()
            val reused = controller.state.value as AiSummaryState.Ready
            views.show(chapter, reused.output, reused.output.text, AiOriginalPosition(2, 10))
            controller.close()
            dialog.render(controller.state.value)
        }
    }

    @Test
    fun cancellingAndReopeningConfirmationCreatesAnIndependentDialogView() = withDialog { controller, dialog ->
        repeat(3) {
            controller.open(chapter, "en")
            assertTrue(controller.state.value is AiSummaryState.Confirm)
            dialog.render(controller.state.value)
            controller.close()
            dialog.render(controller.state.value)
        }
    }
}
