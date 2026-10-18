package com.lagradost.quicknovel

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TTSKeyboardShortcutsTest {
    private val shortcuts = TTSKeyboardShortcuts()
    private val actions = mutableListOf<TTSKeyboardShortcuts.Action>()

    private fun press(key: Int, repeat: Int = 0, enabled: Boolean = true): Boolean =
        shortcuts.keyDown(key, repeat, enabled, actions::add)

    @Test
    fun spaceTogglesOncePerPressEvenIfPlaybackStateChangesDuringThePress() {
        assertTrue(press(KeyEvent.KEYCODE_SPACE))
        assertTrue(press(KeyEvent.KEYCODE_SPACE, repeat = 1, enabled = false))
        assertTrue(press(KeyEvent.KEYCODE_SPACE, repeat = 2))
        assertTrue(shortcuts.keyUp(KeyEvent.KEYCODE_SPACE))
        assertTrue(press(KeyEvent.KEYCODE_SPACE))
        assertEquals(List(2) { TTSKeyboardShortcuts.Action.TogglePlayback }, actions)
    }

    @Test
    fun arrowsNavigateOnceAndOwnTheirReleases() {
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1))
        assertTrue(shortcuts.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertFalse(shortcuts.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(shortcuts.keyUp(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(
            listOf(TTSKeyboardShortcuts.Action.NextSentence, TTSKeyboardShortcuts.Action.PreviousSentence),
            actions,
        )
    }

    @Test
    fun disabledShortcutsLeaveSpacesAndArrowsForEditingOrNormalNavigation() {
        for (key in listOf(KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            assertFalse(press(key, enabled = false))
            assertFalse(shortcuts.keyUp(key))
        }
        assertTrue(actions.isEmpty())
    }

    @Test
    fun holdingAKeyBeforeStartingPlaybackDoesNotActivateAShortcut() {
        assertFalse(press(KeyEvent.KEYCODE_SPACE, enabled = false))
        assertFalse(press(KeyEvent.KEYCODE_SPACE, repeat = 1))
        assertFalse(shortcuts.keyUp(KeyEvent.KEYCODE_SPACE))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun unrelatedKeysAndUnownedReleasesPassThrough() {
        assertFalse(press(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse(press(KeyEvent.KEYCODE_A))
        assertFalse(shortcuts.keyUp(KeyEvent.KEYCODE_SPACE))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun focusLossClearsOwnershipSoLostReleasesDoNotLeaveKeysStuck() {
        press(KeyEvent.KEYCODE_SPACE)
        shortcuts.clear()
        assertFalse(press(KeyEvent.KEYCODE_SPACE, repeat = 1))
        assertTrue(press(KeyEvent.KEYCODE_SPACE))
        assertEquals(2, actions.size)
    }
}
