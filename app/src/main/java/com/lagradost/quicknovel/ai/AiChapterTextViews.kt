package com.lagradost.quicknovel.ai

data class AiOriginalPosition(val innerIndex: Int = 0, val character: Int = 0)

data class AiSummaryTextView<T>(
    val output: AiSummaryOutput,
    val content: T,
    val originalPosition: AiOriginalPosition,
    val showing: Boolean,
)

/** Reader-session presentations; source data and its saved reading position remain separate. */
class AiChapterTextViews<T> {
    private data class Key(val book: String, val chapter: String)
    private data class Entry<T>(val sourceText: String, val view: AiSummaryTextView<T>)
    private val entries = hashMapOf<Key, Entry<T>>()

    @Synchronized
    fun saved(source: AiChapter): AiSummaryTextView<T>? {
        val key = Key(source.bookId, source.chapterId)
        val entry = entries[key] ?: return null
        if (entry.sourceText != source.text) {
            entries.remove(key)
            return null
        }
        return entry.view
    }

    @Synchronized
    fun displayed(source: AiChapter): AiSummaryTextView<T>? = saved(source)?.takeIf { it.showing }

    @Synchronized
    fun show(source: AiChapter, output: AiSummaryOutput, content: T, position: AiOriginalPosition): AiSummaryTextView<T> {
        require(output.text.isNotBlank())
        val previous = saved(source)
        if (previous?.showing == true && previous.output == output) return previous
        val view = AiSummaryTextView(output, content,
            if (previous?.showing == true) previous.originalPosition else position, showing = true)
        entries[Key(source.bookId, source.chapterId)] = Entry(source.text, view)
        return view
    }

    @Synchronized
    fun restore(source: AiChapter): AiOriginalPosition? {
        val previous = displayed(source) ?: return null
        entries[Key(source.bookId, source.chapterId)] = Entry(source.text, previous.copy(showing = false))
        return previous.originalPosition
    }

    @Synchronized
    fun originalPosition(bookId: String, chapterId: String): AiOriginalPosition? =
        entries[Key(bookId, chapterId)]?.view?.takeIf { it.showing }?.originalPosition
}
