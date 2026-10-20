package com.lagradost.quicknovel

import com.lagradost.quicknovel.ui.TextVisualLine
import org.junit.Assert.*
import org.junit.Test

class ReaderChapterSelectionTest {
    private fun line(chapter: Int, top: Int, bottom: Int, character: Int = 0) =
        TextVisualLine(character, character + 10, chapter, 0, top, bottom)

    @Test
    fun selectsTheChapterAtScreenCenterInsteadOfTheFirstVisibleLine() {
        val previous = line(3, 100, 130)
        val current = line(4, 480, 520, 50)
        assertEquals(current, readerLineAtScreenCenter(listOf(previous, current), 500, 80, 900))
    }

    @Test
    fun doesNotSwitchJustBecauseTheNextChapterAppearsNearTheBottom() {
        val current = line(3, 480, 520)
        val next = line(4, 850, 880)
        assertEquals(current, readerLineAtScreenCenter(listOf(current, next), 500, 80, 900))
    }

    @Test
    fun switchesAsTheBoundaryCrossesTheCenterInEitherScrollDirection() {
        val above = listOf(line(3, 470, 490), line(4, 490, 510))
        val below = listOf(line(3, 490, 510), line(4, 510, 530))
        assertEquals(4, readerLineAtScreenCenter(above, 500, 80, 900)?.index)
        assertEquals(3, readerLineAtScreenCenter(below, 500, 80, 900)?.index)
        assertEquals(4, readerLineAtScreenCenter(listOf(line(3, 480, 500), line(4, 500, 520)), 500, 80, 900)?.index)
    }

    @Test
    fun asymmetricToolbarsDoNotMoveTheTargetAwayFromThePhysicalScreenCenter() {
        val atScreenCenter = line(3, 480, 520)
        val atReaderMidpoint = line(4, 680, 720)
        assertEquals(
            atScreenCenter,
            readerLineAtScreenCenter(listOf(atScreenCenter, atReaderMidpoint), 500, 300, 1_100),
        )
    }

    @Test
    fun gapsChooseTheNearestVisibleLineWithStableForwardTies() {
        val before = line(3, 400, 450)
        val after = line(4, 550, 600)
        assertEquals(before, readerLineAtScreenCenter(listOf(before, after), 480, 80, 900))
        assertEquals(after, readerLineAtScreenCenter(listOf(before, after), 520, 80, 900))
        assertEquals(after, readerLineAtScreenCenter(listOf(before, after), 500, 80, 900))
    }

    @Test
    fun clipsOffscreenRowsAndUsesNearestBodyTextAtBookEdges() {
        val offscreen = line(2, -100, -50)
        val first = line(3, 100, 120)
        val last = line(4, 140, 160)
        assertEquals(last, readerLineAtScreenCenter(listOf(offscreen, first, last), 500, 80, 900))
        assertEquals(first, readerLineAtScreenCenter(listOf(offscreen, first, last), 0, 80, 900))
    }

    @Test
    fun aTallImageOrParagraphCanRemainTheCentralChapter() {
        val tall = line(3, -200, 1_200)
        assertEquals(tall, readerLineAtScreenCenter(listOf(tall), 500, 80, 900))
    }

    @Test
    fun noVisibleGeometryOrCollapsedViewportHasNoSelection() {
        assertNull(readerLineAtScreenCenter(emptyList(), 500, 80, 900))
        assertNull(readerLineAtScreenCenter(listOf(line(3, 900, 930), line(4, 80, 80)), 500, 80, 900))
        assertNull(readerLineAtScreenCenter(listOf(line(3, 400, 600)), 500, 600, 600))
    }
}
