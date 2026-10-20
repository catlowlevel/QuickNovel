package com.lagradost.quicknovel.ai

import android.content.res.ColorStateList
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
        val changed = binding.root.isVisible != (running != null)
        binding.root.isVisible = running != null
        if (running != null) {
            binding.chapter.text = running.plan.chapter.title
            binding.count.text = binding.root.resources.getQuantityString(
                R.plurals.ai_generated_token_count, running.generatedTokens.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                NumberFormat.getIntegerInstance().format(running.generatedTokens))
        }
        if (changed) {
            onVisibilityChanged()
            if (running != null) binding.root.post(onVisibilityChanged)
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
