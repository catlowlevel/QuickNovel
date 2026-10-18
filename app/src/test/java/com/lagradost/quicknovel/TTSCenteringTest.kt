package com.lagradost.quicknovel

import com.lagradost.quicknovel.ui.TextVisualLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TTSCenteringTest {
    @Test
    fun centersTheWholeParagraphAndKeepsItStillAcrossItsSentences() {
        val viewport = TTSCenteringViewport(TTSVerticalBounds(0, 1_000), 500)
        val paragraph = TTSVerticalBounds(100, 300)
        assertEquals(-300, TTSCenteringTarget(paragraph, TTSVerticalBounds(100, 140)).scrollBy(viewport))
        assertEquals(-300, TTSCenteringTarget(paragraph, TTSVerticalBounds(260, 300)).scrollBy(viewport))
        assertEquals(0, TTSCenteringTarget(TTSVerticalBounds(400, 600), TTSVerticalBounds(420, 460)).scrollBy(viewport))
    }

    @Test
    fun followsTheSentenceWhenItsParagraphExceedsTheAvailableReadingArea() {
        val target = TTSCenteringTarget(TTSVerticalBounds(-400, 1_600), TTSVerticalBounds(1_000, 1_080))
        assertEquals(540, target.scrollBy(TTSCenteringViewport(TTSVerticalBounds(100, 900), 500)))
    }

    @Test
    fun anOversizedSentenceKeepsItsBeginningReadableInsteadOfSkippingToItsMiddle() {
        val target = TTSCenteringTarget(TTSVerticalBounds(-400, 2_000), TTSVerticalBounds(1_000, 2_000))
        assertEquals(900, target.scrollBy(TTSCenteringViewport(TTSVerticalBounds(100, 900), 500)))
    }

    @Test
    fun bottomControlsAndUnequalToolbarsDoNotPullTheTargetAboveScreenCenter() {
        val target = TTSCenteringTarget(TTSVerticalBounds(400, 600), TTSVerticalBounds(420, 460))
        val viewport = TTSCenteringViewport(TTSVerticalBounds(100, 850), 500, TTSVerticalBounds(730, 810))
        assertEquals(0, target.scrollBy(viewport))
        assertEquals(0, target.scrollBy(viewport.copy(obstruction = TTSVerticalBounds(140, 220))))
        assertEquals(0, target.scrollBy(viewport.copy(obstruction = TTSVerticalBounds(950, 1_050))))
    }

    @Test
    fun controlsCoveringOtherSentencesDoNotMoveTheCenteredParagraph() {
        val paragraph = TTSVerticalBounds(300, 700)
        val viewport = TTSCenteringViewport(TTSVerticalBounds(0, 1_000), 500, TTSVerticalBounds(550, 650))
        assertEquals(0, TTSCenteringTarget(paragraph, TTSVerticalBounds(340, 380)).scrollBy(viewport))
        // Even a panel splitting the paragraph into two smaller regions must not force sentence centering.
        assertEquals(0, TTSCenteringTarget(TTSVerticalBounds(100, 900), TTSVerticalBounds(200, 240)).scrollBy(viewport))
    }

    @Test
    fun eachSpokenSentenceClearsControlsByTheSmallestNecessaryShift() {
        val paragraph = TTSVerticalBounds(300, 700)
        val viewport = TTSCenteringViewport(TTSVerticalBounds(0, 1_000), 500, TTSVerticalBounds(550, 650))
        assertEquals(0, TTSCenteringTarget(paragraph, TTSVerticalBounds(340, 380)).scrollBy(viewport))
        assertEquals(30, TTSCenteringTarget(paragraph, TTSVerticalBounds(540, 580)).scrollBy(viewport))
        assertEquals(-30, TTSCenteringTarget(paragraph, TTSVerticalBounds(620, 660)).scrollBy(viewport))
        // Once narration reaches clear text again, return to the paragraph's normal screen midpoint.
        assertEquals(0, TTSCenteringTarget(paragraph, TTSVerticalBounds(660, 700)).scrollBy(viewport))
    }

    @Test
    fun collisionIsCheckedAtTheSentencePositionAfterParagraphCentering() {
        val viewport = TTSCenteringViewport(TTSVerticalBounds(0, 1_000), 500, TTSVerticalBounds(550, 650))
        // Centering this paragraph moves its sentence from 240..280 to 540..580.
        val target = TTSCenteringTarget(TTSVerticalBounds(0, 400), TTSVerticalBounds(240, 280))
        assertEquals(-270, target.scrollBy(viewport))
        // A sentence initially under the panel can become clear through normal paragraph centering.
        assertEquals(300, TTSCenteringTarget(TTSVerticalBounds(600, 1_000), TTSVerticalBounds(620, 660)).scrollBy(viewport))
    }

    @Test
    fun anObstructedLongParagraphOnlyMovesEnoughToUncoverTheSpokenSentence() {
        val viewport = TTSCenteringViewport(TTSVerticalBounds(0, 1_000), 500, TTSVerticalBounds(450, 550))
        val target = TTSCenteringTarget(TTSVerticalBounds(0, 1_000), TTSVerticalBounds(580, 620))
        assertEquals(0, target.scrollBy(viewport))
        val oversized = target.copy(paragraph = TTSVerticalBounds(-400, 1_600))
        assertEquals(170, oversized.scrollBy(viewport))
    }

    @Test
    fun visibleToolbarsConstrainTextOnlyWhenItWouldOverlapThem() {
        val viewport = TTSCenteringViewport(TTSVerticalBounds(450, 900), 500)
        val target = TTSCenteringTarget(TTSVerticalBounds(400, 600), TTSVerticalBounds(420, 460))
        assertEquals(-50, target.scrollBy(viewport))
    }

    @Test
    fun aFullyObstructedViewportCannotProduceAJump() {
        val viewport = TTSCenteringViewport(TTSVerticalBounds(100, 900), 500, TTSVerticalBounds(0, 1_000))
        assertEquals(0, TTSCenteringTarget(TTSVerticalBounds(100, 300), TTSVerticalBounds(100, 140)).scrollBy(viewport))
        assertEquals(0 to 0, viewport.edgeSpaceWithin(TTSVerticalBounds(0, 1_000)))
    }

    @Test
    fun edgeSpaceAllowsFirstAndLastParagraphsToReachTheScreenCenterOrClearThePanel() {
        val reader = TTSVerticalBounds(0, 1_000)
        val viewport = TTSCenteringViewport(TTSVerticalBounds(100, 900), 500)
        assertEquals(500 to 500, viewport.edgeSpaceWithin(reader))
        assertEquals(400 to 600, viewport.edgeSpaceWithin(TTSVerticalBounds(100, 1_100)))
        assertEquals(550 to 550, viewport.copy(obstruction = TTSVerticalBounds(450, 550)).edgeSpaceWithin(reader))
        assertEquals(0 to 0, viewport.copy(bounds = TTSVerticalBounds(100, 100)).edgeSpaceWithin(reader))
    }

    @Test
    fun selectsOnlyTheSpokenChapterAndParagraphUsingExclusiveCharacterEnds() {
        val lines = listOf(
            TextVisualLine(0, 10, 1, 0, -200, -160),
            TextVisualLine(0, 10, 2, 0, 100, 140),
            TextVisualLine(10, 20, 2, 0, 140, 180),
            TextVisualLine(21, 40, 2, 1, 200, 240),
        )
        val target = ttsCenteringTarget(lines, TTSHelper.TTSLine("Sentence.", 10, 20, 2))!!
        assertEquals(TTSVerticalBounds(100, 180), target.paragraph)
        assertEquals(TTSVerticalBounds(140, 180), target.sentence)
    }

    @Test
    fun missingOrPartiallyLaidOutSentencesWaitForLayoutRatherThanCenteringWrongText() {
        val sentence = TTSHelper.TTSLine("Sentence.", 10, 30, 2)
        assertNull(ttsCenteringTarget(emptyList(), sentence))
        assertNull(ttsCenteringTarget(listOf(TextVisualLine(10, 20, 2, 0, 100, 140)), sentence))
        assertNull(ttsCenteringTarget(listOf(TextVisualLine(20, 30, 2, 0, 100, 140)), sentence))
        assertNull(ttsCenteringTarget(listOf(TextVisualLine(10, 30, 1, 0, 100, 140)), sentence))
    }
}
