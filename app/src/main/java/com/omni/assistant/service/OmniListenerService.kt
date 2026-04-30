package com.omni.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omni.assistant.OmniApplication
import com.omni.assistant.agent.AgentController
import com.omni.assistant.data.AgentStatus
import com.omni.assistant.speech.AudioRecorder
import com.omni.assistant.speech.DeepgramClient
import com.omni.assistant.speech.DeepgramSessionClient
import com.omni.assistant.speech.ListenMode
import com.omni.assistant.speech.WhisperClient
import com.omni.assistant.ui.MainActivity
import com.omni.assistant.util.SpeechCorrector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.net.URLEncoder

class OmniListenerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var deepgramClient: DeepgramClient? = null
    private var commandRecorder: AudioRecorder? = null
    private var isListeningForWakeWord = false
    private var isListeningForCommand = false
    private var startedFromWakeWord = false
    private var useDeepgramForAll = false
    private var wakeWordEnabled = true
    private var speechProvider = "deepgram"
    private var deepgramRequestId = 0
    private var activeDeepgramMode: ListenMode? = null
    private var activeCommandId = 0
    private var returnToWakeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var agentController: AgentController

    // Cached settings
    private var cachedSpeechLanguage = ""
    private var cachedWakeWord = "hey omni"

    // Pre-provisioned command session — fetched during wake word mode
    // so there's no delay when the wake word fires.
    private var preProvisionedSession: com.omni.assistant.speech.DeepgramSession? = null
    private var preProvisionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        agentController = AgentController.getInstance(application as OmniApplication)

        // Keep CPU awake so the service survives background
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omni:listener").apply {
            acquire()
        }

        startForeground(NOTIF_ID, buildNotification("Omni is listening..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            reloadSettings()
            when (intent?.action) {
                ACTION_START_COMMAND_LISTENING -> {
                    cancelReturnToWake()
                    startedFromWakeWord = false
                    agentController.onWakeWordDetected()
                    startCommandListening()
                }
                ACTION_START_WAKE_WORD -> {
                    cancelReturnToWake()
                    startedFromWakeWord = true
                    startWakeWordListening()
                }
                ACTION_STOP -> shutdown()
                ACTION_STOP_COMMAND -> {
                    cancelReturnToWake()
                    activeCommandId += 1
                    isListeningForCommand = false
                    commandRecorder?.stop()
                    commandRecorder = null
                    agentController.reset()
                    startWakeWordListening()
                }
                else -> startWakeWordListening()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        wakeLock = null
        returnToWakeJob?.cancel()
        speechRecognizer?.destroy()
        deepgramClient?.stop()
        scope.cancel()
    }

    // ─── Settings ────────────────────────────────────────────────────────────

    private suspend fun reloadSettings() {
        val repo = (application as OmniApplication).settingsRepository
        cachedSpeechLanguage = repo.speechLanguage.first()
        cachedWakeWord = repo.wakeWord.first().lowercase()
        wakeWordEnabled = repo.wakeWordEnabled.first()
        speechProvider = repo.speechProvider.first().lowercase()
        useDeepgramForAll = repo.authToken.first().isNotBlank() && speechProvider == "deepgram"
        Log.d(
            TAG,
            "Settings: backendSpeech=$useDeepgramForAll, provider=$speechProvider, wakeEnabled=$wakeWordEnabled, lang=$cachedSpeechLanguage, wake=$cachedWakeWord"
        )
    }

    // ─── Listening Modes ─────────────────────────────────────────────────────

    private fun startWakeWordListening() {
        if (!wakeWordEnabled) {
            Log.d(TAG, "Wake word disabled in settings")
            updateNotification("Wake word disabled")
            isListeningForWakeWord = false
            isListeningForCommand = false
            speechRecognizer?.destroy()
            speechRecognizer = null
            deepgramClient?.stop()
            deepgramClient = null
            return
        }
        if (!useDeepgramForAll) {
            Log.d(TAG, "Skipping wake word until Deepgram speech is available. Use Start Listening button instead.")
            updateNotification("Deepgram required for wake word")
            isListeningForWakeWord = false
            isListeningForCommand = false
            speechRecognizer?.destroy()
            speechRecognizer = null
            deepgramClient?.stop()
            deepgramClient = null
            return
        }
        isListeningForWakeWord = true
        isListeningForCommand = false
        updateNotification("Listening for \"$cachedWakeWord\"...")
        commandRecorder?.stop()
        commandRecorder = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        startOrSwitchDeepgram(ListenMode.WAKE_WORD)
        preProvisionCommandSession()
    }

    private fun startCommandListening() {
        cancelReturnToWake()
        isListeningForWakeWord = false
        isListeningForCommand = true
        updateNotification("Listening... speak your command")

        // Stop wake word listener — mic can't be shared
        deepgramClient?.stop()
        deepgramClient = null
        activeDeepgramMode = null
        speechRecognizer?.destroy()
        speechRecognizer = null

        // Record audio locally, then send to Whisper for transcription
        commandRecorder = AudioRecorder(
            onAudioLevel = { level ->
                scope.launch {
                    if (isListeningForCommand) {
                        agentController.onSpeechLevelChanged(level)
                    }
                }
            },
            onSilenceTimeout = { audio ->
                if (isListeningForCommand) {
                    isListeningForCommand = false
                    updateNotification("Transcribing...")
                    agentController.onTranscriptionUpdated("Transcribing...")
                    scope.launch {
                        val result = WhisperClient(application as OmniApplication)
                            .transcribe(audio, cachedSpeechLanguage)
                        if (result != null && result.text.isNotBlank()) {
                            Log.d(TAG, "Whisper: \"${result.text}\" (lang=${result.language})")
                            val text = result.text.lowercase()
                            handleCommandResult(text, listOf(text))
                        } else {
                            Log.d(TAG, "Whisper returned empty, no command")
                            handleNoCommand()
                        }
                    }
                }
            },
        )
        commandRecorder?.start(this)
    }

    private fun shutdown() {
        cancelReturnToWake()
        isListeningForWakeWord = false
        isListeningForCommand = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        deepgramClient?.stop()
        deepgramClient = null
        commandRecorder?.stop()
        commandRecorder = null
        activeDeepgramMode = null
        hideOverlay()
        stopSelf()
    }

    // ─── Deepgram ────────────────────────────────────────────────────────────

    private fun preProvisionCommandSession() {
        preProvisionJob?.cancel()
        preProvisionedSession = null
        preProvisionJob = scope.launch {
            val session = runCatching {
                val keyterms = collectKeyterms()
                DeepgramSessionClient(application as OmniApplication)
                    .create(cachedSpeechLanguage, DEEPGRAM_COMMAND_MODEL, keyterms)
            }.getOrNull()
            if (session != null) {
                preProvisionedSession = session
                Log.d(TAG, "Command session pre-provisioned")
            }
        }
    }

    private fun startOrSwitchDeepgram(mode: ListenMode) {
        val existing = deepgramClient
        if (mode == activeDeepgramMode && existing != null && existing.isConnected) {
            Log.d(TAG, "Keeping active Deepgram connection for $mode")
            existing.switchMode(mode)
            return
        }

        Log.d(TAG, "Creating fresh backend-provisioned Deepgram connection for $mode")
        deepgramClient?.stop()
        deepgramClient = null
        activeDeepgramMode = null
        val requestId = ++deepgramRequestId

        scope.launch {
            val session = if (mode == ListenMode.COMMAND && preProvisionedSession != null) {
                Log.d(TAG, "Using pre-provisioned command session")
                preProvisionedSession!!.also { preProvisionedSession = null }
            } else {
                runCatching {
                    val model = if (mode == ListenMode.WAKE_WORD) DEEPGRAM_WAKE_MODEL else DEEPGRAM_COMMAND_MODEL
                    val keyterms = if (mode == ListenMode.COMMAND) collectKeyterms() else emptyList()
                    DeepgramSessionClient(application as OmniApplication)
                        .create(cachedSpeechLanguage, model, keyterms)
                }.getOrElse { error ->
                    Log.e(TAG, "Deepgram session error: ${error.message}")
                    if (mode == ListenMode.COMMAND && isListeningForCommand) {
                        Log.d(TAG, "Falling back to built-in recognizer for command")
                        updateNotification("Listening... speak your command")
                        startBuiltinRecognizer(wakeWordMode = false)
                    } else {
                        updateNotification("Speech unavailable: ${error.message}")
                    }
                    return@launch
                }
            }
            if (requestId != deepgramRequestId) return@launch
            activeDeepgramMode = mode

            deepgramClient = DeepgramClient(
                websocketUrl = prepareDeepgramUrl(session.url, mode),
                accessToken = session.accessToken,
                language = cachedSpeechLanguage,
                wakeWord = cachedWakeWord,
                onWakeWordDetected = {
                    scope.launch {
                        if (mode == ListenMode.WAKE_WORD && isListeningForWakeWord) {
                            onWakeWordDetected()
                        }
                    }
                },
                onStopWordDetected = {
                    scope.launch {
                        Log.d(TAG, "Stop word detected — resetting agent")
                        cancelReturnToWake()
                        activeCommandId += 1
                        isListeningForCommand = false
                        agentController.reset()
                        startWakeWordListening()
                    }
                },
                onPartialResult = { partial ->
                    scope.launch {
                        if (mode == ListenMode.COMMAND && isListeningForCommand) {
                            agentController.onTranscriptionUpdated(partial)
                            updateNotification("Hearing: \"$partial\"")
                        }
                    }
                },
                onFinalResult = { final ->
                    Log.d(TAG, "Deepgram final: $final")
                    scope.launch {
                        if (mode == ListenMode.COMMAND && isListeningForCommand) {
                            agentController.onTranscriptionUpdated(final)
                        }
                    }
                },
                onCommandComplete = { deepgramText ->
                    scope.launch {
                        if (mode == ListenMode.COMMAND && isListeningForCommand) {
                            // Grab buffered audio and send to Whisper for better accuracy
                            val audio = deepgramClient?.getCommandAudio()
                            if (audio != null && audio.size > 6400) { // >0.2s of audio
                                Log.d(TAG, "Sending ${audio.size / 1024}KB to Whisper (Deepgram: \"$deepgramText\")")
                                agentController.onTranscriptionUpdated("$deepgramText (refining...)")
                                val whisperResult = WhisperClient(application as OmniApplication)
                                    .transcribe(audio, cachedSpeechLanguage)
                                if (whisperResult != null && whisperResult.text.isNotBlank()) {
                                    Log.d(TAG, "Whisper result: \"${whisperResult.text}\" (Deepgram was: \"$deepgramText\")")
                                    val text = whisperResult.text.lowercase()
                                    handleCommandResult(text, listOf(text, deepgramText.lowercase()))
                                } else {
                                    Log.d(TAG, "Whisper failed, falling back to Deepgram")
                                    handleCommandResult(deepgramText.lowercase(), listOf(deepgramText.lowercase()))
                                }
                            } else {
                                handleCommandResult(deepgramText.lowercase(), listOf(deepgramText.lowercase()))
                            }
                        }
                    }
                },
                onNoCommand = {
                    scope.launch {
                        if (mode == ListenMode.COMMAND && isListeningForCommand) handleNoCommand()
                    }
                },
                onAudioLevel = { level ->
                    scope.launch {
                        if (mode == ListenMode.COMMAND && isListeningForCommand) {
                            agentController.onSpeechLevelChanged(level)
                        }
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Deepgram error: $error")
                    scope.launch {
                        deepgramClient?.stop()
                        deepgramClient = null
                        activeDeepgramMode = null
                        if (isListeningForCommand) {
                            Log.d(TAG, "Falling back to built-in recognizer after Deepgram error")
                            startBuiltinRecognizer(wakeWordMode = false)
                            return@launch
                        }
                        delay(2000)
                        if (isListeningForWakeWord || isListeningForCommand) {
                            val retryMode = if (isListeningForWakeWord) ListenMode.WAKE_WORD else ListenMode.COMMAND
                            startOrSwitchDeepgram(retryMode)
                        }
                    }
                }
            )
            deepgramClient?.start(this@OmniListenerService, mode)
        }
    }

    // ─── Built-in Speech Recognition (fallback) ─────────────────────────────

    private fun startBuiltinRecognizer(wakeWordMode: Boolean) {
        speechRecognizer?.destroy()
        speechRecognizer = createRecognizer(wakeWordMode)
        speechRecognizer?.setRecognitionListener(SpeechListener(wakeWordMode))
        speechRecognizer?.startListening(buildRecognizerIntent(wakeWordMode))
    }

    private fun createRecognizer(wakeWordMode: Boolean): SpeechRecognizer {
        if (!wakeWordMode && cachedSpeechLanguage.isBlank() &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            Log.d(TAG, "Using on-device recognizer")
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }
        Log.d(TAG, "Using default recognizer (lang=$cachedSpeechLanguage)")
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    private val appNames: ArrayList<String> by lazy {
        val pm = packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        ArrayList(apps.map { it.activityInfo.loadLabel(pm).toString() })
    }

    private val speechCorrector: SpeechCorrector by lazy { SpeechCorrector(appNames) }

    private fun buildRecognizerIntent(wakeWordMode: Boolean): Intent {
        val silenceMs = if (wakeWordMode) 1500L else 10000L
        val partialSilenceMs = if (wakeWordMode) 1000L else 7000L
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, partialSilenceMs)
            val biasingStrings = if (wakeWordMode) {
                listOf(cachedWakeWord, "hey omni", "omni", "hey homie")
            } else {
                appNames
            }
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(biasingStrings))
            val recognizerLanguage = cachedSpeechLanguage.ifBlank { "en-US" }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLanguage)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, recognizerLanguage)
        }
    }

    private inner class SpeechListener(private val wakeWordMode: Boolean) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Recognizer ready (wakeWord=$wakeWordMode)")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Recognizer speech started (wakeWord=$wakeWordMode)")
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!wakeWordMode && isListeningForCommand) {
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                agentController.onSpeechLevelChanged(level)
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.d(TAG, "Recognizer speech ended (wakeWord=$wakeWordMode)")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error=$error (wakeWord=$wakeWordMode)")
            scope.launch {
                delay(300)
                when {
                    isListeningForWakeWord -> startBuiltinRecognizer(wakeWordMode = true)
                    isListeningForCommand -> startBuiltinRecognizer(wakeWordMode = false)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            val text = candidates.firstOrNull()?.lowercase() ?: return
            Log.d(TAG, "Recognizer results (wakeWord=$wakeWordMode): ${candidates.joinToString(" | ")}")

            if (wakeWordMode) {
                handleWakeWordResult(text)
            } else {
                handleCommandResult(text, candidates.map { it.lowercase() })
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.lowercase() ?: return
            Log.d(TAG, "Recognizer partial (wakeWord=$wakeWordMode): $partial")
            if (wakeWordMode) {
                if (isWakeWordMatch(partial)) speechRecognizer?.stopListening()
            } else {
                agentController.onTranscriptionUpdated(partial)
                updateNotification("Hearing: \"$partial\"")
            }
        }
    }

    // ─── Result Handlers ─────────────────────────────────────────────────────

    private fun handleWakeWordResult(text: String) {
        scope.launch {
            if (isWakeWordMatch(text)) {
                Log.d(TAG, "Wake word detected from recognizer: $text")
                onWakeWordDetected()
            } else {
                Log.d(TAG, "Wake word not matched: $text")
                delay(100)
                startBuiltinRecognizer(wakeWordMode = true)
            }
        }
    }

    private fun isWakeWordMatch(text: String): Boolean {
        val normalizedText = text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        val normalizedWake = cachedWakeWord.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        return normalizedText.contains(normalizedWake) ||
            normalizedText.contains("hey omni") ||
            normalizedText.contains("hey homie")
    }

    private fun handleCommandResult(text: String, candidates: List<String>) {
        val stopWords = listOf("stop omni", "stop, omni", "para omni", "pare omni")
        if (stopWords.any { text.contains(it) }) {
            cancelReturnToWake()
            activeCommandId += 1
            isListeningForCommand = false
            agentController.reset()
            startWakeWordListening()
            return
        }

        val corrected = speechCorrector.correct(text)
        val correctedCandidates = candidates.map { speechCorrector.correct(it) }
        Log.d(TAG, "Command: $corrected (raw: $text)")
        isListeningForCommand = false
        onCommandReceived(corrected, correctedCandidates)
    }

    private fun handleNoCommand() {
        Log.d(TAG, "No command captured, returning to wake word listening")
        activeCommandId += 1
        isListeningForCommand = false
        agentController.reset()
        updateNotification("No command heard")
        startWakeWordListening()
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        if (!isListeningForWakeWord) return
        isListeningForWakeWord = false
        isListeningForCommand = true
        startedFromWakeWord = true
        updateNotification("Wake word detected!")
        agentController.onWakeWordDetected()

        if (!isAppInForeground()) {
            val overlayIntent = Intent(this, OmniOverlayService::class.java).apply {
                action = OmniOverlayService.ACTION_SHOW
            }
            startForegroundService(overlayIntent)
        }

        startCommandListening()
    }

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(android.app.ActivityManager::class.java)
        return am.appTasks.any { it.taskInfo?.topActivity?.packageName == packageName }
    }

    private fun onCommandReceived(command: String, candidates: List<String> = listOf(command)) {
        updateNotification("Processing: \"$command\"")
        val commandId = ++activeCommandId
        cancelReturnToWake()
        agentController.onCommandReceived(command, candidates)

        // Start wake word listening immediately so "Stop Omni" works during execution
        startWakeWordListening()

        returnToWakeJob = scope.launch {
            var sawAgentRun = false
            withTimeoutOrNull(AGENT_RETURN_TO_WAKE_TIMEOUT_MS) {
                agentController.status.first { status ->
                    if (status is AgentStatus.Processing || status is AgentStatus.Executing) {
                        sawAgentRun = true
                    }
                    status is AgentStatus.Done ||
                        status is AgentStatus.Error ||
                        (sawAgentRun && status is AgentStatus.Idle)
                }
            }
            if (commandId == activeCommandId && !isListeningForCommand) {
                // Wait for TTS to finish so the mic doesn't pick up spoken output
                withTimeoutOrNull(TTS_FINISH_TIMEOUT_MS) {
                    agentController.ttsActive.first { !it }
                }
                delay(RETURN_TO_WAKE_COOLDOWN_MS)
                startWakeWordListening()
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun hideOverlay() {
        try {
            startService(Intent(this, OmniOverlayService::class.java).apply {
                action = OmniOverlayService.ACTION_HIDE
            })
        } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, OmniApplication.CHANNEL_LISTENING)
            .setContentTitle("Omni Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun cancelReturnToWake() {
        returnToWakeJob?.cancel()
        returnToWakeJob = null
    }

    private fun collectKeyterms(): List<String> {
        val terms = mutableListOf<String>()
        // App names — what the user is most likely to invoke by voice
        terms.addAll(appNames)
        // Visible screen text from accessibility — current context (page titles, buttons,
        // search results, contact names, app-specific terms like artist names in Spotify)
        OmniAccessibilityService.instance?.getScreenElements()?.forEach { el ->
            el.text?.takeIf { it.isNotBlank() }?.let { terms.add(it) }
            el.contentDescription?.takeIf { it.isNotBlank() }?.let { terms.add(it) }
        }
        return terms
            .map { it.trim() }
            .filter { it.length in 2..40 }
            .map { it.replace(Regex("\\s+"), " ") }
            .distinct()
            .take(100)
    }

    private fun prepareDeepgramUrl(url: String, mode: ListenMode): String {
        if (mode != ListenMode.WAKE_WORD) return url
        val base = if (url.contains("/v1/listen")) {
            "${url.substringBefore("/v1/listen")}/v2/listen"
        } else {
            url.substringBefore("?")
        }
        val params = listOf(
            "model" to DEEPGRAM_WAKE_MODEL,
            "encoding" to "linear16",
            "sample_rate" to "16000",
            "eot_timeout_ms" to "1000",
            "keyterm" to "Omni",
            "keyterm" to "Hey Omni",
            "keyterm" to "Stop Omni",
        ).joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$base?$params"
    }

    companion object {
        private const val TAG = "OmniListener"
        private const val AGENT_RETURN_TO_WAKE_TIMEOUT_MS = 10 * 60 * 1000L
        private const val RETURN_TO_WAKE_COOLDOWN_MS = 1500L
        private const val TTS_FINISH_TIMEOUT_MS = 15_000L
        private const val DEEPGRAM_WAKE_MODEL = "flux-general-en"
        private const val DEEPGRAM_COMMAND_MODEL = "nova-3"
        const val NOTIF_ID = 1001
        const val ACTION_START_COMMAND_LISTENING = "com.omni.START_COMMAND"
        const val ACTION_START_WAKE_WORD = "com.omni.START_WAKE_WORD"
        const val ACTION_STOP = "com.omni.STOP"
        const val ACTION_STOP_COMMAND = "com.omni.STOP_COMMAND"
        const val ACTION_WAKE_WORD_DETECTED = "com.omni.WAKE_WORD"
    }
}
