package com.phi3chat.native

/**
 * Thin Kotlin mirror of the native JNI surface. Every call is blocking and must
 * run off the main thread; [com.phi3chat.engine.PhiEngine] wraps this with a
 * single-threaded dispatcher and coroutines.
 */
object NativeBridge {

    init {
        System.loadLibrary("phi3chat")
    }

    /** Reports model loading progress in `0f..1f`. Returning false aborts the load. */
    fun interface LoadProgressListener {
        fun onProgress(fraction: Float): Boolean
    }

    /** Receives streamed text chunks. `isFinal` marks the end of a completion. */
    fun interface TokenListener {
        fun onToken(text: String, isFinal: Boolean)
    }

    external fun nativeCreate(): Long
    external fun nativeRelease(handle: Long)
    external fun nativeUnload(handle: Long)

    /** @return null on success, or a human-readable error message. */
    external fun nativeLoad(
        handle: Long,
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        progressListener: LoadProgressListener?,
    ): String?

    external fun nativeRequestStop(handle: Long)

    /** @return the rendered prompt, or null if the template could not be applied. */
    external fun nativeFormatPrompt(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistantHeader: Boolean,
    ): String?

    /**
     * Streams a completion. Blocks until generation finishes or is cancelled.
     *
     * @return `[promptTokens, generatedTokens, promptMillis, generationMillis]`,
     *         or null when generation failed.
     */
    external fun nativeGenerate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Int,
        tokenListener: TokenListener?,
    ): LongArray?

    external fun nativeSystemInfo(handle: Long): String?

    /** @return `[contextSize, modelSizeBytes]`. */
    external fun nativeModelInfo(handle: Long): LongArray?
}
