package com.lagradost.quicknovel.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.quicknovel.APIRepository.Companion.providersActive
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.LogcatBinding
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.safe
import com.lagradost.quicknovel.ui.clear
import com.lagradost.quicknovel.ui.download.AnyAdapter
import com.lagradost.quicknovel.ui.history.HistoryAdapter
import com.lagradost.quicknovel.ui.txt
import com.lagradost.quicknovel.util.Apis.Companion.apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiProviderLangSettings
import com.lagradost.quicknovel.util.Apis.Companion.getApiSettings
import com.lagradost.quicknovel.util.BackupUtils.setupStream
import com.lagradost.quicknovel.util.BackupUtils
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.InAppUpdater.Companion.runAutoUpdate
import com.lagradost.quicknovel.util.SingleSelectionHelper.showBottomDialog
import com.lagradost.quicknovel.util.SingleSelectionHelper.showDialog
import com.lagradost.quicknovel.util.SingleSelectionHelper.showMultiDialog
import com.lagradost.quicknovel.util.SubtitleHelper
import com.lagradost.quicknovel.util.UIHelper.clipboardHelper
import com.lagradost.quicknovel.util.UIHelper.dismissSafe
import com.lagradost.safefile.MediaFileContentType
import com.lagradost.safefile.SafeFile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.System.currentTimeMillis
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

import com.lagradost.quicknovel.databinding.DialogAiSettingsBinding
import com.lagradost.quicknovel.AiProviderType
import com.lagradost.quicknovel.AiSettings
import com.lagradost.quicknovel.AiSettingsProfile
import com.lagradost.quicknovel.AiSettingsProfiles
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.setKey
import com.lagradost.quicknovel.EPUB_AI_SETTINGS
import com.lagradost.quicknovel.EPUB_AI_SETTINGS_PROFILES
import com.lagradost.quicknovel.ai.AiManager
import androidx.core.view.isVisible
import com.lagradost.quicknovel.util.UIHelper.dismissSafe
import com.lagradost.quicknovel.util.UIHelper.popupMenu

class SettingsFragment : PreferenceFragmentCompat() {
    private fun PreferenceFragmentCompat?.getPref(id: Int): Preference? {
        if (this == null) return null

        return try {
            findPreference(getString(id))
        } catch (e: Exception) {
            logError(e)
            null
        }
    }


    companion object {
        fun getCurrentLocale(context: Context): String {
            val res = context.resources
            val conf = res.configuration

            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                conf?.locales?.get(0)?.toString() ?: "en"
            } else {
                @Suppress("DEPRECATION")
                conf?.locale?.toString() ?: "en"
            }
        }

        // idk, if you find a way of automating this it would be great
        // https://www.iemoji.com/view/emoji/1794/flags/antarctica
        // Emoji Character Encoding Data --> C/C++/Java Src
        // https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes leave blank for auto
        val appLanguages = arrayListOf(
            /* begin language list */
            Triple("", "English", "en"),
            Triple("", "Türkçe", "tr"),
            Triple("", "Español", "es"),
            Triple("", "Русский", "ru"),
            /* end language list */
        ).sortedBy { it.second.lowercase() } //ye, we go alphabetical, so ppl don't put their lang on top

        fun showSearchProviders(context: Context?) {
            if (context == null) return
            val apiNames = apis.map { it.name }
            val displayNames = apis.map {
                val flag = SubtitleHelper.getFlagFromIso(it.lang) ?: "🌐"
                "$flag ${it.name}"
            }
            context.apply {
                val active = getApiSettings()
                showMultiDialog(
                    displayNames,
                    apiNames.mapIndexed { index, s -> index to active.contains(s) }
                        .filter { it.second }
                        .map { it.first }.toList(),
                    getString(R.string.search_providers),
                    {}) { list ->
                    val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
                    settingsManager.edit {
                        putStringSet(
                            getString(R.string.search_providers_list_key),
                            list.map { apiNames[it] }.toSet()
                        )
                    }
                    providersActive = getApiSettings()
                }
            }
        }

        fun getDefaultDir(context: Context): SafeFile? {
            // See https://www.py4u.net/discuss/614761
            return SafeFile.fromMedia(
                context, MediaFileContentType.Downloads
            )?.gotoDirectory("Epub")
        }

        /**
         * Turns a string to an UniFile. Used for stored string paths such as settings.
         * Should only be used to get a download path.
         * */
        private fun basePathToFile(context: Context, path: String?): SafeFile? {
            return when {
                path.isNullOrBlank() -> getDefaultDir(context)
                path.startsWith("content://") -> SafeFile.fromUri(context, path.toUri())
                else -> SafeFile.fromFilePath(
                    context,
                    path.removePrefix(Environment.getExternalStorageDirectory().path).removePrefix(
                        File.separator
                    ).removeSuffix(File.separator) + File.separator
                )
            }
        }


        /**
         * Base path where downloaded things should be stored, changes depending on settings.
         * Returns the file and a string to be stored for future file retrieval.
         * UniFile.filePath is not sufficient for storage.
         * */
        fun Context.getBasePath(): Pair<SafeFile?, String?> {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
            val basePathSetting =
                settingsManager.getString(getString(R.string.download_path_key), null)
            return basePathToFile(this, basePathSetting) to basePathSetting
        }

        fun getDownloadDirs(context: Context?): List<String> {
            return safe {
                context?.let { ctx ->
                    val defaultDir = getDefaultDir(ctx)?.filePath()

                    val first = listOf(defaultDir)
                    (try {
                        val currentDir = ctx.getBasePath().let { it.first?.filePath() ?: it.second }

                        (first +
                                ctx.getExternalFilesDirs("").mapNotNull { it.path } +
                                currentDir)
                    } catch (e: Exception) {
                        first
                    }).filterNotNull().distinct()
                }
            } ?: emptyList()
        }
    }

    // Open file picker
    private val pathPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            // It lies, it can be null if file manager quits.
            if (uri == null) return@registerForActivityResult
            val context = context ?: return@registerForActivityResult
            // RW perms for the path
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            context.contentResolver.takePersistableUriPermission(uri, flags)

            val file = SafeFile.fromUri(context, uri)
            val filePath = file?.filePath()
            println("Selected URI path: $uri - Full path: $filePath")

            // Stores the real URI using download_path_key
            // Important that the URI is stored instead of filepath due to permissions.
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit { putString(getString(R.string.download_path_key), uri.toString()) }

            // From URI -> File path
            // File path here is purely for cosmetic purposes in settings
            (filePath ?: uri.toString()).let {
                PreferenceManager.getDefaultSharedPreferences(context)
                    .edit { putString(getString(R.string.download_path_pref), it) }
            }
        }

    private val restoreFileSelector =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val context = context ?: return@registerForActivityResult
            lifecycleScope.launch {
                BackupUtils.restoreFromUri(context, uri).onFailure { error ->
                    logError(error)
                    showToast(txt(R.string.restore_failed_format, error.toString()))
                }.onSuccess {
                    showToast(R.string.restore_success, Toast.LENGTH_LONG)
                    activity?.recreate()
                }
            }
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(requireContext())

        val multiPreference = getPref(R.string.search_providers_list_key)

        val updatePrefrence =
            findPreference<Preference>(getString(R.string.manual_check_update_key))!!
        val providerLangPreference =
            findPreference<Preference>(getString(R.string.provider_lang_key))!!

        multiPreference?.setOnPreferenceClickListener {
            showSearchProviders(activity)
            return@setOnPreferenceClickListener true
        }

        /*multiPreference.entries = apiNames.toTypedArray()
        multiPreference.entryValues = apiNames.toTypedArray()

        multiPreference.setOnPreferenceChangeListener { _, newValue ->
            (newValue as HashSet<String>?)?.let {
                providersActive = it
            }
            return@setOnPreferenceChangeListener true
        }*/

        getPref(R.string.locale_key)?.setOnPreferenceClickListener { pref ->
            val tempLangs = appLanguages.toMutableList()
            val current = getCurrentLocale(pref.context)
            val languageCodes = tempLangs.map { (_, _, iso) -> iso }
            val languageNames = tempLangs.map { (emoji, name, iso) ->
                val flag = emoji.ifBlank { SubtitleHelper.getFlagFromIso(iso) ?: "ERROR" }
                "$flag $name"
            }
            val index = languageCodes.indexOf(current)

            activity?.showDialog(
                languageNames, index, getString(R.string.provider_lang_settings), true, { }
            ) { languageIndex ->
                try {
                    val code = languageCodes[languageIndex]
                    CommonActivity.setLocale(activity, code)
                    settingsManager.edit { putString(getString(R.string.locale_key), code) }
                    activity?.recreate()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.download_delay_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.download_delay_names)
            val prefValues = resources.getStringArray(R.array.download_delay_values)

            val current =
                settingsManager.getString(getString(R.string.download_delay_key), prefValues.first())

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.download_delay),
                true,
                {}) {
                try {
                    settingsManager.edit {
                        putString(getString(R.string.download_delay_key), prefValues[it])
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.backup_key)?.setOnPreferenceClickListener {
            val activity = activity
            val context = context
            if (context != null) {
                lifecycleScope.launch {
                    BackupUtils.createBackupFile(context, activity).onFailure { error ->
                        logError(error)
                        if (error.message != null) {
                            showToast(
                                txt(R.string.backup_failed_error_format, error.toString()),
                                Toast.LENGTH_LONG
                            )
                        } else {
                            showToast(R.string.backup_failed, Toast.LENGTH_LONG)
                        }
                    }.onSuccess {
                        showToast(R.string.backup_success, Toast.LENGTH_LONG)
                    }
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.restore_key)?.setOnPreferenceClickListener {
            restoreFileSelector.launch(
                arrayOf(
                    "text/plain",
                    "text/str",
                    "text/x-unknown",
                    "application/json",
                    "unknown/unknown",
                    "content/unknown",
                )
            )
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.download_path_key)?.setOnPreferenceClickListener {
            val dirs = getDownloadDirs(context)

            val currentDir =
                settingsManager.getString(getString(R.string.download_path_pref), null)
                    ?: context?.let { ctx -> getDefaultDir(ctx)?.filePath() }

            activity?.showBottomDialog(
                dirs + listOf("Custom"),
                dirs.indexOf(currentDir),
                getString(R.string.download_path_pref),
                true,
                {}) {
                // Last = custom
                if (it == dirs.size) {
                    try {
                        pathPicker.launch(Uri.EMPTY)
                    } catch (e: Exception) {
                        logError(e)
                    }
                } else {
                    // Sets both visual and actual paths.
                    // key = used path
                    // pref = visual path
                    settingsManager.edit {
                        putString(getString(R.string.download_path_key), dirs[it])
                        putString(getString(R.string.download_path_pref), dirs[it])
                    }
                }
            }
            return@setOnPreferenceClickListener true
        }

        updatePrefrence.setOnPreferenceClickListener {
            ioSafe {
                if (true != activity?.runAutoUpdate(false)) {
                    showToast(R.string.no_update_found, Toast.LENGTH_SHORT)
                }
            }
            return@setOnPreferenceClickListener true
        }

        providerLangPreference.setOnPreferenceClickListener {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(it.context)

            activity?.getApiProviderLangSettings()?.let { current ->
                val allLangs = HashSet<String>()
                for (api in apis) {
                    allLangs.add(api.lang)
                }

                val currentList = ArrayList<Int>()
                for (i in current) {
                    currentList.add(allLangs.indexOf(i))
                }

                val names = allLangs.mapNotNull {
                    val fullName = SubtitleHelper.fromTwoLettersToLanguage(it)
                    if (fullName.isNullOrEmpty()) {
                        return@mapNotNull null
                    }
                    val flag = SubtitleHelper.getFlagFromIso(it) ?: "🌐"
                    it to "$flag $fullName"
                }

                context?.showMultiDialog(
                    names.map { it.second },
                    currentList,
                    getString(R.string.provider_lang_settings),
                    {}) { selectedList ->
                    settingsManager.edit {
                        putStringSet(
                            getString(R.string.provider_lang_key),
                            selectedList.map { names[it].first }.toMutableSet()
                        )
                    }

                    providersActive = it.context.getApiSettings()
                }
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.show_logcat_key)?.setOnPreferenceClickListener { pref ->
            val builder = AlertDialog.Builder(pref.context, R.style.AlertDialogCustom)

            val binding = LogcatBinding.inflate(layoutInflater, null, false)
            builder.setView(binding.root)

            val dialog = builder.create()
            dialog.show()

            val logList = mutableListOf<String>()
            try {
                // https://developer.android.com/studio/command-line/logcat
                val process = Runtime.getRuntime().exec("logcat -d")
                val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                bufferedReader.lineSequence().forEach { logList.add(it) }
            } catch (e: Exception) {
                logError(e) // kinda ironic
            }

            val adapter = LogcatAdapter().apply { submitList(logList) }
            binding.logcatRecyclerView.layoutManager = LinearLayoutManager(pref.context)
            binding.logcatRecyclerView.adapter = adapter

            binding.copyBtt.setOnClickListener {
                clipboardHelper(txt("Logcat"), logList.joinToString("\n"))
                dialog.dismissSafe(activity)
            }

            binding.clearBtt.setOnClickListener {
                Runtime.getRuntime().exec("logcat -c")
                dialog.dismissSafe(activity)
            }

            binding.saveBtt.setOnClickListener {
                val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.getDefault()).format(
                    Date(currentTimeMillis())
                )
                var fileStream: OutputStream?
                try {
                    fileStream = setupStream(
                        it.context,
                        "logcat_${date}",
                        "txt",
                        getDefaultDir(context = it.context)
                    ) ?: throw ErrorLoadingException("No stream")

                    fileStream.writer().use { writer -> writer.write(logList.joinToString("\n")) }
                    dialog.dismissSafe(activity)
                } catch (t: Throwable) {
                    logError(t)
                    showToast(t.message)
                }
            }

            binding.closeBtt.setOnClickListener {
                dialog.dismissSafe(activity)
            }

            return@setOnPreferenceClickListener true
        }

        getPref(R.string.theme_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.themes_names).toMutableList()
            val prefValues = resources.getStringArray(R.array.themes_names_values).toMutableList()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { // remove monet on android 11 and less
                val toRemove = prefValues
                    .mapIndexed { idx, s -> if (s.startsWith("Monet")) idx else null }
                    .filterNotNull()
                var offset = 0
                toRemove.forEach { idx ->
                    prefNames.removeAt(idx - offset)
                    prefValues.removeAt(idx - offset)
                    offset += 1
                }
            }

            val currentLayout =
                settingsManager.getString(getString(R.string.theme_key), prefValues.first())

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(currentLayout),
                getString(R.string.theme),
                false,
                {}) {
                try {
                    //AnyAdapter.sharedPool.clear()
                    //HistoryAdapter.sharedPool.clear()

                    settingsManager.edit {
                        putString(getString(R.string.theme_key), prefValues[it])
                    }
                    activity?.recreate()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.primary_color_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.themes_overlay_names).toMutableList()
            val prefValues =
                resources.getStringArray(R.array.themes_overlay_names_values).toMutableList()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { // remove monet on android 11 and less
                val toRemove = prefValues
                    .mapIndexed { idx, s -> if (s.startsWith("Monet")) idx else null }
                    .filterNotNull()
                var offset = 0
                toRemove.forEach { idx ->
                    prefNames.removeAt(idx - offset)
                    prefValues.removeAt(idx - offset)
                    offset += 1
                }
            }

            val currentLayout =
                settingsManager.getString(getString(R.string.primary_color_key), prefValues.first())

            activity?.showDialog(
                prefNames.toList(),
                prefValues.indexOf(currentLayout),
                getString(R.string.primary_color_settings),
                true,
                {}) {
                try {
                    settingsManager.edit {
                        putString(getString(R.string.primary_color_key), prefValues[it])
                    }
                    activity?.recreate()
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        findPreference<Preference>("ai_settings_pref")?.setOnPreferenceClickListener {
            val context = it.context
            val builder = AlertDialog.Builder(context, R.style.AlertDialogCustom)
            val binding = DialogAiSettingsBinding.inflate(layoutInflater, null, false)
            builder.setView(binding.root)

            var currentSettings = context.getKey<AiSettings>(EPUB_AI_SETTINGS) ?: AiSettings()
            val storedProfiles = context.getKey<AiSettingsProfiles>(EPUB_AI_SETTINGS_PROFILES)
            var profiles = storedProfiles?.profiles?.toMutableList() ?: mutableListOf()
            if (profiles.isEmpty()) {
                profiles += AiSettingsProfile(context.getString(R.string.default_text), currentSettings)
            }
            var selectedProfileName = profiles.firstOrNull { it.settings == currentSettings }?.name
                ?: storedProfiles?.selectedName?.takeIf { name -> profiles.any { it.name == name } }
                ?: profiles.first().name

            fun readSettingsFromInputs(): AiSettings {
                return currentSettings.copy(
                    apiKey = binding.aiApiKeyInput.text.toString(),
                    model = binding.aiModelInput.text.toString(),
                    targetLanguage = binding.aiTargetLanguageInput.text.toString(),
                    customUrl = binding.aiCustomUrlInput.text.toString()
                )
            }

            fun saveProfiles() {
                context.setKey(
                    EPUB_AI_SETTINGS_PROFILES,
                    AiSettingsProfiles(
                        selectedName = selectedProfileName,
                        profiles = profiles.sortedBy { profile -> profile.name.lowercase(Locale.ROOT) }
                    )
                )
            }

            fun upsertCurrentProfile(showSavedToast: Boolean): Boolean {
                val name = binding.aiProfileNameInput.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    showToast(R.string.ai_token_setting_name_required)
                    return false
                }

                currentSettings = readSettingsFromInputs()
                val existing = profiles.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                val profile = AiSettingsProfile(name, currentSettings)
                if (existing == -1) {
                    profiles += profile
                } else {
                    profiles[existing] = profile
                }
                selectedProfileName = name
                saveProfiles()
                if (showSavedToast) showToast(R.string.ai_token_setting_saved)
                return true
            }

            fun updateUi() {
                binding.aiProfileSelect.text = selectedProfileName
                binding.aiProfileNameInput.setText(selectedProfileName)
                binding.aiProviderSelect.text = currentSettings.providerType.name
                binding.aiApiKeyInput.setText(currentSettings.apiKey)
                binding.aiModelInput.setText(currentSettings.model)
                binding.aiTargetLanguageInput.setText(currentSettings.targetLanguage)
                binding.aiCustomUrlInput.setText(currentSettings.customUrl)
                binding.aiCustomUrlLayout.isVisible = true
            }

            updateUi()

            binding.aiProfileSelect.setOnClickListener { view ->
                view.popupMenu(
                    profiles.mapIndexed { index, profile -> index to txt(profile.name) },
                    selectedItemId = profiles.indexOfFirst { it.name == selectedProfileName }.takeIf { idx -> idx >= 0 }
                ) {
                    val profile = profiles.getOrNull(itemId) ?: return@popupMenu
                    selectedProfileName = profile.name
                    currentSettings = profile.settings
                    updateUi()
                }
            }

            binding.aiSaveProfile.setOnClickListener {
                if (upsertCurrentProfile(showSavedToast = true)) {
                    updateUi()
                }
            }

            binding.aiDeleteProfile.setOnClickListener {
                if (profiles.size <= 1) {
                    showToast(R.string.ai_token_setting_keep_one)
                    return@setOnClickListener
                }
                val name = binding.aiProfileNameInput.text?.toString()?.trim().orEmpty()
                val index = profiles.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                if (index == -1) {
                    showToast(R.string.ai_token_setting_not_found)
                    return@setOnClickListener
                }
                AlertDialog.Builder(context, R.style.AlertDialogCustom)
                    .setTitle(R.string.delete)
                    .setMessage(context.getString(R.string.ai_token_setting_delete_confirm, profiles[index].name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        profiles.removeAt(index)
                        val replacement = profiles.first()
                        selectedProfileName = replacement.name
                        currentSettings = replacement.settings
                        saveProfiles()
                        updateUi()
                    }
                    .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
                    .show()
            }

            binding.aiProviderSelect.setOnClickListener { view ->
                view.popupMenu(
                    AiProviderType.entries.map { it.ordinal to txt(it.name) },
                    selectedItemId = currentSettings.providerType.ordinal
                ) {
                    currentSettings = currentSettings.copy(providerType = AiProviderType.entries[itemId])
                    updateUi()
                }
            }

            binding.aiFetchModels.setOnClickListener {
                val tempSettings = readSettingsFromInputs()
                val provider = AiManager.getProvider(tempSettings)
                if (provider == null) {
                    showToast(R.string.ai_provider_not_configured)
                    return@setOnClickListener
                }
                ioSafe {
                    try {
                        val models = provider.getModels()
                        activity?.runOnUiThread {
                            binding.root.popupMenu(
                                models.mapIndexed { index, s -> index to txt(s) },
                                selectedItemId = null
                            ) {
                                binding.aiModelInput.setText(models[itemId])
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                        showToast(e.message)
                    }
                }
            }

            binding.aiTestConnection.setOnClickListener {
                val tempSettings = readSettingsFromInputs()
                val provider = AiManager.getProvider(tempSettings)
                if (provider == null) {
                    showToast(R.string.ai_provider_not_configured)
                    return@setOnClickListener
                }
                ioSafe {
                    try {
                        provider.summarize("Test")
                        showToast(R.string.ai_connection_successful)
                    } catch (e: Exception) {
                        logError(e)
                        showToast(context.getString(R.string.ai_connection_failed_format, e.message))
                    }
                }
            }

            builder.setPositiveButton(R.string.sort_apply) { _, _ ->
                if (upsertCurrentProfile(showSavedToast = false)) {
                    val finalSettings = readSettingsFromInputs().copy(useAi = true)
                    context.setKey(EPUB_AI_SETTINGS, finalSettings)
                    saveProfiles()
                }
            }
            builder.setNegativeButton(R.string.sort_cancel) { d, _ -> d.dismiss() }

            builder.show()
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.rating_format_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.RatingFormat)
            val prefValues = resources.getStringArray(R.array.RatingFormatData)

            val current =
                settingsManager.getString(getString(R.string.rating_format_key), prefValues.first())

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.rating_format),
                false,
                {}) {
                try {
                    settingsManager.edit {
                        putString(getString(R.string.rating_format_key), prefValues[it])
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
            return@setOnPreferenceClickListener true
        }

        getPref(R.string.download_format_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.DownloadGridFormat)
            val prefValues = resources.getStringArray(R.array.DownloadGridFormatData)

            val current =
                settingsManager.getString(
                    getString(R.string.download_format_key),
                    prefValues[1]//As soon as you install the app, everything is displayed as a list even though it is set to grid. This is because it was previously set to .first()
                )

            activity?.showBottomDialog(
                prefNames.toList(),
                prefValues.indexOf(current),
                getString(R.string.rating_format),
                false,
                {}) {
                safe {
                    settingsManager.edit {
                        AnyAdapter.sharedPool.clear()
                        putString(getString(R.string.download_format_key), prefValues[it])
                    }
                }
            }
            return@setOnPreferenceClickListener true
        }

        /*getPref(R.string.theme_key)?.setOnPreferenceClickListener {
            val prefNames = resources.getStringArray(R.array.themes_names)
            val prefValues = resources.getStringArray(R.array.themes_names_values)

            val currentPref =
                settingsManager.getString(getString(R.string.theme_key), "Blue")

            activity?.showDialog(
                prefNames.toList(),
                prefValues.indexOf(currentPref),
                getString(R.string.theme),
                true,
                {}) { index ->
                settingsManager.edit()
                    .putString(getString(R.string.theme_key), prefValues[index])
                    .apply()
            }
            return@setOnPreferenceClickListener true
        }*/


        /*
        val listPreference = findPreference<ListPreference>("provider_list")!!

        val apiNames = MainActivity.apis.map { it.name }

        listPreference.entries = apiNames.toTypedArray()
        listPreference.entryValues = apiNames.toTypedArray()
        listPreference.setOnPreferenceChangeListener { preference, newValue ->
            MainActivity.activeAPI = MainActivity.getApiFromName(newValue.toString())
            return@setOnPreferenceChangeListener true
        }*/
    }
}
