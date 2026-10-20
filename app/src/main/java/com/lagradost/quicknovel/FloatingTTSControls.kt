package com.lagradost.quicknovel

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.lagradost.quicknovel.databinding.FloatingTtsControlsBinding

/** An in-reader overlay; playback and sentence selection remain owned by the ViewModel. */
internal class FloatingTTSControls(
    private val binding: FloatingTtsControlsBinding,
    initialPosition: FloatingControlsPosition,
    private val bounds: () -> FloatingControlsBounds,
    private val savePosition: (FloatingControlsPosition) -> Unit,
    togglePlayback: () -> Unit,
    previousSentence: () -> Unit,
    nextSentence: () -> Unit,
) {
    private var position = initialPosition
    private val panel = binding.root
    private val gesture = FloatingControlsGesture(ViewConfiguration.get(panel.context).scaledTouchSlop.toFloat())
    private var touchOwner: View? = null
    private val buttons = listOf(binding.previous, binding.pausePlay, binding.next)

    init {
        binding.pausePlay.setOnClickListener { if (canInteract()) togglePlayback() }
        binding.previous.setOnClickListener { if (canInteract()) previousSentence() }
        binding.next.setOnClickListener { if (canInteract()) nextSentence() }
        (buttons + panel).forEach { bindTouch(it) }
        panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reposition() }
        // Also offer a way to recover the default position without a touch drag.
        ViewCompat.addAccessibilityAction(
            binding.pausePlay,
            panel.context.getString(R.string.tts_reset_controls_position),
        ) { _, _ ->
            cancelGestures()
            position = FloatingControlsPosition()
            savePosition(position)
            reposition()
            true
        }
    }

    private fun canInteract() = panel.isShown && panel.hasWindowFocus()

    fun update(enabled: Boolean, status: TTSHelper.TTSStatus) {
        val visible = enabled && status != TTSHelper.TTSStatus.IsStopped
        if (!visible) cancelGestures()
        val changed = panel.isVisible != visible
        panel.isVisible = visible
        binding.pausePlay.setImageResource(
            if (status == TTSHelper.TTSStatus.IsRunning) R.drawable.ic_baseline_pause_24
            else R.drawable.ic_baseline_play_arrow_24
        )
        binding.pausePlay.setContentDescription(
            panel.context.getString(
                if (status == TTSHelper.TTSStatus.IsRunning) R.string.tts_pause_playback
                else R.string.tts_resume_playback
            )
        )
        if (changed && visible) panel.post { reposition() }
    }

    fun setColors(background: Int, foreground: Int) {
        panel.setCardBackgroundColor(ColorUtils.blendARGB(background, foreground, 0.06f))
        val tint = ColorStateList.valueOf(foreground)
        buttons.forEach { it.imageTintList = tint }
    }

    fun reposition() {
        if (!panel.isVisible || panel.width == 0 || panel.height == 0) return
        // Keep an in-progress drag stable; otherwise restore its saved relative placement.
        val (x, y) = if (gesture.isActive) bounds().constrain(panel.x, panel.y) else position.place(bounds())
        panel.x = x
        panel.y = y
    }

    fun watchContainerLayout(container: View, updatePosition: () -> Unit) {
        container.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            // Sentence/progress updates can lay out the same area again during a drag.
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                cancelGestures()
            }
            updatePosition()
        }
    }

    fun cancelGestures() {
        buttons.forEach { it.isPressed = false }
        touchOwner = null
        gesture.cancel()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindTouch(view: View) {
        // Every surface shares the same gesture, including the buttons. Raw coordinates
        // stay stable as the panel moves beneath the finger.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                cancelGestures()
                if (canInteract()) {
                    touchOwner = view
                    gesture.down(event.rawX, event.rawY, panel.x, panel.y)
                    view.isPressed = view !== panel
                }
                return@setOnTouchListener true
            }
            // A second touch can replace the first; ignore the older touch stream.
            if (touchOwner !== view) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE ->
                    gesture.move(event.rawX, event.rawY)?.let { (desiredX, desiredY) ->
                        buttons.forEach { it.isPressed = false }
                        val (x, y) = bounds().constrain(desiredX, desiredY)
                        panel.x = x
                        panel.y = y
                    }
                MotionEvent.ACTION_UP -> {
                    if (gesture.isDragging) {
                        position = FloatingControlsPosition.fromOffset(panel.x, panel.y, bounds())
                        savePosition(position)
                    }
                    val click = gesture.up()
                    cancelGestures()
                    if (click && view !== panel) view.performClick()
                }
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelGestures()
                    reposition()
                }
            }
            true
        }
    }
}
