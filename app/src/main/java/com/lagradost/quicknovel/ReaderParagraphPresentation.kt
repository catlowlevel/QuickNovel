package com.lagradost.quicknovel

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import androidx.core.text.getSpans
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.ThematicBreakSpan

internal fun paragraphFormatPlan(text: Spanned): ParagraphFormatPlan {
    val protected =
        text
            .getSpans<Any>()
            .filter {
                it is HeadingSpan ||
                    it is LeadingMarginSpan ||
                    it is CodeBlockSpan ||
                    it is ThematicBreakSpan ||
                    it is ReplacementSpan
            }
            .map { text.getSpanStart(it) until text.getSpanEnd(it) }
    return ParagraphFormatting.plan(text.toString(), protected)
}

/** Editing the spannable in reverse preserves emphasis, links and image/style coordinates. */
internal fun ParagraphFormatPlan.apply(text: Spanned): Spanned =
    SpannableStringBuilder(text).apply {
        for (join in joins.asReversed()) replace(join.start, join.end, join.replacement)
    }
