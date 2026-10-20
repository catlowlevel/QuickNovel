package com.lagradost.quicknovel.ai

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSummaryKeySelectionTest {
    @Test
    fun popupSelectsAnotherSavedProviderAndConfirmsItsModelAndEndpointBeforeGeneration() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            lateinit var controller: AiSummaryController
            lateinit var dialog: ReaderSummaryDialog
            lateinit var originalLabel: String
            lateinit var selectedLabel: String
            var calls = 0
            var usedConfig: AiProviderConfig? = null
            val original = AiKeyProfile("first", "Personal", AiProviderConfig("fixture-first", "first-model"))
            val selected =
                AiKeyProfile(
                    "second",
                    "Alternate",
                    AiProviderConfig("fixture-second", "second-model", "https://example.com/v1/"),
                )
            try {
                scenario.onActivity { activity ->
                    val store =
                        object : AiSettingsPersistence {
                            override suspend fun load() =
                                AiSettings(
                                    profiles =
                                        mapOf(
                                            AiProvider.GEMINI to listOf(original),
                                            AiProvider.OPENAI to listOf(selected),
                                        )
                                )

                            override suspend fun save(settings: AiSettings) = error("No global settings write expected")
                        }
                    val api =
                        object : AiSummaryApi {
                            override suspend fun summarize(
                                provider: AiProvider,
                                config: AiProviderConfig,
                                chapter: AiChapter,
                                language: String,
                            ): AiSummaryOutput {
                                calls++
                                assertEquals(AiProvider.OPENAI, provider)
                                usedConfig = config
                                return AiSummaryOutput("Fixture summary")
                            }
                        }
                    controller = AiSummaryController(scope, store, api)
                    dialog = ReaderSummaryDialog(activity, controller)
                    scope.launch { controller.state.collect(dialog::render) }
                    originalLabel = activity.getString(R.string.ai_summary_profile_choice, "Personal · first-model")
                    selectedLabel = activity.getString(R.string.ai_summary_profile_choice, "Alternate · second-model")
                    controller.open(
                        AiChapter("harbor", "chapter-1", "Harbor", "Arrival", "A ship reaches the harbor."),
                        "en",
                    )
                }
                onView(withContentDescription(originalLabel)).perform(click())
                onView(withText("Alternate · OpenAI\nsecond-model")).perform(click())
                onView(withContentDescription(selectedLabel)).check(matches(isDisplayed()))
                onView(withText("Alternate")).check(matches(isDisplayed()))
                onView(withText("second-model")).check(matches(isDisplayed()))
                onView(withText("example.com")).check(matches(isDisplayed()))
                scenario.onActivity {
                    assertEquals(0, calls)
                    assertEquals(selected.config, (controller.state.value as AiSummaryState.Confirm).plan.config)
                }
                onView(withText(R.string.ai_summary_generate)).perform(click())
                scenario.onActivity {
                    assertEquals(1, calls)
                    assertEquals(selected.config, usedConfig)
                }
            } finally {
                scenario.onActivity {
                    dialog.dismissForDestroy()
                    scope.cancel()
                }
            }
        }
    }
}
