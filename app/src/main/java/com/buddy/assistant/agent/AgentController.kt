package com.buddy.assistant.agent

import android.content.Intent
import android.speech.tts.TextToSpeech
import com.buddy.assistant.BuddyApplication
import com.buddy.assistant.data.AgentAction
import com.buddy.assistant.data.AgentStatus
import com.buddy.assistant.llm.LLMClient
import com.buddy.assistant.service.BuddyAccessibilityService
import com.buddy.assistant.service.BuddyOverlayService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale

class AgentController private constructor(private val app: BuddyApplication) {

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
        // Skip the floating overlay while Buddy's own UI is in the foreground —
        // the main screen already shows the current state natively, and the pill
        // would cover the Cancel / Stop task CTA at the bottom.
        if (isAppInForeground()) return
        try {
            app.startForegroundService(Intent(app, BuddyOverlayService::class.java).apply {
                action = BuddyOverlayService.ACTION_SHOW
            })
        } catch (_: Exception) {}
    }

    private fun isAppInForeground(): Boolean = try {
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        am.appTasks.any { it.taskInfo?.topActivity?.packageName == app.packageName }
    } catch (_: Exception) {
        false
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
        val service = BuddyAccessibilityService.instance
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

        fun getInstance(app: BuddyApplication): AgentController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentController(app).also { INSTANCE = it }
            }
        }
    }
}
