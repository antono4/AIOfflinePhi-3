package com.phi3chat.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/** Sampling and runtime settings, persisted across launches. */
data class Settings(
    val modelPath: String? = null,
    val modelName: String? = null,
    val contextSize: Int = 4096,
    val threadCount: Int = 0,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val repeatLastN: Int = 64,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val useDynamicColor: Boolean = true,
    val autoLoadModel: Boolean = true,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful, concise assistant running fully offline on this device. " +
                "Answer clearly and admit when you do not know something."
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val modelPath = stringPreferencesKey("model_path")
        val modelName = stringPreferencesKey("model_name")
        val contextSize = intPreferencesKey("context_size")
        val threadCount = intPreferencesKey("thread_count")
        val maxTokens = intPreferencesKey("max_tokens")
        val temperature = floatPreferencesKey("temperature")
        val topP = floatPreferencesKey("top_p")
        val topK = intPreferencesKey("top_k")
        val minP = floatPreferencesKey("min_p")
        val repeatPenalty = floatPreferencesKey("repeat_penalty")
        val repeatLastN = intPreferencesKey("repeat_last_n")
        val systemPrompt = stringPreferencesKey("system_prompt")
        val useDynamicColor = booleanPreferencesKey("use_dynamic_color")
        val autoLoadModel = booleanPreferencesKey("auto_load_model")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        val defaults = Settings()
        Settings(
            modelPath = prefs[Keys.modelPath],
            modelName = prefs[Keys.modelName],
            contextSize = prefs[Keys.contextSize] ?: defaults.contextSize,
            threadCount = prefs[Keys.threadCount] ?: defaults.threadCount,
            maxTokens = prefs[Keys.maxTokens] ?: defaults.maxTokens,
            temperature = prefs[Keys.temperature] ?: defaults.temperature,
            topP = prefs[Keys.topP] ?: defaults.topP,
            topK = prefs[Keys.topK] ?: defaults.topK,
            minP = prefs[Keys.minP] ?: defaults.minP,
            repeatPenalty = prefs[Keys.repeatPenalty] ?: defaults.repeatPenalty,
            repeatLastN = prefs[Keys.repeatLastN] ?: defaults.repeatLastN,
            systemPrompt = prefs[Keys.systemPrompt] ?: defaults.systemPrompt,
            useDynamicColor = prefs[Keys.useDynamicColor] ?: defaults.useDynamicColor,
            autoLoadModel = prefs[Keys.autoLoadModel] ?: defaults.autoLoadModel,
        )
    }

    suspend fun setModel(path: String, name: String) = context.dataStore.edit { prefs ->
        prefs[Keys.modelPath] = path
        prefs[Keys.modelName] = name
    }

    suspend fun clearModel() = context.dataStore.edit { prefs ->
        prefs.remove(Keys.modelPath)
        prefs.remove(Keys.modelName)
    }

    suspend fun setContextSize(value: Int) = context.dataStore.edit { it[Keys.contextSize] = value }

    suspend fun setThreadCount(value: Int) = context.dataStore.edit { it[Keys.threadCount] = value }

    suspend fun setMaxTokens(value: Int) = context.dataStore.edit { it[Keys.maxTokens] = value }

    suspend fun setTemperature(value: Float) = context.dataStore.edit { it[Keys.temperature] = value }

    suspend fun setTopP(value: Float) = context.dataStore.edit { it[Keys.topP] = value }

    suspend fun setTopK(value: Int) = context.dataStore.edit { it[Keys.topK] = value }

    suspend fun setMinP(value: Float) = context.dataStore.edit { it[Keys.minP] = value }

    suspend fun setRepeatPenalty(value: Float) = context.dataStore.edit { it[Keys.repeatPenalty] = value }

    suspend fun setRepeatLastN(value: Int) = context.dataStore.edit { it[Keys.repeatLastN] = value }

    suspend fun setSystemPrompt(value: String) = context.dataStore.edit { it[Keys.systemPrompt] = value }

    suspend fun setUseDynamicColor(value: Boolean) = context.dataStore.edit { it[Keys.useDynamicColor] = value }

    suspend fun setAutoLoadModel(value: Boolean) = context.dataStore.edit { it[Keys.autoLoadModel] = value }
}
