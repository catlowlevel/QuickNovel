package com.lagradost.quicknovel.util

import java.text.BreakIterator
import java.util.Locale

/** Count word boundaries in displayed plain text, excluding punctuation, images, and emoji. */
internal fun countWords(text: CharSequence): Int {
    val plainText = text.toString()
    val words = BreakIterator.getWordInstance(Locale.ROOT)
    words.setText(plainText)
    var start = words.first()
    var end = words.next()
    var count = 0
    while (end != BreakIterator.DONE) {
        var position = start
        while (position < end) {
            val codePoint = Character.codePointAt(plainText, position)
            if (Character.isLetterOrDigit(codePoint)) {
                count++
                break
            }
            position += Character.charCount(codePoint)
        }
        start = end
        end = words.next()
    }
    return count
}
