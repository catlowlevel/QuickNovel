package com.lagradost.quicknovel

import android.view.KeyEvent

/** Tracks keys owned by playback so repeats and releases cannot also activate focused views. */
internal class TTSKeyboardShortcuts {
    enum class Action { TogglePlayback, NextSentence, PreviousSentence }

    private val pressedKeys = mutableSetOf<Int>()

    fun keyDown(
        keyCode: Int,
        repeatCount: Int,
        enabled: Boolean,
        perform: (Action) -> Unit,
    ): Boolean {
        if (keyCode in pressedKeys) return true
        if (!enabled || repeatCount != 0) return false
        val action = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> Action.TogglePlayback
            KeyEvent.KEYCODE_DPAD_RIGHT -> Action.NextSentence
            KeyEvent.KEYCODE_DPAD_LEFT -> Action.PreviousSentence
            else -> return false
        }
        pressedKeys.add(keyCode)
        perform(action)
        return true
    }

    fun keyUp(keyCode: Int): Boolean = pressedKeys.remove(keyCode)

    fun clear() = pressedKeys.clear()
}
