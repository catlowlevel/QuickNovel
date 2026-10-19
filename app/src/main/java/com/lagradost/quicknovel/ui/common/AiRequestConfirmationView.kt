package com.lagradost.quicknovel.ui.common

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.TextView

/** Compact request details shared by reader AI actions; the containing dialog owns its buttons. */
class AiRequestConfirmationView(context: Context) : LinearLayout(context) {
    init { orientation = VERTICAL }

    fun bind(chapter: String, configuration: String, estimate: String, customHost: String? = null) {
        removeAllViews()
        fun line(value: String, prominent: Boolean = false) {
            addView(TextView(context).apply {
                text = value
                textSize = if (prominent) 16f else 14f
                if (prominent) setTypeface(typeface, Typeface.BOLD)
                maxLines = if (prominent) 2 else 3
                ellipsize = TextUtils.TruncateAt.END
                includeFontPadding = false
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (childCount > 0) topMargin = (8 * resources.displayMetrics.density).toInt()
            })
        }
        line(chapter, prominent = true)
        line(configuration)
        line(estimate)
        if (customHost != null) line(customHost)
    }
}
