package com.buddy.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "buddy_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val KEY_LLM_MODEL = stringPreferencesKey("llm_model")
        val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val KEY_WAKE_WORD = stringPreferencesKey("wake_word")
        val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val KEY_MAX_STEPS = intPreferencesKey("max_steps")
        val KEY_VOICE_SPEED = stringPreferencesKey("voice_speed")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")

        const val DEFAULT_SYSTEM_PROMPT = """You are Buddy, an AI assistant that controls an Android device on behalf of the user.
You receive the current screen state as a list of UI elements, and you must decide what action to take to accomplish the user's goal.

Always respond with a JSON object in this exact format:
{
  "think": "your reasoning about the current state and what to do",
  "action": {
    "type": "tap|type|swipe|scroll|press_back|press_home|press_recents|open_app|open_url|wait|done",
    "params": {}
  }
}

Actions:
- tap: {"nodeId": "node_id_from_screen"} or {"x": 500, "y": 800}
- type: {"text": "text to type", "nodeId": "optional_field_id"}
- swipe: {"direction": "up|down|left|right"}
- scroll: {"direction": "up|down", "nodeId": "optional_scrollable_id"}
- press_back: {}
- press_home: {}
- press_recents: {}
- open_app: {"package": "com.example.app"}
- open_url: {"url": "https://example.com"}
- wait: {"ms": 2000}
- done: {"success": true|false, "reason": "explanation"}

Be precise, methodical, and always verify your actions match the screen state before acting."""
    }

    val apiKey: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }
    val llmProvider: Flow<String> = context.dataStore.data.map { it[KEY_LLM_PROVIDER] ?: "claude" }
    val llmModel: Flow<String> = context.dataStore.data.map { it[KEY_LLM_MODEL] ?: "claude-opus-4-6" }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD_ENABLED] ?: true }
    val wakeWord: Flow<String> = context.dataStore.data.map { it[KEY_WAKE_WORD] ?: "Hey Buddy" }
    val ttsEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_TTS_ENABLED] ?: true }
    val maxSteps: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_STEPS] ?: 30 }
    val systemPrompt: Flow<String> = context.dataStore.data.map {
        it[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
    }

    suspend fun setApiKey(value: String) = context.dataStore.edit { it[KEY_API_KEY] = value }
    suspend fun setLlmProvider(value: String) = context.dataStore.edit { it[KEY_LLM_PROVIDER] = value }
    suspend fun setLlmModel(value: String) = context.dataStore.edit { it[KEY_LLM_MODEL] = value }
    suspend fun setWakeWordEnabled(value: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD_ENABLED] = value }
    suspend fun setWakeWord(value: String) = context.dataStore.edit { it[KEY_WAKE_WORD] = value }
    suspend fun setTtsEnabled(value: Boolean) = context.dataStore.edit { it[KEY_TTS_ENABLED] = value }
    suspend fun setMaxSteps(value: Int) = context.dataStore.edit { it[KEY_MAX_STEPS] = value }
    suspend fun setSystemPrompt(value: String) = context.dataStore.edit { it[KEY_SYSTEM_PROMPT] = value }

}
