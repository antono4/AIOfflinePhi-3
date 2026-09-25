package com.phi3chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.phi3chat.data.ChatDao
import com.phi3chat.data.Conversation
import com.phi3chat.data.Message
import com.phi3chat.data.Role
import com.phi3chat.data.Settings
import com.phi3chat.data.SettingsRepository
import com.phi3chat.engine.EngineStatus
import com.phi3chat.engine.GenerationStats
import com.phi3chat.engine.PhiEngine
import com.phi3chat.engine.TokenEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A chat message as rendered by the UI: persisted rows only. */
data class ChatItem(
    val id: Long,
    val role: Role,
    val content: String,
    val tokensPerSecond: Double? = null,
    val generatedTokens: Int? = null,
)

data class ChatUiState(
    val conversationId: Long? = null,
    val title: String = "",
    val items: List<ChatItem> = emptyList(),
    val draft: String = "",
    val isGenerating: Boolean = false,
    val streamingText: String = "",
    val error: String? = null,
    val lastStats: GenerationStats? = null,
)

class ChatViewModel(
    private val appContext: Context,
    private val repository: SettingsRepository,
    private val dao: ChatDao,
    val engine: PhiEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** One-shot user notifications (snackbars). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    val settings: StateFlow<Settings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings(),
    )

    val engineStatus: StateFlow<EngineStatus> = engine.status

    val conversations: StateFlow<List<Conversation>> = dao.observeConversations().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    private val _availableModels = MutableStateFlow<List<File>>(emptyList())
    val availableModels: StateFlow<List<File>> = _availableModels.asStateFlow()

    /** Which conversation's messages to observe; null means "no open conversation". */
    private val observedConversation = MutableStateFlow<Long?>(null)

    private var generationJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val itemsFlow = observedConversation.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else dao.observeMessages(id)
    }

    init {
        viewModelScope.launch { refreshModels() }
        viewModelScope.launch {
            itemsFlow.collect { rows ->
                _uiState.update { current ->
                    current.copy(
                        items = rows.filter { it.role != Role.SYSTEM }.map { it.toItem() }
                    )
                }
            }
        }
    }

    private fun Message.toItem() = ChatItem(
        id = id,
        role = role,
        content = content,
        tokensPerSecond = tokensPerSecond,
        generatedTokens = generatedTokens,
    )

    // ---------------------------------------------------------------- models

    fun refreshModels() {
        viewModelScope.launch {
            _availableModels.value = PhiEngine.listImportedModels(appContext)
        }
    }

    /** Copies the picked document into app storage, then loads it. */
    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            PhiEngine.importModelFromUri(appContext, uri)
                .onSuccess { (file, displayName) ->
                    refreshModels()
                    loadModel(file, displayName)
                }
                .onFailure { t ->
                    _uiState.update {
                        it.copy(error = t.message ?: "Could not import the model.")
                    }
                }
        }
    }

    fun loadModel(file: File, displayName: String = file.name) {
        viewModelScope.launch {
            val current = settings.value
            engine.load(
                path = file.absolutePath,
                displayName = displayName,
                contextSize = current.contextSize,
                threadCount = current.threadCount,
                batchSize = 256,
            ).onSuccess {
                repository.setModel(file.absolutePath, displayName)
                _messages.tryEmit("Model $displayName loaded")
            }.onFailure { t ->
                _uiState.update { it.copy(error = t.message ?: "Could not load the model.") }
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            engine.unload()
            repository.clearModel()
        }
    }

    fun deleteModel(file: File) {
        viewModelScope.launch {
            if (settings.value.modelPath == file.absolutePath) {
                engine.unload()
                repository.clearModel()
            }
            PhiEngine.deleteModel(file)
            refreshModels()
        }
    }

    /** Loads the previously selected model at startup, when the setting allows it. */
    fun autoLoadIfConfigured() {
        val current = settings.value
        if (!current.autoLoadModel) return
        if (engineStatus.value is EngineStatus.Ready) return

        val path = current.modelPath ?: return
        val file = File(path)
        if (!file.exists()) {
            viewModelScope.launch {
                repository.clearModel()
                _uiState.update {
                    it.copy(error = "The previously used model is no longer at $path.")
                }
            }
            return
        }
        loadModel(file, current.modelName ?: file.name)
    }

    // ------------------------------------------------------------ generation

    fun updateDraft(text: String) = _uiState.update { it.copy(draft = text) }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun send() {
        val state = _uiState.value
        val text = state.draft.trim()
        if (text.isEmpty() || state.isGenerating) return
        if (engineStatus.value !is EngineStatus.Ready) {
            _uiState.update { it.copy(error = "Load a Phi-3 model before chatting.") }
            return
        }

        generationJob = viewModelScope.launch {
            val conversationId = ensureConversation(text)
            val now = System.currentTimeMillis()
            dao.insertMessage(
                Message(
                    conversationId = conversationId,
                    role = Role.USER,
                    content = text,
                    createdAt = now,
                )
            )
            dao.touchConversation(conversationId, now)

            _uiState.update {
                it.copy(draft = "", isGenerating = true, streamingText = "", error = null)
            }

            val history = buildHistory(conversationId)
            val current = settings.value
            val builder = StringBuilder()
            var stats: GenerationStats? = null
            var failure: String? = null

            engine.generate(
                messages = history,
                maxTokens = current.maxTokens,
                temperature = current.temperature,
                topP = current.topP,
                topK = current.topK,
                minP = current.minP,
                repeatPenalty = current.repeatPenalty,
                repeatLastN = current.repeatLastN,
            ).collect { event ->
                when (event) {
                    is TokenEvent.Delta -> {
                        builder.append(event.text)
                        _uiState.update { it.copy(streamingText = builder.toString()) }
                    }

                    is TokenEvent.Completed -> stats = event.stats

                    is TokenEvent.Failed -> failure = event.message
                }
            }

            if (failure != null) {
                _uiState.update {
                    it.copy(isGenerating = false, streamingText = "", error = failure)
                }
                return@launch
            }

            val reply = builder.toString().trim()
            if (reply.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        streamingText = "",
                        error = "The model produced no output. Try lowering the temperature " +
                            "or shortening the conversation.",
                    )
                }
                return@launch
            }

            dao.insertMessage(
                Message(
                    conversationId = conversationId,
                    role = Role.ASSISTANT,
                    content = reply,
                    createdAt = System.currentTimeMillis(),
                    tokensPerSecond = stats?.tokensPerSecond,
                    generatedTokens = stats?.generatedTokens,
                )
            )
            dao.touchConversation(conversationId, System.currentTimeMillis())

            _uiState.update {
                it.copy(isGenerating = false, streamingText = "", lastStats = stats)
            }
        }
    }

    fun stopGeneration() {
        if (generationJob != null) engine.requestStop()
    }

    // --------------------------------------------------------- conversations

    fun newConversation() {
        engine.requestStop()
        observedConversation.value = null
        _uiState.value = ChatUiState()
    }

    fun openConversation(id: Long) {
        viewModelScope.launch {
            val conversation = dao.conversation(id) ?: return@launch
            observedConversation.value = id
            _uiState.update {
                it.copy(conversationId = id, title = conversation.title, error = null)
            }
        }
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            if (observedConversation.value == id) newConversation()
            dao.deleteConversation(id)
        }
    }

    fun deleteAllConversations() {
        viewModelScope.launch {
            newConversation()
            dao.deleteAllConversations()
        }
    }

    fun renameConversation(title: String) {
        val id = observedConversation.value ?: return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            dao.renameConversation(id, trimmed, System.currentTimeMillis())
            _uiState.update { it.copy(title = trimmed) }
        }
    }

    private suspend fun ensureConversation(firstMessage: String): Long {
        observedConversation.value?.let { return it }
        val title = firstMessage.take(48).replace('\n', ' ').trim().ifBlank { "New chat" }
        val id = dao.createConversation(title, System.currentTimeMillis())
        observedConversation.value = id
        _uiState.update { it.copy(conversationId = id, title = title) }
        return id
    }

    /** System prompt followed by the persisted turns of this conversation. */
    private suspend fun buildHistory(conversationId: Long): List<Pair<String, String>> {
        val rows = dao.observeMessages(conversationId).first()
        val history = ArrayList<Pair<String, String>>(rows.size + 1)
        history += "system" to settings.value.systemPrompt
        rows.filter { it.role != Role.SYSTEM }
            .forEach { history += it.role.name.lowercase() to it.content }
        return history
    }

    // -------------------------------------------------------------- settings

    fun setContextSize(value: Int) = viewModelScope.launch { repository.setContextSize(value) }

    fun setThreadCount(value: Int) = viewModelScope.launch { repository.setThreadCount(value) }

    fun setMaxTokens(value: Int) = viewModelScope.launch { repository.setMaxTokens(value) }

    fun setTemperature(value: Float) = viewModelScope.launch { repository.setTemperature(value) }

    fun setTopP(value: Float) = viewModelScope.launch { repository.setTopP(value) }

    fun setTopK(value: Int) = viewModelScope.launch { repository.setTopK(value) }

    fun setMinP(value: Float) = viewModelScope.launch { repository.setMinP(value) }

    fun setRepeatPenalty(value: Float) = viewModelScope.launch { repository.setRepeatPenalty(value) }

    fun setRepeatLastN(value: Int) = viewModelScope.launch { repository.setRepeatLastN(value) }

    fun setSystemPrompt(value: String) = viewModelScope.launch { repository.setSystemPrompt(value) }

    fun setUseDynamicColor(value: Boolean) =
        viewModelScope.launch { repository.setUseDynamicColor(value) }

    fun setAutoLoadModel(value: Boolean) =
        viewModelScope.launch { repository.setAutoLoadModel(value) }

    /** Reloads the model so a changed context size or thread count takes effect. */
    fun reloadWithCurrentSettings() {
        val current = settings.value
        val path = current.modelPath ?: return
        val file = File(path)
        if (!file.exists()) return
        loadModel(file, current.modelName ?: file.name)
    }

    override fun onCleared() {
        super.onCleared()
        engine.requestStop()
        engine.release()
    }

    companion object {
        fun factory(app: Phi3ChatApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T = ChatViewModel(
                    appContext = app.applicationContext,
                    repository = app.settingsRepository,
                    dao = app.database.chatDao(),
                    engine = app.engine,
                ) as T
            }
    }
}
