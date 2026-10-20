package com.lagradost.quicknovel

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.URLSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.quicknovel.ui.toUiText
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android spans are required to verify style relocation and structural Markwon boundaries. */
@RunWith(AndroidJUnit4::class)
class ReaderParagraphPresentationTest {
    private fun chapter(rendered: Spanned) =
        LiveChapterData(
            index = 3,
            rendered = rendered,
            spans = TTSHelper.parseTextToSpans(rendered, 3),
            originalRendered = rendered,
            originalSpans = TTSHelper.parseTextToSpans(rendered, 3),
            title = "Chapter".toUiText(),
            rawText = "Original HTML",
        )

    @Test
    fun presentationPreservesInlineStylesAndOriginalTextWhileSpeechUsesJoinedCoordinates() {
        val text = SpannableString("He looked at\n\nher with surprise.\n\nThen left.")
        val bold = StyleSpan(Typeface.BOLD)
        val link = URLSpan("https://example.test/character")
        val her = text.toString().indexOf("her")
        text.setSpan(bold, her, her + 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(link, her, her + 3, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        val source = chapter(text)
        val joined = source.paragraphs(true)
        assertEquals("He looked at her with surprise.\n\nThen left.", joined.rendered.toString())
        assertSame(source, joined.paragraphs(false))
        assertSame(joined, source.paragraphs(true))
        assertSame(text, joined.originalRendered)
        assertEquals(text.toString(), joined.originalPlainText)
        assertEquals("Original HTML", joined.rawText)
        assertEquals(
            "her",
            joined.rendered
                .subSequence(joined.rendered.getSpanStart(bold), joined.rendered.getSpanEnd(bold))
                .toString(),
        )
        assertEquals(joined.rendered.getSpanStart(bold), joined.rendered.getSpanStart(link))
        assertEquals(her, joined.sourceCharacter(joined.rendered.getSpanStart(link)))
        assertEquals(2, joined.spans.size)
        assertEquals(2, joined.ttsLines.size)
        assertEquals(3, source.ttsLines.size)
        assertEquals(
            listOf("He looked at her with surprise."),
            joined.joinedSentences.map { joined.rendered.subSequence(it.start, it.end).toString() },
        )
        assertTrue(source.joinedSentences.isEmpty())
        assertTrue(joined.paragraphs(false).joinedSentences.isEmpty())
        val last = joined.rendered.toString().indexOf("Then")
        assertEquals(1, joined.scrollAt(last).innerIndex)
        assertEquals(last, joined.displayCharacter(joined.sourceCharacter(last)))
        assertEquals(her, source.rendered.getSpanStart(bold))
    }

    @Test
    fun realHtmlHeadingsAndQuotesKeepTheirStructureWhileAdjacentProseCanJoin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val markwon = Markwon.builder(context).usePlugin(HtmlPlugin.create()).build()
        val rendered =
            markwon.toMarkdown(
                """
            <h2>an unfinished heading</h2>
            <p>He was walking</p><p>through the forest.</p>
            <blockquote><p>an unfinished thought</p><p>that stays quoted</p></blockquote>
            <p>He looked at</p><p>her.</p>
        """
                    .trimIndent()
            )
        val source = chapter(rendered)
        val joined = source.paragraphs(true)
        val text = joined.rendered.toString()
        assertTrue(text.contains("He was walking through the forest."))
        assertTrue(text.contains("He looked at her."))
        assertFalse(text.contains("heading He"))
        assertFalse(text.contains("thought that stays"))
        assertEquals(rendered.toString(), source.rendered.toString())
        assertSame(source, joined.paragraphs(false))
    }
}
