package com.lagradost.quicknovel.ui.common

import android.content.Context
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.graphics.ColorUtils
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.card.MaterialCardView
import com.lagradost.quicknovel.R

/** Compact request details shared by reader AI actions; the containing dialog owns its buttons. */
class AiRequestConfirmationView(context: Context) : LinearLayout(context) {
    init { orientation = VERTICAL }

    fun bind(chapter: String, configuration: String, estimate: String, customHost: String? = null,
             onConfigurationClick: (() -> Unit)? = null, configurationDetail: String? = null) {
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
        val description = listOfNotNull(configuration, configurationDetail).joinToString(" · ")
        if (onConfigurationClick == null) line(description) else {
            val density = resources.displayMetrics.density
            val textColor = TextView(context).currentTextColor
            val secondaryColor = ColorUtils.setAlphaComponent(textColor, 180)
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (64 * density).toInt()
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val labels = LinearLayout(context).apply { orientation = VERTICAL }
            fun profileLine(value: String, secondary: Boolean) {
                labels.addView(TextView(context).apply {
                    text = value
                    textSize = if (secondary) 14f else 16f
                    setTextColor(if (secondary) secondaryColor else textColor)
                    includeFontPadding = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    if (secondary) topMargin = (4 * density).toInt()
                })
            }
            profileLine(configuration, false)
            configurationDetail?.takeIf { it.isNotBlank() }?.let { profileLine(it, true) }
            row.addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(AppCompatImageView(context).apply {
                setImageResource(R.drawable.arrow_drop_down_24px)
                imageTintList = ColorStateList.valueOf(secondaryColor)
            }, LayoutParams((24 * density).toInt(), (24 * density).toInt()).apply {
                marginStart = (12 * density).toInt()
            })
            val selector = MaterialCardView(context).apply {
                radius = 8 * density
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(ColorUtils.setAlphaComponent(textColor, 10))
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.ai_summary_profile_choice, description)
                addView(row)
                setOnClickListener { onConfigurationClick() }
            }
            ViewCompat.setAccessibilityDelegate(selector, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            })
            addView(selector, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * density).toInt()
            })
        }
        line(estimate)
        if (customHost != null) line(customHost)
    }
}
