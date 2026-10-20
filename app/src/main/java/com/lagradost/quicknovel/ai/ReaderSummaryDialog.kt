package com.lagradost.quicknovel.ai

import android.content.Intent
import android.graphics.Typeface
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.util.countWords
import com.lagradost.quicknovel.ui.settings.AiSettingsActivity
import com.lagradost.quicknovel.ui.common.AiRequestConfirmationView
import java.text.NumberFormat

/** Confirmation and recovery stay modal; generation progress belongs to the reader overlay. */
class ReaderSummaryDialog(private val activity: AppCompatActivity, private val controller: AiSummaryController) {
    private var dialog: AlertDialog? = null
    private var contentHost: FrameLayout? = null
    private var keyPicker: AlertDialog? = null

    private fun chooseKey() {
        val keys = controller.savedKeys
        if (keys.isEmpty() || keyPicker != null) return
        val labels = keys.map { key ->
            val name = key.name.trim().ifBlank { key.provider.displayName }
            val title = if (name.equals(key.provider.displayName, ignoreCase = true)) name
                else "$name · ${key.provider.displayName}"
            "$title\n${key.model}"
        }.toTypedArray()
        val selected = keys.indexOf(controller.selectedKey)
        val picker = AlertDialog.Builder(activity)
            .setTitle(R.string.ai_summary_choose_profile)
            .setSingleChoiceItems(labels, selected) { selection, index ->
                selection.dismiss()
                controller.selectKey(keys[index].provider, keys[index].id)
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.ai_provider_settings) { _, _ ->
                controller.close()
                activity.startActivity(Intent(activity, AiSettingsActivity::class.java))
            }.create()
        keyPicker = picker
        picker.setOnDismissListener { if (keyPicker === it) keyPicker = null }
        picker.show()
    }

    private fun dismissDialog() {
        keyPicker?.dismiss()
        keyPicker = null
        val previous = dialog
        dialog = null
        contentHost = null
        // Programmatic dismissal must not cancel a request or close a later popup.
        previous?.setOnDismissListener(null)
        previous?.dismiss()
    }

    fun dismissForDestroy() = dismissDialog()

    fun render(state: AiSummaryState) {
        if (state == AiSummaryState.Closed || state is AiSummaryState.Running ||
            state is AiSummaryState.Ready || state is AiSummaryState.Waiting || state is AiSummaryState.Finished) {
            dismissDialog()
            return
        }
        // AlertDialog retains its custom view after dismissal; every new dialog owns a new host.
        val host = contentHost ?: FrameLayout(activity).also { contentHost = it }
        val current = dialog ?: AlertDialog.Builder(activity)
            .setTitle(R.string.ai_summary_action)
            .setView(host)
            .setPositiveButton(R.string.ai_summary_generate, null)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.ai_provider_settings, null).create().also {
                dialog = it
                it.setOnDismissListener { dismissed ->
                    if (dialog === dismissed) {
                        dialog = null
                        contentHost = null
                        controller.close()
                    }
                }
                it.show()
            }
        val density = activity.resources.displayMetrics.density
        val inset = (24 * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(inset, (16 * density).toInt(), inset, (16 * density).toInt())
        }
        fun text(value: String, prominent: Boolean = false,
                 secondary: Boolean = false, lines: Int = Int.MAX_VALUE) {
            val label = TextView(activity).apply {
                this.text = value
                textSize = if (secondary) 14f else 16f
                if (prominent) setTypeface(typeface, Typeface.BOLD)
                includeFontPadding = false
                maxLines = lines
                ellipsize = TextUtils.TruncateAt.END
            }
            content.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (content.childCount > 0) topMargin = (12 * density).toInt()
            })
        }
        fun words(value: String): String {
            val count = countWords(value)
            return activity.resources.getQuantityString(R.plurals.word_count, count, count)
        }
        fun continuousToggle(enabled: Boolean) {
            if (!controller.canContinue) return
            content.addView(CheckBox(activity).apply {
                setText(R.string.ai_summary_continuous)
                textSize = 14f
                isChecked = enabled
                setOnCheckedChangeListener { _, checked -> controller.setContinuous(checked) }
            })
        }
        fun button(which: Int, title: Int?, action: () -> Unit = {}) {
            current.getButton(which).apply {
                isVisible = title != null
                if (title != null) { setText(title); setOnClickListener { action() } }
            }
        }
        current.setTitle(R.string.ai_summary_action)
        button(AlertDialog.BUTTON_POSITIVE, null)
        button(AlertDialog.BUTTON_NEUTRAL, null)
        button(AlertDialog.BUTTON_NEGATIVE, R.string.cancel, controller::close)
        when (state) {
            is AiSummaryState.Preparing -> text(activity.getString(R.string.ai_summary_preparing))
            is AiSummaryState.Saved -> {
                val saved = state.result
                val plan = saved.plan
                val name = plan.profileName.trim().ifBlank { plan.provider.displayName }
                text(plan.chapter.title, prominent = true, lines = 2)
                text(activity.getString(R.string.ai_summary_saved_configuration, "$name · ${plan.config.model}"),
                    secondary = true, lines = 3)
                text(activity.getString(R.string.ai_summary_saved_length, words(saved.output.text), words(plan.chapter.text)),
                    secondary = true)
                if (saved.output.truncated) text(activity.getString(R.string.ai_summary_truncated), secondary = true)
                text(activity.getString(R.string.ai_summary_preview), prominent = true, secondary = true)
                text(saved.output.text, secondary = true, lines = 4)
                continuousToggle(state.continuous)
                button(AlertDialog.BUTTON_POSITIVE,
                    if (state.continuous) R.string.ai_summary_continue else R.string.ai_summary_reuse, controller::reuse)
                button(AlertDialog.BUTTON_NEUTRAL, R.string.ai_summary_regenerate, controller::regenerate)
            }
            is AiSummaryState.Confirm -> {
                val plan = state.plan
                val host = aiApiBaseUrlOrNull(plan.config.baseUrl.ifEmpty { plan.provider.apiUrl })!!.host
                val defaultHost = aiApiBaseUrlOrNull(plan.provider.apiUrl)!!.host
                val name = plan.profileName.trim().ifBlank { plan.provider.displayName }
                content.addView(AiRequestConfirmationView(activity).apply {
                    val estimate = if (state.continuous) activity.resources.getQuantityString(
                        R.plurals.ai_summary_continuous_estimate, state.loadedCount, state.loadedCount,
                        NumberFormat.getIntegerInstance().format(state.knownInputTokens))
                    else activity.getString(R.string.chapter_word_count_format, words(plan.chapter.text),
                        activity.getString(R.string.ai_request_token_estimate, NumberFormat.getIntegerInstance().format(plan.inputTokens)))
                    bind(plan.chapter.title, name, estimate, host.takeUnless { it == defaultHost },
                        onConfigurationClick = if (controller.savedKeys.isEmpty()) null else ::chooseKey,
                        configurationDetail = plan.config.model)
                })
                continuousToggle(state.continuous)
                if (state.continuous) text(activity.getString(
                    if (state.regenerating) R.string.ai_summary_continuous_regenerate_scope else R.string.ai_summary_continuous_scope), secondary = true)
                button(AlertDialog.BUTTON_POSITIVE, when {
                    state.continuous -> R.string.ai_summary_start_continuous
                    state.regenerating -> R.string.ai_summary_regenerate
                    else -> R.string.ai_summary_generate
                }, controller::generate)
            }
            is AiSummaryState.Running, is AiSummaryState.Ready,
            is AiSummaryState.Waiting, is AiSummaryState.Finished -> Unit
            is AiSummaryState.Failed -> {
                text(state.chapter.title)
                if (state.continuous) text(activity.getString(R.string.ai_summary_continuous_stopped), secondary = true)
                text(activity.getString(state.reason.messageRes()))
                if (controller.savedKeys.isNotEmpty()) content.addView(MaterialButton(activity, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    setText(R.string.ai_summary_choose_profile)
                    isAllCaps = false
                    setOnClickListener { chooseKey() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = (12 * density).toInt()
                })
                button(AlertDialog.BUTTON_POSITIVE, R.string.ai_summary_retry, controller::retry)
                button(AlertDialog.BUTTON_NEUTRAL, R.string.ai_provider_settings) {
                    controller.close()
                    activity.startActivity(Intent(activity, AiSettingsActivity::class.java))
                }
            }
            AiSummaryState.Closed -> Unit
        }
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val limit = (resources.displayMetrics.heightPixels * .65f).toInt()
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) limit
                    else minOf(limit, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply {
            addView(content)
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        host.removeAllViews()
        host.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}
