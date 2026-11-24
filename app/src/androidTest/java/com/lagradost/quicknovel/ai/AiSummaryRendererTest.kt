package com.lagradost.quicknovel.ai

import android.text.SpannableString
import android.text.Spanned
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.quicknovel.LiveChapterData
import com.lagradost.quicknovel.TTSHelper
import com.lagradost.quicknovel.TTSOverrideMatch
import com.lagradost.quicknovel.TTSReaderOverrideHighlights
import com.lagradost.quicknovel.TTSWordOverride
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import com.lagradost.quicknovel.ui.toUiText
import io.noties.markwon.core.spans.EmphasisSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Use actual Android spans and reader content; regex cleanup would lose styling and source offsets. */
@RunWith(AndroidJUnit4::class)
class AiSummaryRendererTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val renderer by lazy { AiSummaryRenderer(context) }

    private fun source(): LiveChapterData {
        val original = SpannableString("The original chapter remains available.")
        val spans = TTSHelper.parseTextToSpans(original, 3)
        return LiveChapterData(3, spans, original, original, spans, "Chapter 4".toUiText(), "Original chapter HTML")
    }

    private inline fun <reified T : Any> styledWords(text: Spanned): Set<String> =
        text.getSpans(0, text.length, T::class.java).map { span ->
            text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString()
        }.toSet()

    private fun assertSpeechCoordinates(content: LiveChapterData) {
        val text = content.rendered.toString()
        assertTrue(content.ttsLines.isNotEmpty())
        content.ttsLines.forEach { line ->
            assertEquals(content.index, line.index)
            assertEquals(text.substring(line.startChar, line.endChar), line.speakOutMsg)
        }
        content.spans.forEach { span ->
            assertEquals(text.substring(span.start, span.end), span.text.toString())
        }
    }

    @Test
    fun boldAndItalicRenderBeforeSpeechAndRuleHighlightCoordinatesAreCalculated() {
        val output = AiSummaryOutput("The **bus driver** found the *missing bag*. Everyone was **relieved**.")
        val content = renderer.chapter(source(), output)
        val text = "The bus driver found the missing bag. Everyone was relieved."
        assertEquals(text, content.rendered.toString())
        assertEquals(setOf("bus driver", "relieved"), styledWords<StrongEmphasisSpan>(content.rendered))
        assertEquals(setOf("missing bag"), styledWords<EmphasisSpan>(content.rendered))
        assertEquals(
            listOf("The bus driver found the missing bag.", "Everyone was relieved."),
            content.ttsLines.map { it.speakOutMsg },
        )
        assertSpeechCoordinates(content)
        assertEquals(setOf("bus driver", "relieved"), styledWords<StrongEmphasisSpan>(content.spans.single().text))
        val highlights = TTSReaderOverrideHighlights().apply {
            updateRules(listOf(TTSWordOverride(find = "bag", replacement = "backpack")))
        }
        val start = text.indexOf("bag")
        assertEquals(listOf(TTSOverrideMatch(start, start + 3)), highlights.forParagraph(content.ttsLines, 3, 0, text.length))
        assertTrue(output.text.contains("**bus driver**"))
    }

    @Test
    fun underscoreAndNestedEmphasisUseRealStylesWithoutSpokenDelimiters() {
        val content = renderer.chapter(source(), AiSummaryOutput("The __driver__ was _careful_ and ***very relieved***."))
        assertEquals("The driver was careful and very relieved.", content.rendered.toString())
        assertEquals(setOf("driver", "very relieved"), styledWords<StrongEmphasisSpan>(content.rendered))
        assertEquals(setOf("careful", "very relieved"), styledWords<EmphasisSpan>(content.rendered))
        assertEquals("The driver was careful and very relieved.", content.ttsLines.single().speakOutMsg)
        assertSpeechCoordinates(content)
    }

    @Test
    fun paragraphsAndSoftLineBreaksRetainTheirStylesAndReaderRanges() {
        val content = renderer.chapter(
            source(),
            AiSummaryOutput("The **package** arrived.\n\nThe *office*\nreopened."),
        )
        assertEquals("The package arrived.\n\nThe office\nreopened.", content.rendered.toString())
        assertEquals(3, content.spans.size)
        assertEquals(setOf("package"), styledWords<StrongEmphasisSpan>(content.spans[0].text))
        assertEquals(setOf("office"), styledWords<EmphasisSpan>(content.spans[1].text))
        assertSpeechCoordinates(content)
        val joined = content.paragraphs(true)
        assertEquals("The package arrived.\n\nThe office reopened.", joined.rendered.toString())
        assertEquals(setOf("office"), styledWords<EmphasisSpan>(joined.rendered))
        assertSpeechCoordinates(joined)
    }

    @Test
    fun literalSymbolsEscapedAsterisksAndUnfinishedEmphasisKeepTheirMeaning() {
        val output = AiSummaryOutput("Use 2 * 3, hero_name, and \\*literal asterisks\\*. Leave *unfinished.")
        val content = renderer.chapter(source(), output)
        val text = "Use 2 * 3, hero_name, and *literal asterisks*. Leave *unfinished."
        assertEquals(text, content.rendered.toString())
        assertTrue(styledWords<StrongEmphasisSpan>(content.rendered).isEmpty())
        assertTrue(styledWords<EmphasisSpan>(content.rendered).isEmpty())
        assertEquals(text, content.ttsLines.joinToString(" ") { it.speakOutMsg })
        assertSpeechCoordinates(content)
    }

    @Test
    fun cachedMarkdownCanBeReusedAndRestoredWithoutChangingSourceOrOriginalPosition() = runBlocking {
        val original = source()
        val source = AiChapter("fixture-book", "fixture-chapter", "Novel", "Chapter 4", original.originalPlainText)
        val output = AiSummaryOutput("The **delivery** arrived. Everyone was *ready*.")
        val directory = File(context.cacheDir, "summary-renderer-test-${UUID.randomUUID()}")
        try {
            FileAiSummaryCache(directory).save(
                AiCachedSummary(source.resultKey("en"), "en", AiProvider.GEMINI, "Fixture", "fixture-model", output),
            )
            val cached = FileAiSummaryCache(directory).load(source, "en")!!
            assertEquals(output, cached.output)
            val views = AiChapterTextViews<LiveChapterData>()
            val position = AiOriginalPosition(2, 17)
            repeat(2) {
                val content = renderer.chapter(original, cached.output)
                views.show(source, cached.output, content, position)
                assertEquals("The delivery arrived. Everyone was ready.", views.displayed(source)!!.content.rendered.toString())
                assertEquals(setOf("delivery"), styledWords<StrongEmphasisSpan>(content.rendered))
                assertSpeechCoordinates(content)
                assertSame(original.originalRendered, content.originalRendered)
                assertSame(original.originalSpans, content.originalSpans)
                assertEquals(original.rawText, content.rawText)
                assertEquals(position, views.restore(source))
                assertNull(views.displayed(source))
            }
            assertEquals("The original chapter remains available.", original.rendered.toString())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun savedSummaryPopupDisplaysStyledPreviewWithoutGeneratingAgain() {
        ActivityScenario.launch(AiSettingsActivity::class.java).use { scenario ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            lateinit var controller: AiSummaryController
            lateinit var dialog: ReaderSummaryDialog
            var requests = 0
            try {
                scenario.onActivity { activity ->
                    val store = object : AiSettingsPersistence {
                        override suspend fun load() = AiSettings().withConfig(AiProviderConfig("fixture-key", "fixture-model"))
                        override suspend fun save(settings: AiSettings) = error("No settings writes expected")
                    }
                    val api = object : AiSummaryApi {
                        override suspend fun summarize(provider: AiProvider, config: AiProviderConfig, chapter: AiChapter, language: String): AiSummaryOutput {
                            requests++
                            return AiSummaryOutput("A **short recap** with an *important detail*.")
                        }
                    }
                    controller = AiSummaryController(scope, store, api)
                    dialog = ReaderSummaryDialog(activity, controller)
                    val chapter = AiChapter("preview-book", "preview-chapter", "Novel", "Chapter", "Original chapter text.")
                    controller.open(chapter, "en")
                    controller.generate()
                    assertTrue(controller.state.value is AiSummaryState.Ready)
                    controller.close()
                    controller.open(chapter, "en")
                    assertTrue(controller.state.value is AiSummaryState.Saved)
                    dialog.render(controller.state.value)
                }
                onView(withText("A short recap with an important detail.")).check(matches(isDisplayed()))
                onView(withText("A short recap with an important detail.")).check { view, error ->
                    if (error != null) throw error
                    val text = (view as TextView).text as Spanned
                    assertEquals(setOf("short recap"), styledWords<StrongEmphasisSpan>(text))
                    assertEquals(setOf("important detail"), styledWords<EmphasisSpan>(text))
                }
                scenario.onActivity { assertEquals(1, requests) }
            } finally {
                scenario.onActivity {
                    dialog.dismissForDestroy()
                    scope.cancel()
                }
            }
        }
    }
}
