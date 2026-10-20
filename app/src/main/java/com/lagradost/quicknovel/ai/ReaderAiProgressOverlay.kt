package com.lagradost.quicknovel.ai

import android.content.res.ColorStateList
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.ReaderAiProgressBinding
import java.text.NumberFormat

/** Only this card receives touches; the reader and its controls remain interactive. */
internal class ReaderAiProgressOverlay(
    private val binding: ReaderAiProgressBinding,
    onStop: () -> Unit,
    private val onVisibilityChanged: () -> Unit,
) {
    init {
        binding.stop.setOnClickListener { if (binding.root.isShown) onStop() }
    }

    fun render(state: AiSummaryState) {
        val running = state as? AiSummaryState.Running
        val waiting = state as? AiSummaryState.Waiting
        val ready = (state as? AiSummaryState.Ready)?.takeIf { it.continuous }
        val visible = running != null || waiting != null || ready != null
        val changed = binding.root.isVisible != visible
        binding.root.isVisible = visible
        val resources = binding.root.resources
        binding.progress.visibility = if (waiting == null) View.VISIBLE else View.INVISIBLE
        when {
            running != null -> {
                binding.chapter.text = if (running.continuous) resources.getString(R.string.ai_summary_continuous_progress,
                    running.completedChapters, running.plan.chapter.title) else running.plan.chapter.title
                binding.count.text = resources.getQuantityString(R.plurals.ai_generated_token_count,
                    running.generatedTokens.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                    NumberFormat.getIntegerInstance().format(running.generatedTokens))
            }
            waiting != null -> {
                binding.chapter.text = resources.getQuantityString(R.plurals.ai_summary_continuous_done,
                    waiting.completedChapters, waiting.completedChapters)
                binding.count.text = resources.getString(R.string.ai_summary_continuous_waiting, waiting.nextTitle)
            }
            ready != null -> {
                binding.chapter.text = ready.plan.chapter.title
                binding.count.text = resources.getQuantityString(R.plurals.ai_summary_continuous_done,
                    ready.completedChapters + 1, ready.completedChapters + 1)
            }
        }
        if (changed) {
            onVisibilityChanged()
            if (visible) binding.root.post(onVisibilityChanged)
        }
    }

    fun setColors(background: Int, foreground: Int) {
        binding.root.setCardBackgroundColor(ColorUtils.blendARGB(background, foreground, 0.06f))
        binding.chapter.setTextColor(foreground)
        binding.count.setTextColor(foreground)
        val tint = ColorStateList.valueOf(foreground)
        binding.progress.indeterminateTintList = tint
        binding.stop.imageTintList = tint
    }
}
