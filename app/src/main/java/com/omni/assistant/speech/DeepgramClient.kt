package com.omni.assistant.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString.Companion.toByteString

enum class ListenMode { WAKE_WORD, COMMAND }

class DeepgramClient(
    private val apiKey: String,
    private val language: String = "",
    private val wakeWord: String = "hey omni",
    private val onWakeWordDetected: () -> Unit = {},
    private val onPartialResult: (String) -> Unit = {},
    private val onFinalResult: (String) -> Unit = {},
    private val onCommandComplete: (String) -> Unit = {},
    private val onError: (String) -> Unit
) {

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var wsOpen = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private var mode: ListenMode = ListenMode.WAKE_WORD
    private val commandTranscript = StringBuilder()
    private val normalizedWakeWord = wakeWord.lowercase().replace(Regex("[^a-z ]"), "").trim()
    private var commandModeStartTime = 0L
    private var ignoreUntil = 0L

    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    val isConnected: Boolean get() = wsOpen && isRecording

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(context: Context, initialMode: ListenMode = ListenMode.WAKE_WORD) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission not granted")
            return
        }

        mode = initialMode
        commandTranscript.clear()

        // Always use multi-language so wake word "Hey Omni" works regardless of language setting
        val langParam = "multi"

        val url = "wss://api.deepgram.com/v1/listen" +
            "?encoding=linear16&sample_rate=$SAMPLE_RATE&channels=1" +
            "&model=nova-3" +
            "&language=$langParam" +
            "&smart_format=true" +
            "&punctuate=true" +
            "&interim_results=true" +
            "&endpointing=800" +
            "&utterance_end_ms=3000" +
            "&vad_events=true"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected (mode=$mode)")
                wsOpen = true
                startAudioCapture(context)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WS message: ${text.take(200)}")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                wsOpen = false
                isRecording = false
                onError("Connection failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                wsOpen = false
                isRecording = false
            }
        })
    }

    fun switchMode(newMode: ListenMode) {
        Log.d(TAG, "Mode: $mode -> $newMode")
        commandTranscript.clear()
        mode = newMode
        if (newMode == ListenMode.COMMAND) {
            commandModeStartTime = System.currentTimeMillis()
            ignoreUntil = System.currentTimeMillis() + 150 // brief ignore to drop wake-word carryover
        }
    }

    fun stop() {
        wsOpen = false
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        try { webSocket?.send("{\"type\": \"CloseStream\"}") } catch (_: Exception) {}
        webSocket?.close(1000, "Done")
        webSocket = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    // ─── Audio Capture ───────────────────────────────────────────────────────

    private fun startAudioCapture(context: Context) {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        // MIC first — on Samsung A54, MIC with DSP gain gives ~3x louder signal
        // than UNPROCESSED (which bypasses AGC). Avoid VOICE_RECOGNITION — returns
        // silence on some Samsungs.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED
        )
        var selected: AudioRecord? = null
        for (src in sources) {
            val rec = try {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
            } catch (_: Exception) { continue }
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Using audio source=$src")
                selected = rec
                break
            }
            rec.release()
        }
        audioRecord = selected

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            onError("Failed to initialize audio recorder")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            var bytesSent = 0L
            var lastLogMs = 0L
            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    // Apply software gain — Samsung A54 mic is heavily attenuated
                    // even with MIC source. Boost 4x with hard-clipping protection.
                    var i = 0
                    var peak = 0
                    var clippedSamples = 0
                    while (i < read - 1) {
                        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                        val boosted = sample * GAIN
                        // Soft-clip above SOFT_KNEE using tanh-like curve — preserves
                        // waveform shape under overload so consonants stay intelligible.
                        val limited = when {
                            boosted > SOFT_KNEE -> {
                                clippedSamples++
                                val over = (boosted - SOFT_KNEE).toDouble() / (32767 - SOFT_KNEE)
                                (SOFT_KNEE + (32767 - SOFT_KNEE) * kotlin.math.tanh(over)).toInt()
                            }
                            boosted < -SOFT_KNEE -> {
                                clippedSamples++
                                val over = (-boosted - SOFT_KNEE).toDouble() / (32768 - SOFT_KNEE)
                                -(SOFT_KNEE + (32768 - SOFT_KNEE) * kotlin.math.tanh(over)).toInt()
                            }
                            else -> boosted
                        }
                        buffer[i] = (limited and 0xFF).toByte()
                        buffer[i + 1] = ((limited shr 8) and 0xFF).toByte()
                        val a = if (limited < 0) -limited else limited
                        if (a > peak) peak = a
                        i += 2
                    }
                    val sent = webSocket?.send(buffer.copyOf(read).toByteString()) ?: false
                    if (sent) bytesSent += read
                    val now = System.currentTimeMillis()
                    if (now - lastLogMs > 3000) {
                        lastLogMs = now
                        Log.d(TAG, "Audio: ${bytesSent / 1024}KB sent, ws=${wsOpen}, peak=$peak/32767 (x${GAIN}, clipped=${clippedSamples})")
                    }
                }
            }
            Log.d(TAG, "Audio capture stopped, total=${bytesSent / 1024}KB")
        }
    }

    // ─── Message Handling ────────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            when (json.get("type")?.asString) {
                "Results" -> {
                    val transcript = extractTranscript(json) ?: return
                    val isFinal = json.get("is_final")?.asBoolean ?: false
                    when (mode) {
                        ListenMode.WAKE_WORD -> handleWakeWordResult(transcript)
                        ListenMode.COMMAND -> handleCommandResult(transcript, isFinal)
                    }
                }
                "UtteranceEnd" -> {
                    if (mode == ListenMode.COMMAND) finalizeCommand()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${text.take(200)}", e)
        }
    }

    private fun extractTranscript(json: JsonObject): String? {
        val channel = json.getAsJsonObject("channel") ?: return null
        val alternatives = channel.getAsJsonArray("alternatives")
        if (alternatives == null || alternatives.size() == 0) return null
        val transcript = alternatives[0].asJsonObject.get("transcript")?.asString
        return if (transcript.isNullOrBlank()) null else transcript
    }

    private fun handleWakeWordResult(transcript: String) {
        onPartialResult(transcript)
        val normalized = transcript.lowercase().replace(Regex("[^a-z ]"), "").trim()
        if (normalized.contains(normalizedWakeWord)) {
            Log.d(TAG, "Wake word detected: \"$transcript\"")
            onWakeWordDetected()
        }
    }

    private fun handleCommandResult(transcript: String, isFinal: Boolean) {
        if (System.currentTimeMillis() < ignoreUntil) return

        // Strip wake word if it leaked into the command
        val cleaned = transcript
            .replace(Regex("(?i)${Regex.escape(wakeWord)}"), "")
            .trim()
        if (cleaned.isBlank()) return

        if (isFinal) {
            Log.d(TAG, "Command final: $cleaned")
            commandTranscript.append(cleaned).append(" ")
            onFinalResult(cleaned)
        } else {
            onPartialResult(cleaned)
        }
    }

    private fun finalizeCommand() {
        val elapsed = System.currentTimeMillis() - commandModeStartTime
        if (elapsed < MIN_COMMAND_LISTEN_MS) {
            Log.d(TAG, "Ignoring early UtteranceEnd (${elapsed}ms < ${MIN_COMMAND_LISTEN_MS}ms)")
            return
        }
        val full = commandTranscript.toString().trim()
        commandTranscript.clear()
        if (full.isNotBlank()) {
            Log.d(TAG, "Command complete: $full")
            onCommandComplete(full)
        }
    }

    companion object {
        private const val TAG = "DeepgramClient"
        private const val SAMPLE_RATE = 16000
        private const val MIN_COMMAND_LISTEN_MS = 2000L
        private const val GAIN = 2
        private const val SOFT_KNEE = 24000  // start soft-clipping at ~73% of full scale
    }
}
