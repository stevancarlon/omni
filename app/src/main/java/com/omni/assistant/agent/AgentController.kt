package com.omni.assistant.agent

import android.content.Intent
import android.util.Log
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
    private var terminalResetJob: Job? = null
    private var tts: TextToSpeech? = null

    private val _status = MutableStateFlow<AgentStatus>(AgentStatus.Idle)
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _currentThink = MutableStateFlow("")
    val currentThink: StateFlow<String> = _currentThink.asStateFlow()

    private val _speechLevel = MutableStateFlow(0f)
    val speechLevel: StateFlow<Float> = _speechLevel.asStateFlow()

    private val _ttsActive = MutableStateFlow(false)
    val ttsActive: StateFlow<Boolean> = _ttsActive.asStateFlow()

    /** Pending memory awaiting user confirmation. */
    data class PendingMemory(val goal: String, val actions: List<Map<String, Any?>>, val appContext: String, val steps: Int)
    private val _pendingMemory = MutableStateFlow<PendingMemory?>(null)
    val pendingMemory: StateFlow<PendingMemory?> = _pendingMemory.asStateFlow()

    fun confirmMemory(success: Boolean) {
        val pending = _pendingMemory.value ?: return
        _pendingMemory.value = null
        if (success) {
            scope.launch {
                try {
                    llmClient.saveMemory(pending.goal, pending.actions, pending.appContext, pending.steps)
                } catch (e: Exception) {
                    Log.w("AgentController", "Memory save failed: ${e.message}")
                }
            }
        }
    }

    fun dismissMemory() {
        _pendingMemory.value = null
    }

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setSpeechRate(1.1f)
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { _ttsActive.value = true }
                    override fun onDone(utteranceId: String?) { _ttsActive.value = false }
                    override fun onError(utteranceId: String?) { _ttsActive.value = false }
                })
            }
        }
    }

    fun onWakeWordDetected() {
        cancelTerminalReset()
        _status.value = AgentStatus.VoiceListening
        _currentThink.value = ""
        _speechLevel.value = 0f
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

    fun onSpeechLevelChanged(level: Float) {
        _speechLevel.value = level.coerceIn(0f, 1f)
    }

    private fun playTone() {
        try {
            val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
            toneGen.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ toneGen.release() }, 300)
        } catch (_: Exception) {}
    }

    fun onCommandReceived(command: String, candidates: List<String> = listOf(command)) {
        cancelTerminalReset()
        addLog("User: $command")
        _currentThink.value = command
        _speechLevel.value = 0f
        showOverlay()
        agentJob?.cancel()
        agentJob = scope.launch { runAgentLoop(command, candidates) }
    }

    private fun showOverlay() {
        try {
            app.startForegroundService(Intent(app, OmniOverlayService::class.java).apply {
                action = OmniOverlayService.ACTION_SHOW
            })
        } catch (_: Exception) {}
    }

    fun reset() {
        agentJob?.cancel()
        agentJob = null
        cancelTerminalReset()
        _status.value = AgentStatus.Idle
        _speechLevel.value = 0f
    }

    /** Debug-only — force a status for visual QA of orb states. */
    fun debugSetStatus(status: AgentStatus) {
        cancelTerminalReset()
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
        // Track recent actions for deduplication
        val recentActions = mutableListOf<String>()
        // Track executed actions for MobileRAG memory
        val executedActions = mutableListOf<Map<String, Any?>>()
        // Track the primary app context (first non-Omni foreground app)
        var targetApp = ""

        _status.value = AgentStatus.Processing(goal)
        addLog("Starting task: $goal")

        // Query MobileRAG for similar past runs
        val pastGuidance = try {
            llmClient.searchMemories(goal)
        } catch (e: Exception) {
            Log.w("AgentController", "Memory search failed: ${e.message}")
            null
        }

        for (step in 1..maxSteps) {
            if (OmniAccessibilityService.suspended.value) {
                addLog("Paused - sensitive app detected, waiting...")
                _status.value = AgentStatus.Executing(goal, step, maxSteps, "Paused (banking app)")
                OmniAccessibilityService.suspended.first { !it }
                addLog("Resumed")
            }

            // ── Auto-recovery: force actions when stuck ──
            if (consecutiveUnchanged >= 5) {
                addLog("Auto-recovery: pressing HOME after $consecutiveUnchanged stuck steps")
                withContext(Dispatchers.Main) { service.executeAction(AgentAction.PressHome) }
                history.add(mapOf("role" to "user", "content" to "System auto-recovery: pressed HOME because screen was unchanged for $consecutiveUnchanged steps."))
                recentActions.add("press_home")
                consecutiveUnchanged = 0
                delay(1000)
                continue
            }
            if (consecutiveUnchanged >= 3) {
                addLog("Auto-recovery: pressing BACK after $consecutiveUnchanged stuck steps")
                withContext(Dispatchers.Main) { service.executeAction(AgentAction.PressBack) }
                history.add(mapOf("role" to "user", "content" to "System auto-recovery: pressed BACK because screen was unchanged for $consecutiveUnchanged steps."))
                recentActions.add("press_back")
                consecutiveUnchanged = 0
                delay(500)
                continue
            }

            // Capture annotated screenshot (Set-of-Marks) + legend
            val annotated = try {
                withContext(Dispatchers.Main) { service.captureAnnotatedScreen() }
            } catch (e: Exception) {
                Log.w("AgentController", "Annotated screen failed: ${e.message}")
                null
            }
            // Fallback to text-only if screenshot fails
            val screenLegend = annotated?.legend
                ?: withContext(Dispatchers.Main) { service.getCompactScreenDescription() }

            val screenHash = screenLegend.hashCode().toString()
            consecutiveUnchanged = trackScreenChanges(screenHash, lastScreenHash, consecutiveUnchanged)

            // Only send screenshot when screen changed or on first step (saves ~4K tokens/step)
            val screenshot = if (screenHash != lastScreenHash || step == 1) annotated?.screenshotBase64 else null
            lastScreenHash = screenHash

            val fgApp = OmniAccessibilityService.foregroundPackage.value
            // Track the target app (first non-Omni app we enter)
            if (targetApp.isBlank() && fgApp.isNotBlank() && fgApp != "com.omni.assistant") {
                targetApp = fgApp
            }

            _status.value = AgentStatus.Executing(goal, step, maxSteps, "Thinking...")
            addLog("Step $step: ${screenLegend.lines().size} marks in $fgApp${if (screenshot != null) " + screenshot" else ""}")

            // Build context-aware warnings + self-reflection check
            val warnings = buildStepWarnings(consecutiveUnchanged, step, maxSteps, recentActions)

            val response = try {
                llmClient.getNextAction(goal, screenLegend, history, voiceCandidates, warnings, screenshot, step == 1, fgApp, pastGuidance)
            } catch (e: Exception) {
                addLog("LLM error: ${e.message}")
                fail("LLM error: ${e.message}")
                return
            }

            addLog("Think: ${response.think}")
            addLog("Action: ${response.actionType}")
            _currentThink.value = response.think.ifBlank { response.actionType }
            // Store compressed history (think + action type only, not raw JSON)
            history.add(mapOf("role" to "assistant", "content" to response.rawJson))
            _status.value = AgentStatus.Executing(goal, step, maxSteps, response.actionType)

            if (response.action is AgentAction.Done) {
                val done = response.action as AgentAction.Done
                complete(done.success, done.reason)
                addLog("Done: ${done.reason}")
                speak(done.reason)
                // Offer user to confirm memory save
                if (executedActions.isNotEmpty()) {
                    _pendingMemory.value = PendingMemory(goal, executedActions.toList(), targetApp.ifBlank { fgApp }, step)
                }
                return
            }

            // ── Action deduplication ──
            val actionKey = actionSignature(response.action)
            if (recentActions.count { it == actionKey } >= 2) {
                addLog("BLOCKED duplicate action: $actionKey — forcing press_back")
                withContext(Dispatchers.Main) { service.executeAction(AgentAction.PressBack) }
                history.add(mapOf("role" to "user", "content" to
                    "System blocked your action '$actionKey' because you already tried it twice. Pressed BACK instead. Try something completely different."))
                recentActions.add("BLOCKED:$actionKey")
                delay(500)
                continue
            }
            recentActions.add(actionKey)
            if (recentActions.size > 5) recentActions.removeAt(0)

            val result = withContext(Dispatchers.Main) { service.executeAction(response.action) }
            addLog("Result: ${result.description}")
            // Track for MobileRAG memory
            if (result.success) {
                executedActions.add(mapOf("action" to response.actionType, "params" to response.rawJson.take(200)))
            }

            // Keep feedback factual: this summary is from the screen used to
            // choose the action, not a fresh post-action capture.
            val screenSummary = buildScreenSummary(screenLegend)
            val feedback = buildString {
                append("Action result: ${result.description}.")
                if (!result.success) {
                    append(" You must try a different approach.")
                }
                append("\nScreen before action: $screenSummary")
            }
            history.add(mapOf("role" to "user", "content" to feedback))

            // Adaptive delay based on action type
            val delayMs = when (response.action) {
                is AgentAction.OpenApp, is AgentAction.OpenUrl -> 1500L
                is AgentAction.PressBack, is AgentAction.PressHome, is AgentAction.PressRecents -> 300L
                else -> 500L
            }
            delay(delayMs)
        }

        complete(false, "Reached maximum steps ($maxSteps)")
        speak("I reached the step limit and couldn't complete the task.")
    }

    private fun actionSignature(action: AgentAction): String = when (action) {
        is AgentAction.Tap -> "tap:${action.nodeId ?: "${action.x},${action.y}"}"
        is AgentAction.TypeText -> "type:${action.nodeId ?: "${action.x},${action.y}"}:${action.text.take(20)}"
        is AgentAction.Swipe -> "swipe:${action.direction}"
        is AgentAction.Scroll -> "scroll:${action.direction}:${action.nodeId}"
        is AgentAction.PressBack -> "press_back"
        is AgentAction.PressHome -> "press_home"
        is AgentAction.PressRecents -> "press_recents"
        is AgentAction.OpenApp -> "open_app:${action.packageName}"
        is AgentAction.OpenUrl -> "open_url:${action.url.take(30)}"
        is AgentAction.Wait -> "wait:${action.ms}"
        is AgentAction.Done -> "done"
    }

    private fun buildStepWarnings(consecutiveUnchanged: Int, step: Int, maxSteps: Int, recentActions: List<String>): String? {
        val warnings = mutableListOf<String>()
        if (consecutiveUnchanged >= 2) {
            warnings.add("[STUCK $consecutiveUnchanged steps] Screen unchanged. Try a different action.")
        }
        if (step >= maxSteps - 3) {
            warnings.add("[STEP $step/$maxSteps] Finish now: done(success=true) if goal achieved, done(success=false) if not.")
        }
        val tried = recentActions.takeLast(3)
        if (tried.isNotEmpty()) {
            warnings.add("[Recent actions: ${tried.joinToString(", ")}] — do NOT repeat these.")
        }
        // Self-reflection check every 5 steps
        if (step > 1 && step % 5 == 0) {
            warnings.add("[REFLECT] Step $step — stop and ask yourself: Am I still working toward the original goal? If the goal is already achieved, use done(success=true). If I'm going in circles, use done(success=false).")
        }
        return warnings.joinToString("\n").ifBlank { null }
    }

    private fun buildScreenSummary(screen: String): String {
        val lines = screen.lines().filter { it.isNotBlank() }
        val count = lines.size
        val keyTexts = lines.take(6).mapNotNull { line ->
            val match = Regex("text=\"([^\"]+)\"").find(line)
            match?.groupValues?.get(1)?.take(40)
        }
        val summary = buildString {
            append("$count elements visible")
            if (keyTexts.isNotEmpty()) {
                append(". Key text: ${keyTexts.joinToString(", ") { "\"$it\"" }}")
            }
        }
        return summary.take(300)
    }

    private fun trackScreenChanges(current: String, previous: String, unchanged: Int): Int {
        if (current != previous) return 0
        val count = unchanged + 1
        if (count >= 3) addLog("Screen unchanged for $count steps — agent may be stuck")
        return count
    }

    private fun fail(message: String) {
        cancelTerminalReset()
        _status.value = AgentStatus.Error(message)
        scheduleTerminalReset { _status.value is AgentStatus.Error }
    }

    private fun complete(success: Boolean, reason: String) {
        cancelTerminalReset()
        _status.value = AgentStatus.Done(success, reason)
        scheduleTerminalReset { _status.value is AgentStatus.Done }
    }

    private fun scheduleTerminalReset(isStillTerminalState: () -> Boolean) {
        terminalResetJob = scope.launch {
            delay(TERMINAL_VISIBLE_MS)
            if (isStillTerminalState()) {
                _status.value = AgentStatus.Idle
            }
        }
    }

    private fun cancelTerminalReset() {
        terminalResetJob?.cancel()
        terminalResetJob = null
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
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "omni_agent")
            }
        }
    }

    private fun addLog(entry: String) {
        _log.update { (it + "[${System.currentTimeMillis()}] $entry").takeLast(100) }
    }

    companion object {
        private const val TERMINAL_VISIBLE_MS = 3000L

        @Volatile
        private var INSTANCE: AgentController? = null

        fun getInstance(app: OmniApplication): AgentController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AgentController(app).also { INSTANCE = it }
            }
        }
    }
}
