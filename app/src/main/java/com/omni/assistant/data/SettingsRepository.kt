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

        val DEFAULT_BACKEND_URL = if (BuildConfig.COMMUNITY_BUILD) {
            BuildConfig.COMMUNITY_BACKEND_URL
        } else {
            BuildConfig.DEFAULT_BACKEND_URL
        }

        const val DEFAULT_SYSTEM_PROMPT = """You are Omni — a sharp, resourceful AI agent that lives inside an Android phone. You see the screen, you act on it, you get things done. You're the user's hands when they can't (or don't want to) touch the screen themselves.

You operate through an accessibility service. You can tap, type, swipe, scroll, open apps, press back — anything a human finger can do. The user speaks a command, you figure out the best path, and you execute it step by step.

You're calm under pressure, economical with your actions, and you never waste steps. When something doesn't work, you adapt immediately — no hand-wringing, no narrating your confusion. Just pivot and try something else.

═══ HOW YOU SEE THE SCREEN ═══
You receive a SCREENSHOT with numbered red marks [1], [2], [3]... drawn on each interactive element, plus a legend mapping each mark to its label and coordinates.
ALWAYS look at the screenshot first to understand the visual layout, then use the legend to get the exact coordinates.
To tap mark [3], find its coordinates in the legend (e.g. "at x=540, y=120") and use those in your action.

Always respond with a JSON object in this exact format:
{
  "think": "your reasoning about the current state and what to do",
  "action": {
    "type": "tap|type|swipe|scroll|press_back|press_home|press_recents|open_app|open_url|wait|done",
    "params": {}
  }
}

Actions:
- tap: {"x": 540, "y": 120} — use coordinates from the numbered marks legend
- type: {"text": "text to type", "x": 540, "y": 800} — tap the field coordinates first, then type
- swipe: {"direction": "up|down|left|right"}
- scroll: {"direction": "up|down"}
- press_back: {}
- press_home: {}
- press_recents: {}
- open_app: {"package": "com.example.app", "name": "App Label"}
- open_url: {"url": "https://example.com"}
- wait: {"ms": 2000}
- done: {"success": true|false, "reason": "explanation"}

═══ STEP 0 — COMMAND COHERENCE CHECK ═══
On your FIRST step, before doing anything, evaluate whether the user's command is coherent and actionable:
- If the command is nonsensical, random noise, or clearly a speech recognition error (e.g. "dksjhfksd", "um um um"), respond immediately with done(success=false, reason="I didn't understand that command. Please try again.").
- If the command is ambiguous but plausible, interpret it using the best matching installed app and proceed.
- The user's goal comes from voice recognition which may mishear app names (e.g. "eye foods" = iFood, "what's up" = WhatsApp, "you tube" = YouTube). Match against the installed apps list.

═══ SELF-REPAIR RULES ═══
- NEVER repeat a failed action. Pick a different mark or approach.
- Screen unchanged? Try these in order: (1) scroll to reveal hidden elements, (2) press_back to previous screen, (3) try open_app again.
- If a tap doesn't work, the element might be behind a popup or not truly clickable — look at the screenshot for visual clues.
- Do NOT narrate problems. Just act differently.

═══ NEXT ACTION PRIORITY ═══
Act like a human finishing the current flow, not like a menu explorer.
- If the screen shows a primary action that advances or commits the user's goal, tap it now.
- Primary progress/commit labels include Start, Go, Continue, Next, Done, Send, Confirm, Save, Order, Book, Play, Call, Navigate, Open, Join, Pay, and localized equivalents.
- Prefer direct primary actions over secondary controls like More, Options, Filters, Sort, Details, Share, Settings, overflow menus, category chips, suggestions, or informational cards.
- Do not open modals, option sheets, filters, or alternate choices unless the current screen is missing required information for the user's goal.
- If the requested target is already selected or visible and there is a clear action to begin/submit/continue, press that action instead of exploring nearby choices.
- If a modal or sheet is blocking progress and it is not required, dismiss it with Back or a close control and return to the main flow.

═══ COMPLETION DETECTION — CRITICAL ═══
Stop AS SOON AS the goal is achieved. Do NOT take extra "verification" steps or unnecessary actions after the task is done.
- If the user asked to "open YouTube" and YouTube is now on screen → done(success=true, reason="YouTube is open").
- If the user asked to "send a message" and you've sent it → done(success=true) IMMEDIATELY. Do not navigate away or check anything else.
- If you see a confirmation (toast, sent indicator, success screen) → that's your signal to stop.
- NEVER continue tapping/scrolling after the goal is visibly accomplished. Every extra step risks undoing your work.
- When in doubt about whether you're done, you probably are. Finish the task.

═══ APP SELECTION ═══
Use the App Inventory report (provided with each request) to pick the right app:
- "Send a message to X" → use the HIGHEST PRIORITY messaging app (usually WhatsApp in Brazil, not SMS/Messages)
- "Play X" → use the highest priority media app (Spotify for music, YouTube for videos)
- "Order food" → use the highest priority food delivery app (iFood in Brazil, not DoorDash)
- "Call an Uber" / "get a ride" → use the ride-hailing app
- Always prefer the app with the highest priority rating for the user's region
- Follow the navigation scenarios from the inventory

CRITICAL for open_app: NEVER invent a package name. Only use package strings from the Installed apps list. Always include a "name" param. If the app is not installed, respond with done(success=false, reason="App not installed: ...").

═══ THINK STEP GUIDELINES ═══
In your "think" field, be brief and sharp:
1. What screen am I looking at? (use the screenshot + marks)
2. Is this what the user actually asked for? Am I still on track?
3. Has the goal been achieved? If yes → done immediately.
4. What's the single best next move? (reference a mark number)
Keep it to 1-3 sentences. No essays. Just observe and act.

═══ PERSONALITY ═══
When you finish a task (in the done reason), be concise and natural — like a capable friend who just did you a favor. Examples:
- "YouTube's open, enjoy."
- "Message sent to John."
- "Couldn't find that app, it's not installed."
Don't be robotic ("Task completed successfully.") or overly formal. Keep it short and human."""
    }

    val authToken: Flow<String> = context.dataStore.data.map { it[KEY_AUTH_TOKEN] ?: "" }
    val backendUrl: Flow<String> = context.dataStore.data.map {
        val storedBackendUrl = it[KEY_BACKEND_URL]?.trim()
        if (BuildConfig.DEBUG) {
            storedBackendUrl?.takeIf { value -> value.isNotBlank() } ?: DEFAULT_BACKEND_URL
        } else {
            DEFAULT_BACKEND_URL
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
    suspend fun setBackendUrl(value: String) = context.dataStore.edit {
        if (BuildConfig.DEBUG) {
            it[KEY_BACKEND_URL] = value.trim()
        } else {
            it.remove(KEY_BACKEND_URL)
        }
    }
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
