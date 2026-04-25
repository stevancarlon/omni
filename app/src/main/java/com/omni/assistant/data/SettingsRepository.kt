package com.omni.assistant.data

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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "omni_settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_API_KEY_CLAUDE = stringPreferencesKey("api_key_claude")
        val KEY_API_KEY_OPENAI = stringPreferencesKey("api_key_openai")
        val KEY_API_KEY_OPENROUTER = stringPreferencesKey("api_key_openrouter")
        val KEY_API_KEY_GROQ = stringPreferencesKey("api_key_groq")
        val KEY_LLM_PROVIDER = stringPreferencesKey("llm_provider")
        val KEY_LLM_MODEL = stringPreferencesKey("llm_model")
        val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val KEY_WAKE_WORD = stringPreferencesKey("wake_word")
        val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val KEY_MAX_STEPS = intPreferencesKey("max_steps")
        val KEY_VOICE_SPEED = stringPreferencesKey("voice_speed")
        val KEY_SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        val KEY_SPEECH_PROVIDER = stringPreferencesKey("speech_provider")
        val KEY_DEEPGRAM_API_KEY = stringPreferencesKey("deepgram_api_key")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_WELCOME_SEEN = booleanPreferencesKey("welcome_seen")
        val KEY_CREDITS_BALANCE = intPreferencesKey("credits_balance")

        const val DEFAULT_SYSTEM_PROMPT = """You are Omni, an AI assistant that controls an Android device on behalf of the user.
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
- open_app: {"package": "com.example.app", "name": "App Label"}  // ALWAYS copy the exact package from the Installed apps list AND include the label as "name". If unsure of the package, use {"name": "YouTube"} alone and Omni will resolve it.
- open_url: {"url": "https://example.com"}
- wait: {"ms": 2000}
- done: {"success": true|false, "reason": "explanation"}

The user's goal comes from voice recognition which may mishear app names (e.g. "eye foods" = iFood, "what's up" = WhatsApp, "you tube" = YouTube). Match the user's words against the installed apps list to determine the correct app.

CRITICAL for open_app: NEVER invent a package name. Only use package strings that appear verbatim in the Installed apps list. Always include a "name" param with the app label from the list so Omni can self-correct if the package is wrong. If a requested app is not in the list, respond with done(success=false, reason="App not installed: ...").

Be precise, methodical, and always verify your actions match the screen state before acting."""
    }

    val apiKey: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY] ?: "" }
    val apiKeyClaude: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY_CLAUDE] ?: "" }
    val apiKeyOpenai: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY_OPENAI] ?: "" }
    val apiKeyOpenrouter: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY_OPENROUTER] ?: "" }
    val apiKeyGroq: Flow<String> = context.dataStore.data.map { it[KEY_API_KEY_GROQ] ?: "" }
    val llmProvider: Flow<String> = context.dataStore.data.map { it[KEY_LLM_PROVIDER] ?: "claude" }
    val llmModel: Flow<String> = context.dataStore.data.map { it[KEY_LLM_MODEL] ?: "claude-opus-4-6" }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD_ENABLED] ?: true }
    val wakeWord: Flow<String> = context.dataStore.data.map { it[KEY_WAKE_WORD] ?: "Hey Omni" }
    val ttsEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_TTS_ENABLED] ?: true }
    val maxSteps: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_STEPS] ?: 30 }
    val speechLanguage: Flow<String> = context.dataStore.data.map { it[KEY_SPEECH_LANGUAGE] ?: "" }
    val speechProvider: Flow<String> = context.dataStore.data.map { it[KEY_SPEECH_PROVIDER] ?: "builtin" }
    val deepgramApiKey: Flow<String> = context.dataStore.data.map { it[KEY_DEEPGRAM_API_KEY] ?: "" }
    val systemPrompt: Flow<String> = context.dataStore.data.map {
        it[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
    }
    val welcomeSeen: Flow<Boolean> = context.dataStore.data.map { it[KEY_WELCOME_SEEN] ?: false }
    val creditsBalance: Flow<Int> = context.dataStore.data.map { it[KEY_CREDITS_BALANCE] ?: 0 }

    suspend fun setApiKey(value: String) = context.dataStore.edit { it[KEY_API_KEY] = value }
    suspend fun setApiKeyClaude(value: String) = context.dataStore.edit { it[KEY_API_KEY_CLAUDE] = value }
    suspend fun setApiKeyOpenai(value: String) = context.dataStore.edit { it[KEY_API_KEY_OPENAI] = value }
    suspend fun setApiKeyOpenrouter(value: String) = context.dataStore.edit { it[KEY_API_KEY_OPENROUTER] = value }
    suspend fun setApiKeyGroq(value: String) = context.dataStore.edit { it[KEY_API_KEY_GROQ] = value }

    fun apiKeyForProvider(provider: String): Flow<String> = context.dataStore.data.map {
        when (provider) {
            "claude" -> it[KEY_API_KEY_CLAUDE] ?: it[KEY_API_KEY] ?: ""
            "openai" -> it[KEY_API_KEY_OPENAI] ?: ""
            "openrouter" -> it[KEY_API_KEY_OPENROUTER] ?: ""
            "groq" -> it[KEY_API_KEY_GROQ] ?: ""
            else -> it[KEY_API_KEY] ?: ""
        }
    }
    suspend fun setLlmProvider(value: String) = context.dataStore.edit { it[KEY_LLM_PROVIDER] = value }
    suspend fun setLlmModel(value: String) = context.dataStore.edit { it[KEY_LLM_MODEL] = value }
    suspend fun setWakeWordEnabled(value: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD_ENABLED] = value }
    suspend fun setWakeWord(value: String) = context.dataStore.edit { it[KEY_WAKE_WORD] = value }
    suspend fun setTtsEnabled(value: Boolean) = context.dataStore.edit { it[KEY_TTS_ENABLED] = value }
    suspend fun setMaxSteps(value: Int) = context.dataStore.edit { it[KEY_MAX_STEPS] = value }
    suspend fun setSpeechLanguage(value: String) = context.dataStore.edit { it[KEY_SPEECH_LANGUAGE] = value }
    suspend fun setSpeechProvider(value: String) = context.dataStore.edit { it[KEY_SPEECH_PROVIDER] = value }
    suspend fun setDeepgramApiKey(value: String) = context.dataStore.edit { it[KEY_DEEPGRAM_API_KEY] = value }
    suspend fun setSystemPrompt(value: String) = context.dataStore.edit { it[KEY_SYSTEM_PROMPT] = value }
    suspend fun setWelcomeSeen(value: Boolean) = context.dataStore.edit { it[KEY_WELCOME_SEEN] = value }
    suspend fun addCredits(delta: Int) = context.dataStore.edit {
        it[KEY_CREDITS_BALANCE] = (it[KEY_CREDITS_BALANCE] ?: 0) + delta
    }

}
