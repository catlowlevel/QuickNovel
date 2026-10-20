package com.lagradost.quicknovel

import com.lagradost.quicknovel.ui.common.ImmutableChapterData
import com.lagradost.quicknovel.util.filterChapterTitles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ChapterTitlesTest {
    @Test
    fun decodesApostrophesInNumericAndNamedForms() {
        val expected = "Chapter 17: The Captain's Map"
        for (entity in listOf("&#039;", "&#39;", "&#x27;", "&#X27;", "&apos;")) {
            assertEquals(expected, chapterTitleText(expected.replace("'", entity)))
        }
    }

    @Test
    fun decodesNamedPunctuationAndUnicodeWithoutLosingCharacters() {
        assertEquals("“Winter’s End” & 火 🔥\u00a0", chapterTitleText(
            "&ldquo;Winter&rsquo;s End&rdquo; &amp; &#28779; &#x1F525;&nbsp;"
        ))
    }

    @Test
    fun preservesPlainTextAmpersandsAndUnknownEntities() {
        val title = "R&D &notable &unknown; &#xZZ; https://example.com/?a=1&copy=2"
        assertEquals(title, chapterTitleText(title))
        assertEquals("The Captain's Map — 火", chapterTitleText("The Captain's Map — 火"))
        assertEquals("", chapterTitleText(""))
    }

    @Test
    fun preservesAngleBracketsAndSpacingRatherThanParsingMarkup() {
        val title = "  <Gate> A < B > C <b>Storm</b>  "
        assertEquals(title, chapterTitleText(title))
        assertEquals("<Gate> & <b>Storm</b>", chapterTitleText("&lt;Gate&gt; &amp; &lt;b&gt;Storm&lt;/b&gt;"))
    }

    @Test
    fun decodesOneLayerSoEscapedEntityExamplesAreNotDecodedRecursively() {
        assertEquals("The &#039; symbol", chapterTitleText("The &amp;#039; symbol"))
        assertEquals("The &amp; symbol", chapterTitleText("The &amp;amp; symbol"))
    }

    @Test
    fun previouslySavedChapterTitlesDisplayCorrectlyWithoutRewritingMetadata() {
        val json = """{"name":"The Captain&#039;s Map","url":"https://example.com/chapter?x=1&y=2","dateOfRelease":"Today","views":42}"""
        val chapter = DataStore.mapper.readValue(json, ChapterData::class.java)
        assertEquals("The Captain's Map", chapter.displayName)
        assertEquals("The Captain&#039;s Map", chapter.name)
        assertEquals("https://example.com/chapter?x=1&y=2", chapter.url)
        assertEquals("Today", chapter.dateOfRelease)
        assertEquals(42, chapter.views)
        val serialized = DataStore.mapper.readTree(DataStore.mapper.writeValueAsString(chapter))
        assertEquals("The Captain&#039;s Map", serialized["name"].asText())
        assertFalse(serialized.has("displayName"))
    }

    @Test
    fun chapterListAndSearchUseTheSameDecodedTitleAndKeepChapterIdentity() {
        val chapter = ChapterData("Chapter 17: The Captain&#039;s Map", "/chapter/17", "Today", 42)
        val displayed = ImmutableChapterData.from(chapter, 16)
        assertEquals(chapter.displayName, displayed.displayName)
        assertEquals(chapter.name, displayed.name)
        assertEquals("Chapter 17: The Captain's Map", displayed.displayName)
        assertEquals(chapter.url, displayed.url)
        assertEquals(chapter.dateOfRelease, displayed.dateOfRelease)
        assertEquals(chapter.views, displayed.views)
        assertEquals(16, displayed.index)
        assertSame(chapter, filterChapterTitles(listOf(chapter), "Captain's") { it.displayName }.single())
    }
}
