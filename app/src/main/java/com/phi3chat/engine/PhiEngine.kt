package com.phi3chat.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.phi3chat.native.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Lifecycle of the native model. */
sealed interface EngineStatus {
    data object NoModel : EngineStatus

    data class Loading(val fraction: Float) : EngineStatus

    data class Ready(val info: ModelInfo) : EngineStatus {
        val name: String get() = info.name
        val contextSize: Int get() = info.contextSize
        val sizeBytes: Long get() = info.sizeBytes
    }

    data class Failed(val message: String) : EngineStatus
}

data class ModelInfo(
    val name: String,
    val contextSize: Int,
    val sizeBytes: Long,
    val backend: String,
)

sealed interface TokenEvent {
    data class Delta(val text: String) : TokenEvent
    data class Completed(val stats: GenerationStats) : TokenEvent
    data class Failed(val message: String) : TokenEvent
}

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptMillis: Long,
    val generationMillis: Long,
) {
    val tokensPerSecond: Double
        get() = if (generationMillis <= 0) 0.0 else generatedTokens * 1000.0 / generationMillis
}

/**
 * Owns the native engine handle and guarantees that only one inference call runs
 * at a time. All native work happens on [Dispatchers.Default]; callers only ever
 * touch coroutines.
 */
class PhiEngine(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.NoModel)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val busy = AtomicBoolean(false)

    /** Retained by the process, not the UI, so a load survives navigation. */
    private var handle: Long = NativeBridge.nativeCreate()

    val isGenerating: Boolean get() = busy.get()

    /**
     * Loads a GGUF file. `path` must be a real filesystem path, not a SAF URI,
     * because llama.cpp opens it with mmap. Use [importModelFromUri] to copy a
     * document-provider file into app storage first.
     */
    suspend fun load(
        path: String,
        displayName: String,
        contextSize: Int,
        threadCount: Int,
        batchSize: Int = 256,
    ): Result<ModelInfo> = withContext(Dispatchers.Default) {
        if (busy.get()) return@withContext Result.failure(
            IllegalStateException("Cannot load a model while a reply is being generated.")
        )

        _status.value = EngineStatus.Loading(0f)

        val error = NativeBridge.nativeLoad(
            handle,
            path,
            contextSize,
            threadCount,
            batchSize,
            NativeBridge.LoadProgressListener { fraction ->
                _status.value = EngineStatus.Loading(fraction)
                true
            },
        )

        if (error != null) {
            _status.value = EngineStatus.Failed(error)
            return@withContext Result.failure(IllegalStateException(error))
        }

        val info = NativeBridge.nativeModelInfo(handle)
        val backend = NativeBridge.nativeSystemInfo(handle).orEmpty()
        val model = ModelInfo(
            name = displayName,
            contextSize = info?.getOrNull(0)?.toInt() ?: contextSize,
            sizeBytes = info?.getOrNull(1) ?: 0L,
            backend = backend,
        )
        _status.value = EngineStatus.Ready(model)
        Result.success(model)
    }

    suspend fun unload() = withContext(Dispatchers.Default) {
        NativeBridge.nativeUnload(handle)
        _status.value = EngineStatus.NoModel
    }

    fun requestStop() {
        NativeBridge.nativeRequestStop(handle)
    }

    /**
     * Streams a reply for [messages]. Emits [TokenEvent.Delta] for each chunk and
     * exactly one terminal event.
     *
     * Generation runs in [scope] so it survives the collector's lifecycle, but a
     * cancelled collector stops it. Only one generation may be in flight.
     */
    fun generate(
        messages: List<Pair<String, String>>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int = -1,
    ): Flow<TokenEvent> = callbackFlow {
        if (!busy.compareAndSet(false, true)) {
            trySend(TokenEvent.Failed("A reply is already being generated."))
            close()
            return@callbackFlow
        }

        val job = scope.launch {
            val roles = messages.map { it.first }.toTypedArray()
            val contents = messages.map { it.second }.toTypedArray()

            val listener = NativeBridge.TokenListener { text, isFinal ->
                if (!isFinal && text.isNotEmpty()) trySend(TokenEvent.Delta(text))
            }

            val result = try {
                NativeBridge.nativeGenerate(
                    handle, roles, contents,
                    maxTokens, temperature, topP, topK, minP,
                    repeatPenalty, repeatLastN, seed, listener,
                )
            } catch (t: Throwable) {
                null
            }

            if (result == null) {
                trySend(
                    TokenEvent.Failed(
                        "Generation failed. The model may have run out of memory."
                    )
                )
            } else {
                val stats = GenerationStats(
                    promptTokens = result[0].toInt(),
                    generatedTokens = result[1].toInt(),
                    promptMillis = result[2],
                    generationMillis = result[3],
                )
                trySend(TokenEvent.Completed(stats))
            }

            busy.set(false)
            close()
        }

        awaitClose {
            NativeBridge.nativeRequestStop(handle)
            job.cancel()
            busy.set(false)
        }
    }

    /** Renders the prompt without running inference; useful for debugging templates. */
    suspend fun previewPrompt(messages: List<Pair<String, String>>): String? =
        withContext(Dispatchers.Default) {
            val roles = messages.map { it.first }.toTypedArray()
            val contents = messages.map { it.second }.toTypedArray()
            NativeBridge.nativeFormatPrompt(handle, roles, contents, true)
        }

    fun release() {
        NativeBridge.nativeRelease(handle)
        handle = -1
        _status.value = EngineStatus.NoModel
    }

    companion object {
        /** Minimum plausible size of a quantised Phi-3-mini GGUF, in bytes. */
        private const val MIN_MODEL_BYTES = 128L * 1024 * 1024

        /**
         * Copies a model picked through the Storage Access Framework into app
         * storage, because the native loader needs a path it can mmap. Returns
         * the destination file and the user-facing display name.
         */
        suspend fun importModelFromUri(
            appContext: Context,
            uri: Uri,
            onProgress: (Float) -> Unit = {},
        ): Result<Pair<File, String>> = withContext(Dispatchers.IO) {
            try {
                val displayName = queryDisplayName(appContext, uri) ?: "model.gguf"
                val size = querySize(appContext, uri)
                if (size in 1 until MIN_MODEL_BYTES) {
                    return@withContext Result.failure(
                        IllegalStateException(
                            "That file is only ${size / 1024 / 1024} MB. A Phi-3 GGUF should be " +
                                "at least ~2 GB (Q4) - it does not look like a model."
                        )
                    )
                }

                val dir = File(appContext.filesDir, "models").apply { mkdirs() }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val target = File(dir, safeName)

                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (size > 0) onProgress((copied.toFloat() / size).coerceIn(0f, 1f))
                        }
                        output.flush()
                    }
                } ?: return@withContext Result.failure(
                    IllegalStateException("Could not open the selected file.")
                )

                onProgress(1f)
                Result.success(target to displayName)
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

        suspend fun listImportedModels(appContext: Context): List<File> = withContext(Dispatchers.IO) {
            File(appContext.filesDir, "models")
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(".gguf", ignoreCase = true) }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        suspend fun deleteModel(file: File) = withContext(Dispatchers.IO) {
            file.delete()
        }

        private fun queryDisplayName(context: Context, uri: Uri): String? =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }

        private fun querySize(context: Context, uri: Uri): Long =
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
                } ?: -1L
    }
}
