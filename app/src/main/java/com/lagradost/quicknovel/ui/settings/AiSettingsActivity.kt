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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    val editor = state.editor
    var discard by rememberSaveable { mutableStateOf(false) }
    var showKey by remember(state.editing, editor.provider, editor.profile().id) { mutableStateOf(false) }
    var wantModels by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val editable = !state.loading && !state.saving
    val idle = editable && state.operation == null
    val exit: () -> Unit = {
        if (!state.saving) {
            if (state.editing) {
                if (state.dirty) discard = true else controller.discardEditor()
            } else onBack()
        }
    }
    BackHandler(onBack = exit)
    LaunchedEffect(state.editing, editor.provider, editor.profile().id) {
        showKey = false
        wantModels = false
        deleting = false
    }
    LaunchedEffect(state.notice) {
        if (state.notice is AiSettingsNotice.Failed || state.notice == AiSettingsNotice.NoModels) wantModels = false
    }
    val config = editor.config()
    val invalidBaseUrl = config.baseUrl.isNotBlank() && aiApiBaseUrlOrNull(config.baseUrl) == null
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (!state.editing) R.string.ai_provider_settings
                            else if (state.adding) R.string.ai_add_key else R.string.ai_edit_key
                        )
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = exit,
                        enabled = !state.saving,
                        colors = IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_baseline_arrow_back_24),
                            stringResource(R.string.action_bar_up_description),
                        )
                    }
                },
                actions = {
                    if (!state.editing) {
                        Box {
                            IconButton(
                                onClick = { adding = true },
                                enabled = editable,
                                colors =
                                    IconButtonDefaults.iconButtonColors(
                                        disabledContentColor = LocalContentColor.current
                                    ),
                            ) {
                                Icon(
                                    painterResource(R.drawable.ic_baseline_add_24),
                                    stringResource(R.string.ai_add_key),
                                )
                            }
                            DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                                AiProvider.entries.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(providerLabel(provider)) },
                                        onClick = {
                                            adding = false
                                            controller.createKey(provider)
                                        },
                                    )
                                }
                            }
                        }
                    } else if (!state.adding) {
                        IconButton(onClick = { deleting = true }, enabled = editable) {
                            Icon(
                                painterResource(R.drawable.ic_baseline_delete_outline_24),
                                stringResource(R.string.ai_delete_key),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (state.editing)
                Surface {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        TextButton(
                            onClick = controller::discardEditor,
                            enabled = editable,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        Button(
                            onClick = controller::save,
                            enabled = editable && state.dirty && !invalidBaseUrl,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (state.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.save))
                        }
                    }
                }
        },
    ) { padding ->
        if (!state.editing) {
            AiKeyList(
                state,
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                onEdit = controller::openEditor,
                onSelect = controller::activateKey,
            )
        } else
            Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    providerLabel(editor.provider),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = editor.profile().name,
                    onValueChange = controller::renameKey,
                    label = { Text(stringResource(R.string.ai_key_name)) },
                    singleLine = true,
                    placeholder = { Text(keyLabel(editor, editor.profile())) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                )
                OutlinedTextField(
                    value = config.baseUrl,
                    onValueChange = {
                        wantModels = false
                        controller.setBaseUrl(it)
                    },
                    label = { Text(stringResource(R.string.ai_base_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                    isError = invalidBaseUrl,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    placeholder = { Text(editor.provider.apiUrl) },
                    trailingIcon = {
                        if (config.baseUrl.isNotEmpty())
                            TextButton(
                                onClick = {
                                    wantModels = false
                                    controller.setBaseUrl("")
                                },
                                enabled = editable,
                            ) {
                                Text(stringResource(R.string.ai_reset_url))
                            }
                    },
                    supportingText =
                        if (invalidBaseUrl) {
                            { Text(stringResource(R.string.ai_error_invalid_url)) }
                        } else null,
                )
                OutlinedTextField(
                    value = config.apiKey,
                    onValueChange = {
                        wantModels = false
                        controller.setApiKey(it)
                    },
                    label = { Text(stringResource(R.string.ai_api_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }, enabled = editable) {
                            Text(stringResource(if (showKey) R.string.ai_hide_key else R.string.ai_show_key))
                        }
                    },
                )
                OutlinedTextField(
                    value = config.model,
                    onValueChange = {
                        wantModels = false
                        controller.setModel(it)
                    },
                    label = { Text(stringResource(R.string.ai_model)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            wantModels = true
                            controller.fetchModels()
                        },
                        enabled =
                            idle && !invalidBaseUrl && (config.apiKey.isNotBlank() || config.baseUrl.isNotBlank()),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.operation == AiSettingsOperation.FETCH_MODELS) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else Text(stringResource(R.string.ai_fetch_models))
                    }
                    OutlinedButton(
                        onClick = controller::testConnection,
                        enabled =
                            idle &&
                                !invalidBaseUrl &&
                                (config.apiKey.isNotBlank() || config.baseUrl.isNotBlank()) &&
                                config.model.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.operation == AiSettingsOperation.TEST_CONNECTION) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else Text(stringResource(R.string.ai_test_connection))
                    }
                }
                AiNotice(state.notice)
            }
    }
    if (state.editing && wantModels && state.operation == null && state.models.isNotEmpty()) {
        AiModelPicker(
            state.models,
            onDismiss = { wantModels = false },
            onSelect = {
                controller.setModel(it.id)
                wantModels = false
            },
        )
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.ai_discard_title)) },
            text = { Text(stringResource(R.string.ai_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        discard = false
                        controller.discardEditor()
                    }
                ) {
                    Text(stringResource(R.string.ai_discard))
                }
            },
            dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.ai_delete_key)) },
            text = { Text(stringResource(R.string.ai_delete_key_message, keyLabel(editor, editor.profile()))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        controller.deleteEditedKey()
                    }
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun providerLabel(provider: AiProvider): String =
    if (provider == AiProvider.OPENAI) stringResource(R.string.ai_openai_compatible) else provider.displayName

@Composable
private fun keyLabel(settings: AiSettings, profile: AiKeyProfile): String =
    profile.name.ifBlank {
        stringResource(R.string.ai_key_number, settings.keyProfiles().indexOfFirst { it.id == profile.id } + 1)
    }

@Composable
private fun AiNotice(notice: AiSettingsNotice?) {
    if (notice == null) return
    Text(
        when (notice) {
            AiSettingsNotice.Saved -> stringResource(R.string.ai_settings_saved)
            AiSettingsNotice.NoModels -> stringResource(R.string.ai_no_models)
            is AiSettingsNotice.Connected -> stringResource(R.string.ai_connected, notice.model.id)
            is AiSettingsNotice.Failed -> stringResource(notice.reason.messageRes())
        },
        color =
            if (notice is AiSettingsNotice.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AiKeyList(
    state: AiSettingsState,
    modifier: Modifier,
    onEdit: (AiProvider, String) -> Unit,
    onSelect: (AiProvider, String) -> Unit,
) {
    val entries =
        AiProvider.entries.flatMap { provider -> state.settings.profiles[provider].orEmpty().map { provider to it } }
    val enabled = !state.loading && !state.saving
    LazyColumn(modifier.navigationBarsPadding(), contentPadding = PaddingValues(16.dp)) {
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (entries.isEmpty() && !state.loading)
            item {
                Text(
                    stringResource(R.string.ai_no_keys),
                    modifier = Modifier.padding(vertical = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        items(entries, key = { (provider, profile) -> "${provider.name}:${profile.id}" }) { (provider, profile) ->
            val selected = state.settings.provider == provider && state.settings.profile().id == profile.id
            val labelSettings = state.settings.copy(provider = provider)
            val label = keyLabel(labelSettings, profile)
            val selectDescription = stringResource(R.string.ai_select_key, label)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = selected,
                    onClick = { onSelect(provider, profile.id) },
                    enabled = enabled,
                    colors =
                        RadioButtonDefaults.colors(
                            disabledSelectedColor = MaterialTheme.colorScheme.primary,
                            disabledUnselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    modifier = Modifier.semantics { contentDescription = selectDescription },
                )
                Column(
                    Modifier.weight(1f)
                        .clickable(enabled = enabled) { onEdit(provider, profile.id) }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(providerLabel(provider), profile.config.model)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { onEdit(provider, profile.id) },
                    enabled = enabled,
                    colors = IconButtonDefaults.iconButtonColors(disabledContentColor = LocalContentColor.current),
                ) {
                    Icon(painterResource(R.drawable.ic_baseline_edit_24), stringResource(R.string.ai_edit_key))
                }
            }
        }
        item { AiNotice(state.notice.takeIf { it is AiSettingsNotice.Failed }) }
    }
}

@Composable
private fun AiModelPicker(models: List<AiModel>, onDismiss: () -> Unit, onSelect: (AiModel) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered =
        remember(models, query) {
            models.filter { it.id.contains(query.trim(), true) || it.name.contains(query.trim(), true) }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_choose_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.ai_search_models)) },
                )
                if (filtered.isEmpty()) Text(stringResource(R.string.ai_no_matching_models))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { it.id }) { model ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 12.dp)) {
                            Text(model.name, style = MaterialTheme.typography.bodyLarge)
                            if (model.name != model.id)
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
