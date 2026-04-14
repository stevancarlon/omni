package com.buddy.assistant.agent

import android.speech.tts.TextToSpeech
import com.buddy.assistant.BuddyApplication
import com.buddy.assistant.data.AgentAction
import com.buddy.assistant.data.AgentStatus
import com.buddy.assistant.llm.LLMClient
import com.buddy.assistant.service.BuddyAccessibilityService
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

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
            }
        }
    }

    fun onWakeWordDetected() {
        _status.value = AgentStatus.VoiceListening
        speak("Yes?")
    }

    fun onCommandReceived(command: String) {
        addLog("User: $command")
        agentJob?.cancel()
        agentJob = scope.launch { runAgentLoop(command) }
    }

    fun reset() {
        agentJob?.cancel()
        agentJob = null
        _status.value = AgentStatus.Idle
    }

    // ─── Agent Loop ──────────────────────────────────────────────────────────

    private suspend fun runAgentLoop(goal: String) {
        val service = BuddyAccessibilityService.instance
        if (service == null) {
            fail("Accessibility service not enabled. Please enable Buddy in Accessibility Settings.")
            speak("Please enable Buddy in Accessibility Settings first.")
            return
        }

        val maxSteps = app.settingsRepository.maxSteps.first()
        val history = mutableListOf<Map<String, String>>()
        var consecutiveUnchanged = 0
        var lastScreenHash = ""

        _status.value = AgentStatus.Processing(goal)
        speak("On it!")
        addLog("Starting task: $goal")

        for (step in 1..maxSteps) {
            val screen = withContext(Dispatchers.Main) { service.getCompactScreenDescription() }
            val screenHash = screen.hashCode().toString()

            consecutiveUnchanged = trackScreenChanges(screenHash, lastScreenHash, consecutiveUnchanged)
            lastScreenHash = screenHash

            _status.value = AgentStatus.Executing(goal, step, maxSteps, "Thinking...")
            addLog("Step $step: Reading screen (${screen.lines().size} elements)")

            val response = try {
                llmClient.getNextAction(goal, screen, history)
            } catch (e: Exception) {
                addLog("LLM error: ${e.message}")
                fail("LLM error: ${e.message}")
                return
            }

            addLog("Think: ${response.think}")
            addLog("Action: ${response.actionType}")
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

            delay(800)
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
