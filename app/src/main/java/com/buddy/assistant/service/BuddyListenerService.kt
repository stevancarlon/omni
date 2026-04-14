package com.buddy.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import com.buddy.assistant.BuddyApplication
import com.buddy.assistant.agent.AgentController
import com.buddy.assistant.data.AgentStatus
import com.buddy.assistant.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class BuddyListenerService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningForWakeWord = false
    private var isListeningForCommand = false
    private lateinit var agentController: AgentController

    override fun onCreate() {
        super.onCreate()
        agentController = AgentController.getInstance(application as BuddyApplication)
        startForeground(NOTIF_ID, buildNotification("Starting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            else -> startWakeWordListening()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        scope.cancel()
    }

    // ─── Listening Modes ─────────────────────────────────────────────────────

    private fun startWakeWordListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        isListeningForWakeWord = true
        isListeningForCommand = false
        updateNotification("Listening for \"Hey Buddy\"...")
        startRecognizer(wakeWordMode = true)
    }

    private fun startCommandListening() {
        isListeningForWakeWord = false
        isListeningForCommand = true
        updateNotification("Listening... speak your command")
        startRecognizer(wakeWordMode = false)
    }

    private fun shutdown() {
        isListeningForWakeWord = false
        isListeningForCommand = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopSelf()
    }

    // ─── Speech Recognition ──────────────────────────────────────────────────

    private fun startRecognizer(wakeWordMode: Boolean) {
        speechRecognizer?.destroy()
        speechRecognizer = createRecognizer()
        speechRecognizer?.setRecognitionListener(SpeechListener(wakeWordMode))
        speechRecognizer?.startListening(buildRecognizerIntent(wakeWordMode))
    }

    private fun createRecognizer(): SpeechRecognizer {
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            Log.d(TAG, "Using on-device recognizer")
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }
        Log.d(TAG, "Using default recognizer")
        return SpeechRecognizer.createSpeechRecognizer(this)
    }

    private fun buildRecognizerIntent(wakeWordMode: Boolean): Intent {
        val silenceMs = if (wakeWordMode) 3000L else 10000L
        val partialSilenceMs = if (wakeWordMode) 2000L else 7000L
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, partialSilenceMs)
        }
    }

    // ─── Recognition Listener ────────────────────────────────────────────────

    private inner class SpeechListener(private val wakeWordMode: Boolean) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech (wakeWord=$wakeWordMode)")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error=$error (wakeWord=$wakeWordMode)")
            scope.launch {
                delay(300)
                when {
                    isListeningForWakeWord -> startRecognizer(wakeWordMode = true)
                    isListeningForCommand -> startRecognizer(wakeWordMode = false)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.lowercase()
                ?: return

            Log.d(TAG, "Result (wakeWord=$wakeWordMode): $text")

            if (wakeWordMode) {
                handleWakeWordResult(text)
            } else {
                handleCommandResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!wakeWordMode) return
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.lowercase()
                ?: return
            scope.launch {
                val wakeWord = getWakeWord()
                if (partial.contains(wakeWord)) speechRecognizer?.stopListening()
            }
        }
    }

    // ─── Result Handlers ─────────────────────────────────────────────────────

    private fun handleWakeWordResult(text: String) {
        scope.launch {
            val wakeWord = getWakeWord()
            if (text.contains(wakeWord)) {
                Log.d(TAG, "Wake word matched")
                onWakeWordDetected()
            } else {
                delay(100)
                startRecognizer(wakeWordMode = true)
            }
        }
    }

    private fun handleCommandResult(text: String) {
        val isStopCommand = text.contains("stop buddy") || text.contains("stop, buddy")
        if (isStopCommand) {
            Log.d(TAG, "Stop command detected")
            agentController.reset()
            startWakeWordListening()
            return
        }

        Log.d(TAG, "Command: $text")
        isListeningForCommand = false
        onCommandReceived(text)
    }

    // ─── Callbacks ───────────────────────────────────────────────────────────

    private fun onWakeWordDetected() {
        startedFromWakeWord = true
        updateNotification("Wake word detected!")
        agentController.onWakeWordDetected()
        val overlayIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            action = ACTION_WAKE_WORD_DETECTED
        }
        startActivity(overlayIntent)
        scope.launch {
            delay(300)
            startCommandListening()
        }
    }

    private var startedFromWakeWord = false

    private fun onCommandReceived(command: String) {
        updateNotification("Processing: \"$command\"")
        agentController.onCommandReceived(command)
        scope.launch {
            agentController.status.first { it is AgentStatus.Idle || it is AgentStatus.Done || it is AgentStatus.Error }
            if (startedFromWakeWord) {
                startWakeWordListening()
            } else {
                updateNotification("Task complete")
                shutdown()
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun getWakeWord(): String =
        (application as BuddyApplication).settingsRepository.wakeWord.first().lowercase()

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, BuddyApplication.CHANNEL_LISTENING)
            .setContentTitle("Buddy Assistant")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "BuddyListener"
        const val NOTIF_ID = 1001
        const val ACTION_START_COMMAND_LISTENING = "com.buddy.START_COMMAND"
        const val ACTION_START_WAKE_WORD = "com.buddy.START_WAKE_WORD"
        const val ACTION_STOP = "com.buddy.STOP"
        const val ACTION_WAKE_WORD_DETECTED = "com.buddy.WAKE_WORD"
    }
}
