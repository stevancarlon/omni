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
import com.omni.assistant.speech.DeepgramClient
import com.omni.assistant.speech.DeepgramSessionClient
import com.omni.assistant.speech.ListenMode
import com.omni.assistant.ui.MainActivity
import com.omni.assistant.util.SpeechCorrector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class OmniListenerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var deepgramClient: DeepgramClient? = null
    private var isListeningForWakeWord = false
    private var isListeningForCommand = false
    private var startedFromWakeWord = false
    private var useDeepgramForAll = false
    private var deepgramRequestId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var agentController: AgentController

    // Cached settings
    private var cachedSpeechLanguage = ""
    private var cachedWakeWord = "hey omni"

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
                    startedFromWakeWord = false
                    agentController.onWakeWordDetected()
                    startCommandListening()
                }
                ACTION_START_WAKE_WORD -> {
                    startedFromWakeWord = true
                    startWakeWordListening()
                }
                ACTION_STOP -> shutdown()
                ACTION_STOP_COMMAND -> {
                    isListeningForCommand = false
                    agentController.reset()
                    deepgramClient?.stop()
                    deepgramClient = null
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
        speechRecognizer?.destroy()
        deepgramClient?.stop()
        scope.cancel()
    }

    // ─── Settings ────────────────────────────────────────────────────────────

    private suspend fun reloadSettings() {
        val repo = (application as OmniApplication).settingsRepository
        cachedSpeechLanguage = repo.speechLanguage.first()
        cachedWakeWord = repo.wakeWord.first().lowercase()
        useDeepgramForAll = repo.authToken.first().isNotBlank()
        Log.d(TAG, "Settings: backendSpeech=${useDeepgramForAll}, lang=$cachedSpeechLanguage, wake=$cachedWakeWord")
    }

    // ─── Listening Modes ─────────────────────────────────────────────────────

    private fun startWakeWordListening() {
        if (!useDeepgramForAll) {
            Log.d(TAG, "Skipping wake word until signed in. Use Start Listening button instead.")
            updateNotification("Sign in to enable wake word")
            isListeningForWakeWord = false
            return
        }
        isListeningForWakeWord = true
        isListeningForCommand = false
        updateNotification("Listening for \"$cachedWakeWord\"...")
        deepgramClient?.stop()
        deepgramClient = null
        startBuiltinRecognizer(wakeWordMode = true)
    }

    private fun startCommandListening() {
        isListeningForWakeWord = false
        isListeningForCommand = true
        updateNotification("Listening... speak your command")

        if (useDeepgramForAll) {
            speechRecognizer?.destroy()
            speechRecognizer = null
            startOrSwitchDeepgram(ListenMode.COMMAND)
        } else {
            startBuiltinRecognizer(wakeWordMode = false)
        }
    }

    private fun shutdown() {
        isListeningForWakeWord = false
        isListeningForCommand = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        deepgramClient?.stop()
        deepgramClient = null
        hideOverlay()
        stopSelf()
    }

    // ─── Deepgram ────────────────────────────────────────────────────────────

    private fun startOrSwitchDeepgram(mode: ListenMode) {
        val existing = deepgramClient
        if (mode == ListenMode.WAKE_WORD && existing != null && existing.isConnected) {
            Log.d(TAG, "Reusing Deepgram connection, switching to $mode")
            existing.switchMode(mode)
            return
        }

        Log.d(TAG, "Creating fresh backend-provisioned Deepgram connection for $mode")
        deepgramClient?.stop()
        deepgramClient = null
        val requestId = ++deepgramRequestId

        scope.launch {
            val session = runCatching {
                DeepgramSessionClient(application as OmniApplication).create(cachedSpeechLanguage)
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
            if (requestId != deepgramRequestId) return@launch

            deepgramClient = DeepgramClient(
                websocketUrl = session.url,
                accessToken = session.accessToken,
                language = cachedSpeechLanguage,
                wakeWord = cachedWakeWord,
                onWakeWordDetected = {
                    scope.launch { onWakeWordDetected() }
                },
                onPartialResult = { partial ->
                    scope.launch {
                        if (isListeningForCommand) {
                            agentController.onTranscriptionUpdated(partial)
                            updateNotification("Hearing: \"$partial\"")
                        }
                    }
                },
                onFinalResult = { final ->
                    Log.d(TAG, "Deepgram final: $final")
                    scope.launch {
                        if (isListeningForCommand) agentController.onTranscriptionUpdated(final)
                    }
                },
                onCommandComplete = { fullText ->
                    scope.launch {
                        if (isListeningForCommand) {
                            handleCommandResult(fullText.lowercase(), listOf(fullText.lowercase()))
                        }
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Deepgram error: $error")
                    scope.launch {
                        deepgramClient?.stop()
                        deepgramClient = null
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
        if (!wakeWordMode && cachedSpeechLanguage.isBlank() && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
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

        override fun onRmsChanged(rmsdB: Float) {}
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

    // ─── Callbacks ───────────────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        startedFromWakeWord = true
        updateNotification("Wake word detected!")
        agentController.onWakeWordDetected()

        if (!isAppInForeground()) {
            val overlayIntent = Intent(this, OmniOverlayService::class.java).apply {
                action = OmniOverlayService.ACTION_SHOW
            }
            startForegroundService(overlayIntent)
        }

        scope.launch {
            delay(300)
            startCommandListening()
        }
    }

    private fun isAppInForeground(): Boolean {
        val am = getSystemService(android.app.ActivityManager::class.java)
        return am.appTasks.any { it.taskInfo?.topActivity?.packageName == packageName }
    }

    private fun onCommandReceived(command: String, candidates: List<String> = listOf(command)) {
        updateNotification("Processing: \"$command\"")
        agentController.onCommandReceived(command, candidates)
        scope.launch {
            agentController.status.first { it is AgentStatus.Idle || it is AgentStatus.Done || it is AgentStatus.Error }
            // Always return to wake word listening after task completes
            startWakeWordListening()
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

    companion object {
        private const val TAG = "OmniListener"
        const val NOTIF_ID = 1001
        const val ACTION_START_COMMAND_LISTENING = "com.omni.START_COMMAND"
        const val ACTION_START_WAKE_WORD = "com.omni.START_WAKE_WORD"
        const val ACTION_STOP = "com.omni.STOP"
        const val ACTION_STOP_COMMAND = "com.omni.STOP_COMMAND"
        const val ACTION_WAKE_WORD_DETECTED = "com.omni.WAKE_WORD"
    }
}
