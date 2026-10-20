package com.lagradost.quicknovel

import org.junit.Assert.*
import org.junit.Test

class ParagraphFormattingTest {
    private fun formatted(text: String) = ParagraphFormatting.plan(text).apply(text)

    @Test
    fun joinsSentenceFragmentsAcrossBothSoftAndParagraphBreaks() {
        val source =
            "He was walking\nthrough the forest\n\nand stopped at\n\nthe old gate.\n\nA new paragraph starts here."
        assertEquals(
            "He was walking through the forest and stopped at the old gate.\n\nA new paragraph starts here.",
            formatted(source),
        )
    }

    @Test
    fun removesOnlyBoundaryWhitespaceAndAcceptsWindowsLineEndings() {
        assertEquals("He looked at her, then left.", formatted("He looked at  \r\n\r\n\ther, then left."))
        assertEquals("  He  looked at her.  ", formatted("  He  looked at\n\nher.  "))
    }

    @Test
    fun preservesCompletedParagraphsAndDialogueBoundaries() {
        for (punctuation in listOf(".", "!", "?", "…", ":", ";", "。", "！", "？", "”", "’")) {
            val source = "He stopped$punctuation\n\nthen she spoke."
            assertEquals(source, formatted(source))
        }
        for (dialogue in listOf("\"Wait!\"", "“Wait!”", "'Wait!'", "— Wait!", "「Wait!」")) {
            val source = "He turned toward her\n\n$dialogue"
            assertEquals(source, formatted(source))
        }
    }

    @Test
    fun preservesHeadingsSceneBreaksListsAndImageRows() {
        for (structure in
            listOf(
                "Chapter 4",
                "The Next Day",
                "***",
                "---",
                "1. First item",
                "• First item",
                "- First item",
                "a) First item",
                "\uFFFC",
                "# heading",
                "> quoted text",
            )) {
            val source = "He was walking\n\n$structure\n\nthrough the forest."
            assertEquals(source, formatted(source))
        }
        assertEquals("left\tright\n\nnext row", formatted("left\tright\n\nnext row"))
    }

    @Test
    fun joinsProperNamesAfterConnectivesCommasAndCommonAbbreviations() {
        assertEquals("He looked at Sunny.", formatted("He looked at\n\nSunny."))
        assertEquals("He turned, Sunny waved.", formatted("He turned,\n\nSunny waved."))
        assertEquals("He visited Dr. Smith.", formatted("He visited Dr.\n\nSmith."))
        assertEquals("He visited Dr. Smith.", formatted("He visited Dr. Smith."))
    }

    @Test
    fun supportsUncasedProseWithoutMergingSentencesPastFullStops() {
        assertEquals("彼は森の中を歩いていた。\n\n次の日だった。", formatted("彼は森の中を\n\n歩いていた。\n\n次の日だった。"))
        assertEquals("그는 숲을 걷고 있었다.", formatted("그는 숲을\n\n걷고 있었다."))
    }

    @Test
    fun keepsHyphensWithoutIntroducingAnExtraSpaceInsideTheWord() {
        assertEquals("A well-known name.", formatted("A well-\n\nknown name."))
        assertEquals("He hesitated—\n\nthen stopped.", formatted("He hesitated—\n\nthen stopped."))
        assertEquals("He hesitated -\n\nthen stopped.", formatted("He hesitated -\n\nthen stopped."))
    }

    @Test
    fun styledStructuralRowsProtectOnlyTheirAdjacentBoundaries() {
        val source = "An unfinished heading\n\nwith lower case\n\nHe was walking\n\nthrough the forest."
        val heading = 0 until "An unfinished heading".length
        val plan = ParagraphFormatting.plan(source, listOf(heading))
        assertEquals(
            "An unfinished heading\n\nwith lower case\n\nHe was walking through the forest.",
            plan.apply(source),
        )
    }

    @Test
    fun everyRetainedCharacterRoundTripsBetweenSourceAndFormattedCoordinates() {
        val source = "He looked at\n\nher, then walked\r\n\r\nthrough a well-\n\nknown forest."
        val plan = ParagraphFormatting.plan(source)
        val display = plan.apply(source)
        assertEquals("He looked at her, then walked through a well-known forest.", display)
        for (character in source.indices) {
            if (plan.joins.any { character in it.start until it.end }) continue
            val mapped = plan.displayCharacter(character)
            assertEquals("Character $character", source[character], display[mapped])
            assertEquals(character, plan.sourceCharacter(mapped))
        }
        assertEquals(display.length, plan.displayCharacter(source.length))
        assertEquals(source.length, plan.sourceCharacter(display.length))
        assertEquals(0, plan.displayCharacter(-20))
        assertEquals(source.length, plan.sourceCharacter(Int.MAX_VALUE))
    }

    @Test
    fun formattingIsIdempotentAndLeavesAlreadyFormattedOrEmptyTextAlone() {
        for (source in
            listOf(
                "",
                "\n\n",
                "One complete paragraph.",
                "He looked at\n\nher.\n\nThen left.",
                "\n\nHe looked at\n\nher.\n",
            )) {
            val once = formatted(source)
            assertEquals(once, formatted(once))
        }
    }

    @Test
    fun speechUsesJoinedSentencesAndHighlightCoordinatesPointToDisplayedWords() {
        val source = "He looked at\n\nher with surprise.\n\nThen left."
        val display = formatted(source)
        val lines = TTSHelper.ttsParseText(display, 7)
        assertEquals(listOf("He looked at her with surprise.", "Then left."), lines.map { it.speakOutMsg })
        for (line in lines) assertEquals(line.speakOutMsg, display.substring(line.startChar, line.endChar))
        val highlights =
            TTSReaderOverrideHighlights().apply {
                updateRules(listOf(TTSWordOverride(find = "her with", replacement = "them with")))
            }
        assertEquals(listOf(TTSOverrideMatch(13, 21)), highlights.forParagraph(lines, 7, 0, display.indexOf('\n')))
    }

    @Test
    fun savedSpeechPositionsMigrateFromLegacySentenceNumbersAndSurviveToggling() {
        val source = "He looked at\n\nher with surprise.\n\nThen left."
        val plan = ParagraphFormatting.plan(source)
        val original = TTSHelper.ttsParseText(source, 2)
        val joined = TTSHelper.ttsParseText(plan.apply(source), 2)
        assertEquals(3, original.size)
        assertEquals(2, joined.size)
        val legacy = TTSPlaybackBookmark(2, 1, "chapter")
        assertEquals(0, legacy.resolveSentence(joined, plan::displayCharacter, original))
        val saved = TTSPlaybackBookmark(2, 1, "chapter", plan.sourceCharacter(joined[1].startChar))
        assertEquals(2, saved.resolveSentence(original, { it }, original))
        assertEquals(1, saved.resolveSentence(joined, plan::displayCharacter, original))
        val json = DataStore.mapper.writeValueAsString(saved)
        assertEquals(saved, DataStore.mapper.readValue(json, TTSPlaybackBookmark::class.java))
        val oldJson = """{"chapter":2,"sentence":1,"chapterId":"chapter"}"""
        assertEquals(legacy, DataStore.mapper.readValue(oldJson, TTSPlaybackBookmark::class.java))
    }
}
