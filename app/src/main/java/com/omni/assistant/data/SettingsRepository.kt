package com.omni.assistant.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.omni.assistant.BuildConfig
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
        val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        val KEY_BACKEND_URL = stringPreferencesKey("backend_url")
        val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        val KEY_WAKE_WORD = stringPreferencesKey("wake_word")
        val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val KEY_MAX_STEPS = intPreferencesKey("max_steps")
        val KEY_SPEECH_LANGUAGE = stringPreferencesKey("speech_language")
        val KEY_SPEECH_PROVIDER = stringPreferencesKey("speech_provider")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val KEY_WELCOME_SEEN = booleanPreferencesKey("welcome_seen")
        val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val KEY_ACCOUNT_EMAIL = stringPreferencesKey("account_email")
        val KEY_ACCOUNT_NAME = stringPreferencesKey("account_name")
        val KEY_SUBSCRIPTION_STATUS = stringPreferencesKey("subscription_status")
        val KEY_SUBSCRIPTION_PLAN = stringPreferencesKey("subscription_plan")

        val DEFAULT_BACKEND_URL = BuildConfig.DEFAULT_BACKEND_URL
        private const val LEGACY_DEFAULT_BACKEND_URL = "http://192.168.2.111:4000"

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

    val authToken: Flow<String> = context.dataStore.data.map { it[KEY_AUTH_TOKEN] ?: "" }
    val backendUrl: Flow<String> = context.dataStore.data.map {
        val storedBackendUrl = it[KEY_BACKEND_URL]?.trim()
        when {
            storedBackendUrl.isNullOrBlank() -> DEFAULT_BACKEND_URL
            storedBackendUrl == LEGACY_DEFAULT_BACKEND_URL -> DEFAULT_BACKEND_URL
            else -> storedBackendUrl
        }
    }
    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WAKE_WORD_ENABLED] ?: true }
    val wakeWord: Flow<String> = context.dataStore.data.map { it[KEY_WAKE_WORD] ?: "Hey Omni" }
    val ttsEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_TTS_ENABLED] ?: true }
    val maxSteps: Flow<Int> = context.dataStore.data.map { it[KEY_MAX_STEPS] ?: 30 }
    val speechLanguage: Flow<String> = context.dataStore.data.map { it[KEY_SPEECH_LANGUAGE] ?: "" }
    val speechProvider: Flow<String> = context.dataStore.data.map { it[KEY_SPEECH_PROVIDER] ?: "deepgram" }
    val systemPrompt: Flow<String> = context.dataStore.data.map {
        it[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT
    }
    val welcomeSeen: Flow<Boolean> = context.dataStore.data.map { it[KEY_WELCOME_SEEN] ?: false }
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { it[KEY_ONBOARDING_COMPLETE] ?: false }
    val accountEmail: Flow<String> = context.dataStore.data.map { it[KEY_ACCOUNT_EMAIL] ?: "" }
    val accountName: Flow<String> = context.dataStore.data.map { it[KEY_ACCOUNT_NAME] ?: "" }
    val subscriptionStatus: Flow<String> = context.dataStore.data.map { it[KEY_SUBSCRIPTION_STATUS] ?: "inactive" }
    val subscriptionPlan: Flow<String> = context.dataStore.data.map { it[KEY_SUBSCRIPTION_PLAN] ?: "free" }

    suspend fun setAuthToken(value: String) = context.dataStore.edit { it[KEY_AUTH_TOKEN] = value }
    suspend fun setBackendUrl(value: String) = context.dataStore.edit { it[KEY_BACKEND_URL] = value }
    suspend fun setWakeWordEnabled(value: Boolean) = context.dataStore.edit { it[KEY_WAKE_WORD_ENABLED] = value }
    suspend fun setWakeWord(value: String) = context.dataStore.edit { it[KEY_WAKE_WORD] = value }
    suspend fun setTtsEnabled(value: Boolean) = context.dataStore.edit { it[KEY_TTS_ENABLED] = value }
    suspend fun setMaxSteps(value: Int) = context.dataStore.edit { it[KEY_MAX_STEPS] = value }
    suspend fun setSpeechLanguage(value: String) = context.dataStore.edit { it[KEY_SPEECH_LANGUAGE] = value }
    suspend fun setSpeechProvider(value: String) = context.dataStore.edit { it[KEY_SPEECH_PROVIDER] = value }
    suspend fun setSystemPrompt(value: String) = context.dataStore.edit { it[KEY_SYSTEM_PROMPT] = value }
    suspend fun setWelcomeSeen(value: Boolean) = context.dataStore.edit { it[KEY_WELCOME_SEEN] = value }
    suspend fun setOnboardingComplete(value: Boolean) = context.dataStore.edit {
        it[KEY_ONBOARDING_COMPLETE] = value
    }

    suspend fun setAccountSession(
        authToken: String,
        email: String,
        name: String,
        subscriptionStatus: String,
        subscriptionPlan: String,
    ) = context.dataStore.edit {
        it[KEY_AUTH_TOKEN] = authToken
        it[KEY_ACCOUNT_EMAIL] = email
        it[KEY_ACCOUNT_NAME] = name
        it[KEY_SUBSCRIPTION_STATUS] = subscriptionStatus
        it[KEY_SUBSCRIPTION_PLAN] = subscriptionPlan
        it[KEY_WELCOME_SEEN] = true
    }

    suspend fun setSubscriptionState(status: String, plan: String) = context.dataStore.edit {
        it[KEY_SUBSCRIPTION_STATUS] = status
        it[KEY_SUBSCRIPTION_PLAN] = plan
    }

    suspend fun clearAccountSession() = context.dataStore.edit {
        it[KEY_AUTH_TOKEN] = ""
        it[KEY_ACCOUNT_EMAIL] = ""
        it[KEY_ACCOUNT_NAME] = ""
        it[KEY_SUBSCRIPTION_STATUS] = "inactive"
        it[KEY_SUBSCRIPTION_PLAN] = "free"
        it[KEY_ONBOARDING_COMPLETE] = false
        it[KEY_WELCOME_SEEN] = false
    }

}
