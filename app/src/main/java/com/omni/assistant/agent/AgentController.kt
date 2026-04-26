package com.omni.assistant.agent

import android.content.Intent
import android.speech.tts.TextToSpeech
import com.omni.assistant.OmniApplication
import com.omni.assistant.data.AgentAction
import com.omni.assistant.data.AgentStatus
import com.omni.assistant.llm.LLMClient
import com.omni.assistant.service.OmniAccessibilityService
import com.omni.assistant.service.OmniOverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

class AgentController private constructor(private val app: OmniApplication) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val llmClient = LLMClient(app)
    private var agentJob: Job? = null
    private var tts: TextToSpeech? = null

    private val _status = MutableStateFlow<AgentStatus>(AgentStatus.Idle)
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _currentThink = MutableStateFlow("")
    val currentThink: StateFlow<String> = _currentThink.asStateFlow()

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.1f)
            }
        }
    }

    fun onWakeWordDetected() {
        _status.value = AgentStatus.VoiceListening
        _currentThink.value = ""
        showOverlay()
        playTone()
        scope.launch {
            if (!hasActiveSubscription()) {
                fail("Subscription required. Please upgrade to use Omni.")
                return@launch
            }
        }
    }

    fun onTranscriptionUpdated(text: String) {
        _currentThink.value = text
    }

    private fun playTone() {
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ toneGen.release() }, 300)
        } catch (_: Exception) {}
    }

    fun onCommandReceived(command: String, candidates: List<String> = listOf(command)) {
        addLog("User: $command")
        _currentThink.value = command
        showOverlay()
        agentJob?.cancel()
        agentJob = scope.launch { runAgentLoop(command, candidates) }
    }

    private fun showOverlay() {
        // Overlay must appear for every agent run so the user can see state
        // and stop the task after Omni navigates away to another app.
        try {
            app.startForegroundService(Intent(app, OmniOverlayService::class.java).apply {
                action = OmniOverlayService.ACTION_SHOW
            })
        } catch (_: Exception) {}
    }

    fun reset() {
        agentJob?.cancel()
        agentJob = null
        _status.value = AgentStatus.Idle
    }

    /** Debug-only — force a status for visual QA of orb states. */
    fun debugSetStatus(status: AgentStatus) {
        _status.value = status
    }

    // ─── Agent Loop ──────────────────────────────────────────────────────────

    private suspend fun runAgentLoop(goal: String, voiceCandidates: List<String> = listOf(goal)) {
        if (!hasActiveSubscription()) {
            fail("Subscription required. Please upgrade to use Omni.")
            speak("Please upgrade to use Omni.")
            return
        }

        val service = OmniAccessibilityService.instance
        if (service == null) {
            fail("Accessibility service not enabled. Please enable Omni in Accessibility Settings.")
            speak("Please enable Omni in Accessibility Settings first.")
            return
        }

        val maxSteps = app.settingsRepository.maxSteps.first()
        val history = mutableListOf<Map<String, String>>()
        var consecutiveUnchanged = 0
        var lastScreenHash = ""

        _status.value = AgentStatus.Processing(goal)
        addLog("Starting task: $goal")

        for (step in 1..maxSteps) {
            if (OmniAccessibilityService.suspended.value) {
                addLog("Paused - sensitive app detected, waiting...")
                _status.value = AgentStatus.Executing(goal, step, maxSteps, "Paused (banking app)")
                OmniAccessibilityService.suspended.first { !it }
                addLog("Resumed")
            }

            val screen = withContext(Dispatchers.Main) { service.getCompactScreenDescription() }
            val screenHash = screen.hashCode().toString()

            consecutiveUnchanged = trackScreenChanges(screenHash, lastScreenHash, consecutiveUnchanged)
            lastScreenHash = screenHash

            _status.value = AgentStatus.Executing(goal, step, maxSteps, "Thinking...")
            addLog("Step $step: Reading screen (${screen.lines().size} elements)")

            val response = try {
                llmClient.getNextAction(goal, screen, history, voiceCandidates)
            } catch (e: Exception) {
                addLog("LLM error: ${e.message}")
                fail("LLM error: ${e.message}")
                return
            }

            addLog("Think: ${response.think}")
            addLog("Action: ${response.actionType}")
            _currentThink.value = response.think.ifBlank { response.actionType }
            history.add(mapOf("role" to "assistant", "content" to response.rawJson))
            _status.value = AgentStatus.Executing(goal, step, maxSteps, response.actionType)

            if (response.action is AgentAction.Done) {
                val done = response.action as AgentAction.Done
                _status.value = AgentStatus.Done(done.success, done.reason)
                addLog("Done: ${done.reason}")
                speak(done.reason)
                return
            }

            val success = withContext(Dispatchers.Main) { service.executeAction(response.action) }
            val resultText = if (success) "success" else "failed"
            addLog("Result: $resultText")

            history.add(mapOf("role" to "user", "content" to "Action result: $resultText. Continue working towards the goal."))

            delay(400)
        }

        _status.value = AgentStatus.Done(false, "Reached maximum steps ($maxSteps)")
        speak("I reached the step limit and couldn't complete the task.")
    }

    private fun trackScreenChanges(current: String, previous: String, unchanged: Int): Int {
        if (current != previous) return 0
        val count = unchanged + 1
        if (count >= 3) addLog("Screen unchanged for $count steps — agent may be stuck")
        return count
    }

    private fun fail(message: String) {
        _status.value = AgentStatus.Error(message)
    }

    private suspend fun hasActiveSubscription(): Boolean {
        val authToken = app.settingsRepository.authToken.first()
        if (authToken.isBlank()) return false

        runCatching { app.authRepository.refreshSession() }
            .onSuccess { session ->
                app.settingsRepository.setAccountSession(
                    authToken = session.authToken,
                    email = session.email,
                    name = session.name,
                    subscriptionStatus = session.subscriptionStatus,
                    subscriptionPlan = session.subscriptionPlan,
                )
            }
            .onFailure {
                addLog("Subscription refresh failed: ${it.message}")
            }

        return app.settingsRepository.subscriptionStatus.first() == "active" &&
            app.settingsRepository.subscriptionPlan.first() != "free"
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun speak(text: String) {
        scope.launch {
            val ttsEnabled = app.settingsRepository.ttsEnabled.first()
            if (!ttsEnabled) return@launch
            withContext(Dispatchers.Main) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun addLog(entry: String) {
        _log.update { (it + "[${System.currentTimeMillis()}] $entry").takeLast(100) }
    }

    companion object {
        @Volatile
        private var INSTANCE: AgentController? = null

        fun getInstance(app: OmniApplication): AgentController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentController(app).also { INSTANCE = it }
            }
        }
    }
}
