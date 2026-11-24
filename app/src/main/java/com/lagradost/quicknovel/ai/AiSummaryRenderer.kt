package com.lagradost.quicknovel.ai

import android.content.Context
import android.text.Spanned
import com.lagradost.quicknovel.LiveChapterData
import com.lagradost.quicknovel.TTSHelper
import io.noties.markwon.Markwon
import io.noties.markwon.SoftBreakAddsNewLinePlugin

/** Derive reader styling and speech from the same text, keeping cached model output intact. */
internal class AiSummaryRenderer(context: Context) {
    private val markwon = Markwon.builder(context)
        .usePlugin(SoftBreakAddsNewLinePlugin.create())
        .build()

    fun render(text: String): Spanned = markwon.toMarkdown(text)

    fun chapter(source: LiveChapterData, output: AiSummaryOutput): LiveChapterData {
        val rendered = render(output.text)
        return source.copy(rendered = rendered, spans = TTSHelper.parseTextToSpans(rendered, source.index))
    }
}
