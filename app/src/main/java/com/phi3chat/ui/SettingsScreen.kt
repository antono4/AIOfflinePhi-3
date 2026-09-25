package com.phi3chat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phi3chat.ChatViewModel
import com.phi3chat.R
import com.phi3chat.data.Settings
import com.phi3chat.engine.EngineStatus
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val models by viewModel.availableModels.collectAsStateWithLifecycle()

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importModel(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_description_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ModelSection(
                models = models,
                status = engineStatus,
                onImport = { modelPicker.launch(arrayOf("*/*")) },
                onLoad = { viewModel.loadModel(it) },
                onDelete = { viewModel.deleteModel(it) },
                onUnload = { viewModel.unloadModel() },
                autoLoad = settings.autoLoadModel,
                onAutoLoadChange = viewModel::setAutoLoadModel,
            )

            RuntimeSection(
                settings = settings,
                onContextSize = viewModel::setContextSize,
                onThreads = viewModel::setThreadCount,
                onReload = {
                    viewModel.reloadWithCurrentSettings()
                },
            )

            SamplingSection(
                settings = settings,
                onMaxTokens = viewModel::setMaxTokens,
                onTemperature = viewModel::setTemperature,
                onTopP = viewModel::setTopP,
                onTopK = viewModel::setTopK,
                onMinP = viewModel::setMinP,
                onRepeatPenalty = viewModel::setRepeatPenalty,
                onRepeatLastN = viewModel::setRepeatLastN,
            )

            PromptSection(
                settings = settings,
                onSystemPrompt = viewModel::setSystemPrompt,
            )

            AppearanceSection(
                useDynamicColor = settings.useDynamicColor,
                onDynamicColor = viewModel::setUseDynamicColor,
            )

            DangerSection(
                onDeleteAll = { viewModel.deleteAllConversations() },
            )

            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun ModelSection(
    models: List<File>,
    status: EngineStatus,
    onImport: () -> Unit,
    onLoad: (File) -> Unit,
    onDelete: (File) -> Unit,
    onUnload: () -> Unit,
    autoLoad: Boolean,
    onAutoLoadChange: (Boolean) -> Unit,
) {
    SectionCard(
        title = "Model",
        subtitle = status.description(),
    ) {
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.import_model))
        }

        if (models.isEmpty()) {
            Text(
                text = "No GGUF files imported yet. Phi-3-mini-4k-instruct Q4_K_M " +
                    "is about 2.4 GB and runs in roughly 3 GB of RAM.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            models.forEach { file ->
                val isLoaded = (status as? EngineStatus.Ready)?.name == file.name
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${file.length() / (1024 * 1024)} MB" +
                                if (isLoaded) " · loaded" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (isLoaded) {
                        TextButton(onClick = onUnload) { Text("Unload") }
                    } else {
                        TextButton(
                            onClick = { onLoad(file) },
                            enabled = status !is EngineStatus.Loading,
                        ) { Text("Load") }
                    }
                    TextButton(onClick = { onDelete(file) }) { Text("Delete") }
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Load model on startup", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Reuses the last selected GGUF automatically.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = autoLoad, onCheckedChange = onAutoLoadChange)
        }
    }
}

@Composable
private fun RuntimeSection(
    settings: Settings,
    onContextSize: (Int) -> Unit,
    onThreads: (Int) -> Unit,
    onReload: () -> Unit,
) {
    SectionCard(
        title = "Runtime",
        subtitle = "Changes apply the next time the model is loaded.",
    ) {
        LabeledSlider(
            label = "Context size",
            value = settings.contextSize.toFloat(),
            range = 1024f..8192f,
            steps = 6,
            display = "${settings.contextSize} tokens",
            onValueChange = { onContextSize(it.toInt()) },
        )

        val threadLabel = if (settings.threadCount == 0) {
            "Auto (${Runtime.getRuntime().availableProcessors() - 2} threads)"
        } else {
            "${settings.threadCount} threads"
        }
        LabeledSlider(
            label = "CPU threads",
            value = settings.threadCount.toFloat(),
            range = 0f..8f,
            steps = 7,
            display = threadLabel,
            onValueChange = { onThreads(it.toInt()) },
        )

        Text(
            text = "More context holds longer conversations but costs RAM. " +
                "On a phone, 4 threads is usually the sweet spot.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(onClick = onReload, modifier = Modifier.fillMaxWidth()) {
            Text("Reload model with these settings")
        }
    }
}

@Composable
private fun SamplingSection(
    settings: Settings,
    onMaxTokens: (Int) -> Unit,
    onTemperature: (Float) -> Unit,
    onTopP: (Float) -> Unit,
    onTopK: (Int) -> Unit,
    onMinP: (Float) -> Unit,
    onRepeatPenalty: (Float) -> Unit,
    onRepeatLastN: (Int) -> Unit,
) {
    SectionCard(
        title = "Generation",
        subtitle = "Sampling parameters for new replies.",
    ) {
        LabeledSlider(
            label = "Max reply length",
            value = settings.maxTokens.toFloat(),
            range = 64f..2048f,
            steps = 30,
            display = "${settings.maxTokens} tokens",
            onValueChange = { onMaxTokens(it.toInt()) },
        )
        LabeledSlider(
            label = "Temperature",
            value = settings.temperature,
            range = 0f..1.5f,
            steps = 29,
            display = "%.2f".format(settings.temperature),
            onValueChange = onTemperature,
        )
        LabeledSlider(
            label = "Top-p",
            value = settings.topP,
            range = 0.1f..1f,
            steps = 17,
            display = "%.2f".format(settings.topP),
            onValueChange = onTopP,
        )
        LabeledSlider(
            label = "Top-k",
            value = settings.topK.toFloat(),
            range = 0f..100f,
            steps = 19,
            display = "${settings.topK}",
            onValueChange = { onTopK(it.toInt()) },
        )
        LabeledSlider(
            label = "Min-p",
            value = settings.minP,
            range = 0f..0.5f,
            steps = 9,
            display = "%.2f".format(settings.minP),
            onValueChange = onMinP,
        )
        LabeledSlider(
            label = "Repeat penalty",
            value = settings.repeatPenalty,
            range = 1f..1.5f,
            steps = 9,
            display = "%.2f".format(settings.repeatPenalty),
            onValueChange = onRepeatPenalty,
        )
        LabeledSlider(
            label = "Repeat window",
            value = settings.repeatLastN.toFloat(),
            range = 0f..256f,
            steps = 15,
            display = "${settings.repeatLastN} tokens",
            onValueChange = { onRepeatLastN(it.toInt()) },
        )
    }
}

@Composable
private fun PromptSection(
    settings: Settings,
    onSystemPrompt: (String) -> Unit,
) {
    var draft by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }

    SectionCard(
        title = "System prompt",
        subtitle = "Sets the assistant's persona for every new conversation.",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSystemPrompt(draft) },
                enabled = draft != settings.systemPrompt,
            ) { Text("Save") }

            TextButton(
                onClick = { draft = Settings.DEFAULT_SYSTEM_PROMPT },
            ) { Text("Reset to default") }
        }
    }
}

@Composable
private fun AppearanceSection(
    useDynamicColor: Boolean,
    onDynamicColor: (Boolean) -> Unit,
) {
    SectionCard(title = "Appearance") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Use system colours", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "Follows your wallpaper palette on Android 12 and newer.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = useDynamicColor, onCheckedChange = onDynamicColor)
        }
    }
}

@Composable
private fun DangerSection(onDeleteAll: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }

    SectionCard(title = "Data") {
        Text(
            text = "Chat history is stored only on this device and is never uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (confirming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        onDeleteAll()
                        confirming = false
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Delete everything") }
                OutlinedButton(
                    onClick = { confirming = false },
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
            }
        } else {
            OutlinedButton(onClick = { confirming = true }) {
                Text("Delete all conversations")
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = display,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

private fun EngineStatus.description(): String = when (val s = this) {
    is EngineStatus.NoModel -> "No model loaded."
    is EngineStatus.Loading -> "Loading ${(s.fraction * 100).toInt()}%…"
    is EngineStatus.Ready ->
        "${s.name} · ${s.contextSize} token context · ${s.sizeBytes / (1024 * 1024)} MB"
    is EngineStatus.Failed -> s.message
}
