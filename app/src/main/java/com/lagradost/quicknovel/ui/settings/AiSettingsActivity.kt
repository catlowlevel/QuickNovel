package com.lagradost.quicknovel.ui.settings

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.perfToColor
import com.lagradost.cloudstream4.theme.perfToMode
import com.lagradost.quicknovel.CommonActivity
import com.lagradost.quicknovel.CommonActivity.updateLocale
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.ai.*
import com.mihon.presentation.settings.collectAsState

class AiSettingsActivity : AppCompatActivity() {
    private val model: AiSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        CommonActivity.loadThemes(this)
        updateLocale()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val preferences = rememberAppSettings()
            val theme by preferences.ui.theme.collectAsState()
            val color by preferences.ui.primaryColor.collectAsState()
            CloudStreamTheme(mode = perfToMode(theme), primaryColor = perfToColor(color)) {
                AiSettingsScreen(model.controller, onBack = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiSettingsScreen(controller: AiSettingsController, onBack: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    var discard by rememberSaveable { mutableStateOf(false) }
    var showKey by remember(state.settings.provider, state.settings.profile().id) { mutableStateOf(false) }
    var wantModels by rememberSaveable { mutableStateOf(false) }
    var renamingKey by rememberSaveable { mutableStateOf(false) }
    var keyName by rememberSaveable { mutableStateOf("") }
    var deletingKey by rememberSaveable { mutableStateOf(false) }
    val exit: () -> Unit = {
        if (!state.saving) {
            if (state.dirty) discard = true else onBack()
        }
    }
    BackHandler(onBack = exit)
    LaunchedEffect(state.settings.provider, state.settings.profile().id) {
        showKey = false
        wantModels = false
        renamingKey = false
        deletingKey = false
    }
    LaunchedEffect(state.notice) {
        if (state.notice is AiSettingsNotice.Failed || state.notice == AiSettingsNotice.NoModels) wantModels = false
    }
    val config = state.settings.config()
    val editable = !state.loading && !state.saving
    val idle = editable && state.operation == null
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ai_provider_settings)) },
                navigationIcon = {
                    IconButton(onClick = exit, enabled = !state.saving) {
                        Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), stringResource(R.string.action_bar_up_description))
                    }
                },
            )
        },
        bottomBar = {
            Surface {
                Button(onClick = controller::save, enabled = editable && state.dirty,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.save))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AiProvider.entries.forEachIndexed { index, provider ->
                    SegmentedButton(selected = state.settings.provider == provider,
                        enabled = editable,
                        onClick = { controller.selectProvider(provider) },
                        shape = SegmentedButtonDefaults.itemShape(index, AiProvider.entries.size)) {
                        Text(provider.displayName, maxLines = 1)
                    }
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AiKeySelector(state.settings, enabled = editable,
                onSelect = controller::selectKey, onAdd = controller::addKey,
                onRename = { keyName = state.settings.profile().name; renamingKey = true },
                onDelete = { deletingKey = true })
            OutlinedTextField(value = config.apiKey, onValueChange = { wantModels = false; controller.setApiKey(it) },
                label = { Text(stringResource(R.string.ai_api_key)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(stringResource(if (showKey) R.string.ai_hide_key else R.string.ai_show_key))
                    }
                })
            OutlinedTextField(value = config.model, onValueChange = { wantModels = false; controller.setModel(it) },
                label = { Text(stringResource(R.string.ai_model)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), enabled = editable,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                supportingText = { Text(stringResource(R.string.ai_model_hint)) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { wantModels = true; controller.fetchModels() },
                    enabled = idle && config.apiKey.isNotBlank(), modifier = Modifier.weight(1f)) {
                    if (state.operation == AiSettingsOperation.FETCH_MODELS) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else Text(stringResource(R.string.ai_fetch_models))
                }
                OutlinedButton(onClick = controller::testConnection,
                    enabled = idle && config.apiKey.isNotBlank() && config.model.isNotBlank(), modifier = Modifier.weight(1f)) {
                    if (state.operation == AiSettingsOperation.TEST_CONNECTION) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else Text(stringResource(R.string.ai_test_connection))
                }
            }
            Text(stringResource(R.string.ai_connection_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.notice?.let { notice ->
                Text(when (notice) {
                    AiSettingsNotice.Saved -> stringResource(R.string.ai_settings_saved)
                    AiSettingsNotice.NoModels -> stringResource(R.string.ai_no_models)
                    is AiSettingsNotice.Connected -> stringResource(R.string.ai_connected, notice.model.id)
                    is AiSettingsNotice.Failed -> stringResource(notice.reason.messageRes())
                }, color = if (notice is AiSettingsNotice.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
    if (wantModels && state.operation == null && state.models.isNotEmpty()) {
        AiModelPicker(state.models, onDismiss = { wantModels = false }, onSelect = {
            controller.setModel(it.id)
            wantModels = false
        })
    }
    if (discard) {
        AlertDialog(onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.ai_discard_title)) },
            text = { Text(stringResource(R.string.ai_discard_message)) },
            confirmButton = {
                TextButton(onClick = { discard = false; onBack() }) { Text(stringResource(R.string.ai_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) { Text(stringResource(R.string.cancel)) }
            })
    }
    if (renamingKey) {
        AlertDialog(onDismissRequest = { renamingKey = false },
            title = { Text(stringResource(R.string.ai_rename_key)) },
            text = {
                OutlinedTextField(value = keyName, onValueChange = { keyName = it }, singleLine = true,
                    label = { Text(stringResource(R.string.ai_key_name)) })
            },
            confirmButton = {
                TextButton(onClick = { controller.renameKey(keyName); renamingKey = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = { TextButton(onClick = { renamingKey = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (deletingKey) {
        AlertDialog(onDismissRequest = { deletingKey = false },
            title = { Text(stringResource(R.string.ai_delete_key)) },
            text = { Text(stringResource(R.string.ai_delete_key_message, keyLabel(state.settings, state.settings.profile()))) },
            confirmButton = {
                TextButton(onClick = { controller.deleteKey(); deletingKey = false }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingKey = false }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun keyLabel(settings: AiSettings, profile: AiKeyProfile): String = profile.name.ifBlank {
    stringResource(R.string.ai_key_number, settings.keyProfiles().indexOfFirst { it.id == profile.id } + 1)
}

@Composable
private fun AiKeySelector(
    settings: AiSettings,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ai_key_profile), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { choosing = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(keyLabel(settings, settings.profile()), Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Icon(painterResource(R.drawable.arrow_drop_down_24px), stringResource(R.string.ai_choose_key))
                }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    settings.keyProfiles().forEach { profile ->
                        DropdownMenuItem(text = { Text(keyLabel(settings, profile)) },
                            onClick = { choosing = false; onSelect(profile.id) },
                            trailingIcon = {
                                if (settings.profile().id == profile.id) {
                                    Icon(painterResource(R.drawable.ic_baseline_check_24), null)
                                }
                            })
                    }
                }
            }
            IconButton(onClick = onAdd, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_baseline_add_24), stringResource(R.string.ai_add_key))
            }
            Box {
                IconButton(onClick = { options = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_baseline_more_vert_24), stringResource(R.string.ai_key_options))
                }
                DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_rename_key)) },
                        onClick = { options = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ai_delete_key)) },
                        onClick = { options = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun AiModelPicker(models: List<AiModel>, onDismiss: () -> Unit, onSelect: (AiModel) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(models, query) {
        models.filter { it.id.contains(query.trim(), true) || it.name.contains(query.trim(), true) }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_choose_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.ai_search_models)) })
                if (filtered.isEmpty()) Text(stringResource(R.string.ai_no_matching_models))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 12.dp)) {
                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                            if (model.name != model.id) Text(model.id, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun AiFailure.messageRes(): Int = when (this) {
    AiFailure.MISSING_KEY -> R.string.ai_error_missing_key
    AiFailure.INVALID_KEY -> R.string.ai_error_invalid_key
    AiFailure.MISSING_MODEL -> R.string.ai_error_missing_model
    AiFailure.INVALID_MODEL -> R.string.ai_error_invalid_model
    AiFailure.AUTHENTICATION -> R.string.ai_error_authentication
    AiFailure.FORBIDDEN -> R.string.ai_error_forbidden
    AiFailure.NOT_FOUND -> R.string.ai_error_not_found
    AiFailure.RATE_LIMIT -> R.string.ai_error_rate_limit
    AiFailure.SERVER -> R.string.ai_error_server
    AiFailure.NETWORK -> R.string.ai_error_network
    AiFailure.TIMEOUT -> R.string.ai_error_timeout
    AiFailure.INVALID_RESPONSE -> R.string.ai_error_response
    AiFailure.UNSUPPORTED_MODEL -> R.string.ai_error_unsupported
    AiFailure.LOAD_SETTINGS -> R.string.ai_error_load
    AiFailure.SAVE_SETTINGS -> R.string.ai_error_save
}
