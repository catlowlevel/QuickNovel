package com.lagradost.quicknovel

import android.content.Context
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.graphics.ColorUtils
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.lagradost.quicknovel.databinding.TtsOverrideEditBinding
import com.lagradost.quicknovel.databinding.TtsOverrideItemBinding
import com.lagradost.quicknovel.databinding.TtsOverrideListBinding
import com.lagradost.quicknovel.databinding.TtsOverridePreviewBinding
import com.lagradost.quicknovel.util.toPx
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute

internal class TTSOverrideEditor(
    private val context: Context,
    initialCollections: TTSOverrideCollections,
    private val save: (TTSOverrideCollections) -> Unit,
    private val currentSentence: () -> TTSHelper.TTSLine?,
    private val createPreviewOutput: () -> TTSPreviewOutput,
    private val pauseNarration: () -> Unit,
) {
    private class MatchHighlight(color: Int) : BackgroundColorSpan(color)
    private var collections = initialCollections.copy(local = initialCollections.local.toList(), global = initialCollections.global.toList())
    private var scope = TTSOverrideScope.Local
    private val rules: List<TTSWordOverride> get() = collections.rules(scope)
    private val inflater = LayoutInflater.from(context)
    private val binding = TtsOverrideListBinding.inflate(inflater)
    private val adapter = RuleAdapter()
    private val content = FrameLayout(context)
    private lateinit var dialog: AlertDialog
    private var backAction: (() -> Unit)? = null
    private var leavePage: (() -> Unit)? = null
    private var previewPlayback: TTSPreviewPlayback? = null

    private data class Action(val label: Int, val run: () -> Unit)

    private fun showPage(
        view: View,
        title: Int,
        positive: Action,
        negative: Action? = null,
        neutral: Action? = null,
        back: (() -> Unit)? = null,
        leave: (() -> Unit)? = null,
    ) {
        leavePage?.invoke()
        leavePage = leave
        backAction = back
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(dialog.currentFocus?.windowToken, 0)
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setTitle(title)
        listOf(AlertDialog.BUTTON_POSITIVE to positive, AlertDialog.BUTTON_NEGATIVE to negative,
            AlertDialog.BUTTON_NEUTRAL to neutral).forEach { (which, action) ->
            dialog.getButton(which).apply {
                isVisible = action != null
                isEnabled = true
                if (action != null) {
                    setText(action.label)
                    setOnClickListener { action.run() }
                }
            }
        }
        dialog.window?.apply {
            // The initial list has no input; later pages must still be able to open the keyboard.
            clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun show() {
        TTSOverrideScope.entries.forEach { binding.scopeTabs.addTab(binding.scopeTabs.newTab()) }
        binding.scopeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                scope = TTSOverrideScope.entries[tab.position]
                updateList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.rules.layoutManager = LinearLayoutManager(context)
        binding.rules.adapter = adapter
        binding.rules.layoutParams.height = (context.resources.displayMetrics.heightPixels / 2)
            .coerceAtMost(360.toPx).coerceAtLeast(96.toPx)
        updateList()
        dialog = AlertDialog.Builder(context)
            .setTitle(R.string.tts_pronunciation_overrides)
            .setView(content)
            .setPositiveButton(R.string.done, null)
            .setNegativeButton(R.string.tts_override_preview, null)
            .setNeutralButton(R.string.tts_override_add, null)
            .create()
        dialog.onBackPressedDispatcher.addCallback(dialog, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                backAction?.invoke() ?: dialog.dismiss()
            }
        })
        val lifecycle = (context as? LifecycleOwner)?.lifecycle
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { previewPlayback?.stop() }
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        lifecycle?.addObserver(observer)
        dialog.setOnDismissListener {
            lifecycle?.removeObserver(observer)
            leavePage?.invoke()
            leavePage = null
            backAction = null
        }
        dialog.show()
        showList()
    }

    private fun showList() {
        updateList()
        showPage(binding.root, R.string.tts_pronunciation_overrides,
            positive = Action(R.string.done) { dialog.dismiss() },
            negative = Action(R.string.tts_override_preview) { preview(collections.effectiveRules(), ::showList) },
            neutral = Action(R.string.tts_override_add) { editRule(null) },
        )
    }

    private fun updateList() {
        TTSOverrideScope.entries.forEach { entry ->
            binding.scopeTabs.getTabAt(entry.ordinal)?.text = context.getString(
                R.string.tts_override_scope_count, scopeName(entry), collections.rules(entry).size
            )
        }
        binding.description.setText(if (scope == TTSOverrideScope.Local) R.string.tts_override_local_description
            else R.string.tts_override_global_description)
        binding.empty.isVisible = rules.isEmpty()
        binding.rules.isVisible = rules.isNotEmpty()
        adapter.notifyDataSetChanged()
    }

    private fun scopeName(scope: TTSOverrideScope): String = context.getString(
        if (scope == TTSOverrideScope.Local) R.string.tts_override_local else R.string.tts_override_global
    )

    private fun commit(updated: List<TTSWordOverride>, target: TTSOverrideScope = scope) {
        commitCollections(collections.withRules(target, updated))
    }

    private fun commitCollections(updated: TTSOverrideCollections) {
        collections = updated
        save(collections)
        updateList()
    }

    private fun editRule(id: String?) {
        val editingScope = scope
        val sourceScope = editingScope.takeIf { id != null }
        val original = if (id == null) TTSWordOverride() else rules.firstOrNull { it.id == id } ?: return
        val fields = TtsOverrideEditBinding.inflate(inflater)
        fields.find.setText(original.find)
        fields.replacement.setText(original.replacement)
        fields.regex.isChecked = original.isRegex
        fields.caseSensitive.isChecked = original.caseSensitive
        fields.enabled.isChecked = original.enabled
        TTSOverrideScope.entries.forEach { entry ->
            fields.scope.addTab(fields.scope.newTab().setText(scopeName(entry)), entry == editingScope)
        }
        fun selectedScope() = TTSOverrideScope.entries[fields.scope.selectedTabPosition]
        fun updateScopeHint() {
            fields.scopeHint.setText(when {
                sourceScope == TTSOverrideScope.Global && selectedScope() == TTSOverrideScope.Local ->
                    R.string.tts_override_move_local_warning
                selectedScope() == TTSOverrideScope.Local -> R.string.tts_override_scope_local_hint
                else -> R.string.tts_override_scope_global_hint
            })
        }
        fields.scope.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) { updateScopeHint() }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        updateScopeHint()
        fun updateDuplicateWarning() {
            val draft = original.copy(find = fields.find.text.toString(), isRegex = fields.regex.isChecked,
                caseSensitive = fields.caseSensitive.isChecked)
            val duplicates = collections.duplicateScopes(draft, sourceScope)
            fields.duplicateWarning.isVisible = draft.find.isNotEmpty() && duplicates.isNotEmpty()
            fields.duplicateWarning.text = context.getString(
                R.string.tts_override_duplicate_warning, duplicates.joinToString(", ") { scopeName(it) }
            )
        }
        var helpExpanded = false
        fields.helpDetails.text = context.getString(R.string.tts_override_regex_replacement_hint) + "\n\n" +
            context.getString(R.string.tts_override_matching_hint)
        fun updateReplacementHint() {
            val regex = fields.regex.isChecked
            fields.findLabel.setText(if (regex) R.string.tts_override_pattern else R.string.tts_override_find)
            fields.replacementHint.setText(if (regex) R.string.tts_override_regex_hint else R.string.tts_override_replacement_hint)
            if (!regex) helpExpanded = false
            fields.helpToggle.isVisible = regex
            fields.helpDetails.isVisible = regex && helpExpanded
            fields.helpToggle.setText(if (helpExpanded) R.string.tts_override_hide_help else R.string.tts_override_show_help)
        }
        fields.helpToggle.setOnClickListener { helpExpanded = !helpExpanded; updateReplacementHint() }
        fields.regex.setOnCheckedChangeListener { _, _ -> updateReplacementHint(); updateDuplicateWarning() }
        fields.caseSensitive.setOnCheckedChangeListener { _, _ -> updateDuplicateWarning() }
        fields.find.doAfterTextChanged { updateDuplicateWarning() }
        updateReplacementHint()
        updateDuplicateWarning()
        fun readDraft(): TTSWordOverride? {
            val rule = original.copy(
                find = fields.find.text.toString(),
                replacement = fields.replacement.text.toString(),
                isRegex = fields.regex.isChecked,
                caseSensitive = fields.caseSensitive.isChecked,
                enabled = fields.enabled.isChecked,
            )
            val validation = rule.validationError()
            val error = when (validation) {
                TTSWordOverride.Error.EmptyFind -> R.string.tts_override_empty_find
                TTSWordOverride.Error.InvalidRegex -> R.string.tts_override_invalid_regex
                TTSWordOverride.Error.InvalidReplacement -> R.string.tts_override_invalid_replacement
                null -> null
            }
            fields.find.error = null
            fields.replacement.error = null
            if (error != null) {
                val field = if (validation == TTSWordOverride.Error.InvalidReplacement)
                    fields.replacement else fields.find
                field.error = context.getString(error)
                field.requestFocus()
                return null
            }
            return rule
        }
        // Keep these fields alive while previewing, including the unsaved scope and cursor.
        fun showEditor() {
            showPage(fields.root, if (id == null) R.string.tts_override_add else R.string.tts_override_edit,
                positive = Action(R.string.save) {
                    readDraft()?.let { rule ->
                        val destination = selectedScope()
                        commitCollections(collections.saveRule(rule, sourceScope, destination))
                        binding.scopeTabs.getTabAt(destination.ordinal)?.select()
                        showList()
                    }
                },
                negative = Action(R.string.cancel, ::showList),
                neutral = Action(R.string.tts_override_preview) {
                    readDraft()?.let { draft ->
                        preview(collections.saveRule(draft, sourceScope, selectedScope()).effectiveRules(), ::showEditor)
                    }
                },
                back = ::showList,
            )
        }
        showEditor()
        fields.find.requestFocus()
    }

    private fun preview(previewRules: List<TTSWordOverride>, returnTo: () -> Unit) {
        val fields = TtsOverridePreviewBinding.inflate(inflater)
        val engine = TTSOverrideEngine(previewRules)
        val matchColor = ColorUtils.setAlphaComponent(context.colorFromAttribute(android.R.attr.colorAccent), 80)
        var speechText = ""
        val playback = TTSPreviewPlayback(
            createOutput = createPreviewOutput,
            beforeRead = pauseNarration,
            changed = { reading ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                    setText(if (reading) R.string.stop else R.string.read)
                    isEnabled = reading || speechText.isNotBlank()
                }
            },
            failed = { Toast.makeText(context, R.string.tts_override_preview_failed, Toast.LENGTH_SHORT).show() },
        )
        // Take the current sentence when opening; later narration cannot overwrite test input.
        fields.input.setText(currentSentence()?.speakOutMsg.orEmpty())
        fun update() {
            playback.stop()
            val text = fields.input.text.toString()
            val preview = if (text.isEmpty()) TTSOverridePreview("", emptyList(), 0) else engine.preview(text)
            speechText = if (text.isEmpty()) "" else preview.text
            fields.input.text?.let { editable ->
                editable.getSpans(0, editable.length, MatchHighlight::class.java).forEach(editable::removeSpan)
                preview.matches.forEach { match ->
                    editable.setSpan(MatchHighlight(matchColor), match.start, match.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            fields.matchCount.text = context.resources.getQuantityString(
                R.plurals.tts_override_preview_match_count, preview.matchCount, preview.matchCount
            )
            fields.output.text = if (text.isEmpty()) context.getString(R.string.tts_override_preview_hint) else {
                speechText.takeUnless { it.isBlank() }
                    ?: context.getString(R.string.tts_override_preview_silent)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = speechText.isNotBlank()
        }
        fields.input.doAfterTextChanged { update() }
        previewPlayback = playback
        showPage(fields.root, R.string.tts_override_preview_title,
            positive = Action(R.string.read) {
                if (playback.isReading) playback.stop() else playback.read(speechText)
            },
            negative = Action(R.string.tts_override_back, returnTo),
            back = returnTo,
            leave = { playback.close(); previewPlayback = null },
        )
        update()
    }

    private fun showActions(anchor: View, id: String) {
        val index = rules.indexOfFirst { it.id == id }
        if (index < 0) return
        val rule = rules[index]
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, R.string.tts_override_edit)
            menu.add(0, 2, 1, R.string.tts_override_move_up).isEnabled = index > 0
            menu.add(0, 3, 2, R.string.tts_override_move_down).isEnabled = index < rules.lastIndex
            menu.add(0, 4, 3, R.string.delete)
            val toGlobal = scope == TTSOverrideScope.Local
            menu.add(0, 5, 4, if (toGlobal) R.string.tts_override_move_global else R.string.tts_override_move_local)
            menu.add(0, 6, 5, if (toGlobal) R.string.tts_override_copy_global else R.string.tts_override_copy_local)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> editRule(id)
                    2 -> move(id, -1)
                    3 -> move(id, 1)
                    5 -> transfer(id, copy = false)
                    6 -> transfer(id, copy = true)
                    4 -> AlertDialog.Builder(context)
                        .setTitle(R.string.tts_override_delete_title)
                        .setMessage(context.getString(R.string.tts_override_delete_message, rule.find))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.delete) { _, _ -> commit(rules.filterNot { it.id == id }) }
                        .show()
                }
                true
            }
            show()
        }
    }

    private fun transfer(id: String, copy: Boolean) {
        val source = scope
        val destination = if (source == TTSOverrideScope.Local) TTSOverrideScope.Global else TTSOverrideScope.Local
        val rule = rules.firstOrNull { it.id == id } ?: return
        val duplicate = collections.rules(destination).any { rule.hasSameMatch(it) }
        val messages = mutableListOf<String>()
        if (!copy && source == TTSOverrideScope.Global) messages += context.getString(R.string.tts_override_move_local_warning)
        if (duplicate) messages += context.getString(R.string.tts_override_transfer_duplicate, scopeName(destination))
        fun applyTransfer() {
            commitCollections(collections.transfer(id, source, copy))
            binding.scopeTabs.getTabAt(destination.ordinal)?.select()
        }
        if (messages.isEmpty()) applyTransfer() else AlertDialog.Builder(context)
            .setTitle(if (copy) R.string.tts_override_copy_title else R.string.tts_override_move_title)
            .setMessage(messages.joinToString("\n\n"))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> applyTransfer() }
            .show()
    }

    private fun move(id: String, offset: Int) {
        val index = rules.indexOfFirst { it.id == id }
        val destination = index + offset
        if (index < 0 || destination !in rules.indices) return
        commit(rules.toMutableList().apply { add(destination, removeAt(index)) })
    }

    private inner class RuleAdapter : RecyclerView.Adapter<RuleHolder>() {
        override fun getItemCount() = rules.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RuleHolder(TtsOverrideItemBinding.inflate(inflater, parent, false))

        override fun onBindViewHolder(holder: RuleHolder, position: Int) {
            val rule = rules[position]
            with(holder.binding) {
                find.text = rule.find
                replacement.text = if (rule.replacement.isEmpty()) context.getString(R.string.tts_override_remove_match)
                    else context.getString(R.string.tts_override_read_as, rule.replacement)
                options.text = listOfNotNull(
                    context.getString(if (rule.isRegex) R.string.tts_override_regex else R.string.tts_override_literal),
                    context.getString(if (rule.caseSensitive) R.string.tts_override_case_sensitive else R.string.tts_override_ignore_case),
                    if (scope == TTSOverrideScope.Global && collections.overridesGlobal(rule))
                        context.getString(R.string.tts_override_shadowed) else null,
                ).joinToString(" · ")
                val duplicates = collections.duplicateScopes(rule, scope)
                duplicateWarning.isVisible = duplicates.isNotEmpty()
                duplicateWarning.text = context.getString(
                    R.string.tts_override_duplicate_summary, duplicates.joinToString(", ") { scopeName(it) }
                )
                enabled.setOnCheckedChangeListener(null)
                enabled.isChecked = rule.enabled
                enabled.contentDescription = context.getString(R.string.tts_override_enable_rule, rule.find)
                enabled.setOnCheckedChangeListener { _, checked ->
                    commit(rules.map { if (it.id == rule.id) it.copy(enabled = checked) else it })
                }
                edit.setOnClickListener { editRule(rule.id) }
                actions.contentDescription = context.getString(R.string.tts_override_rule_actions, rule.find)
                actions.setOnClickListener { showActions(it, rule.id) }
            }
        }
    }

    private class RuleHolder(val binding: TtsOverrideItemBinding) : RecyclerView.ViewHolder(binding.root)
}
