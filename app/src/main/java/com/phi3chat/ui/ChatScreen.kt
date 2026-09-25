package com.phi3chat.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phi3chat.ChatUiState
import com.phi3chat.ChatViewModel
import com.phi3chat.R
import com.phi3chat.data.Role
import com.phi3chat.engine.EngineStatus

/**
 * The chat surface: transcript, streaming draft, and the composer.
 *
 * Layout order matters here: [imePadding] wraps the transcript so the list
 * shrinks when the keyboard opens instead of scrolling behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatus.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val isDark = isSystemInDarkTheme()

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importModel(uri)
    }

    LaunchedEffect(Unit) { viewModel.autoLoadIfConfigured() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // Follow the end of the transcript as it grows.
    val rowCount = uiState.items.size + if (uiState.isGenerating) 1 else 0
    LaunchedEffect(rowCount, uiState.streamingText.length) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Column {
                        Text(
                            text = uiState.title.ifBlank { stringResource(R.string.app_name) },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        Text(
                            text = engineStatus.label(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.newConversation() }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(R.string.new_chat),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (engineStatus is EngineStatus.Loading) {
                LoadingBar(
                    fraction = (engineStatus as EngineStatus.Loading).fraction,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .imePadding(),
            ) {
                Transcript(
                    uiState = uiState,
                    isDark = isDark,
                    listState = listState,
                    onImportModel = { modelPicker.launch(MODEL_MIME_TYPES) },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Composer(
                draft = uiState.draft,
                isGenerating = uiState.isGenerating,
                enabled = engineStatus is EngineStatus.Ready,
                onDraftChange = viewModel::updateDraft,
                onSend = {
                    keyboard?.hide()
                    viewModel.send()
                },
                onStop = viewModel::stopGeneration,
            )

            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Transcript(
    uiState: ChatUiState,
    isDark: Boolean,
    listState: LazyListState,
    onImportModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.items.isEmpty() && !uiState.isGenerating) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.no_model_title),
                body = stringResource(R.string.no_model_body),
                action = {
                    Button(onClick = onImportModel) {
                        Text(stringResource(R.string.import_model))
                    }
                },
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(items = uiState.items, key = { it.id }) { item ->
            MessageBubble(
                role = item.role,
                text = item.content,
                isStreaming = false,
                isDark = isDark,
                tokensPerSecond = item.tokensPerSecond,
            )
        }

        if (uiState.isGenerating) {
            item(key = "streaming") {
                MessageBubble(
                    role = Role.ASSISTANT,
                    text = uiState.streamingText.ifEmpty { stringResource(R.string.thinking) },
                    isStreaming = true,
                    isDark = isDark,
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    isGenerating: Boolean,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                enabled = enabled && !isGenerating,
                placeholder = { Text(stringResource(R.string.message_hint)) },
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )

            val canSend = enabled && !isGenerating && draft.isNotBlank()

            if (isGenerating) {
                IconButton(onClick = onStop, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = stringResource(R.string.content_description_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.content_description_send),
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun EngineStatus.label(): String = when (val s = this) {
    is EngineStatus.NoModel -> "No model loaded"
    is EngineStatus.Loading -> "Loading ${(s.fraction * 100).toInt()}%"
    is EngineStatus.Ready -> "${s.name} · ${s.contextSize / 1024}K ctx"
    is EngineStatus.Failed -> "Model error"
}

/** GGUF files usually carry no MIME type, so accept anything and validate by name. */
private val MODEL_MIME_TYPES = arrayOf("*/*")
