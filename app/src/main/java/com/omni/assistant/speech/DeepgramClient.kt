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
    private val websocketUrl: String,
    private val accessToken: String,
    private val language: String = "",
    private val wakeWord: String = "hey omni",
    private val onWakeWordDetected: () -> Unit = {},
    private val onStopWordDetected: () -> Unit = {},
    private val onPartialResult: (String) -> Unit = {},
    private val onFinalResult: (String) -> Unit = {},
    private val onCommandComplete: (String) -> Unit = {},
    private val onNoCommand: () -> Unit = {},
    private val onAudioLevel: (Float) -> Unit = {},
    private val onError: (String) -> Unit
) {

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    @Volatile private var wsOpen = false
    @Volatile private var intentionallyStopping = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val gson = Gson()

    private var mode: ListenMode = ListenMode.WAKE_WORD
    private val commandTranscript = StringBuilder()
    // Full command audio buffer — raw PCM sent to Whisper for accurate transcription
    private val commandAudioBuffer = java.io.ByteArrayOutputStream()
    // Pending audio buffer — captures mic bytes during the WS handshake so the
    // user's first word isn't lost while the socket is still opening.
    private val pendingAudio = ArrayDeque<okio.ByteString>()
    private var pendingBytes = 0
    private val normalizedWakeWords = listOf(
        normalizePhrase(wakeWord),
        normalizePhrase("hey omni"),
        normalizePhrase("hey homie"),
        normalizePhrase("hey umini"),
        normalizePhrase("hey umni"),
        normalizePhrase("hey mini"),
        normalizePhrase("hey omie"),
    ).filter { it.isNotBlank() }.distinct()
    private var commandModeStartTime = 0L
    private var ignoreUntil = 0L
    private var commandFinalizeJob: Job? = null
    private var emptyCommandFinals = 0
    private var emptyWakeFinals = 0
    private var wakeDetected = false
    private var noCommandReported = false

    private val client = OkHttpClient.Builder()
        .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    val isConnected: Boolean get() = wsOpen && isRecording

    /** Returns the buffered command audio (raw PCM16, 16kHz mono) and clears the buffer. */
    fun getCommandAudio(): ByteArray {
        synchronized(commandAudioBuffer) {
            val data = commandAudioBuffer.toByteArray()
            commandAudioBuffer.reset()
            return data
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    fun start(context: Context, initialMode: ListenMode = ListenMode.WAKE_WORD) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onError("Microphone permission not granted")
            return
        }

        mode = initialMode
        intentionallyStopping = false
        commandTranscript.clear()
        synchronized(commandAudioBuffer) { commandAudioBuffer.reset() }
        noCommandReported = false
        wakeDetected = initialMode != ListenMode.WAKE_WORD
        if (initialMode == ListenMode.COMMAND) {
            commandModeStartTime = System.currentTimeMillis()
            ignoreUntil = System.currentTimeMillis() + 150
        }

        // Start mic capture IMMEDIATELY so the first word survives the WS handshake.
        // Audio bytes are buffered in pendingAudio until wsOpen flips true.
        synchronized(pendingAudio) {
            pendingAudio.clear()
            pendingBytes = 0
        }
        startAudioCapture(context)

        val request = Request.Builder()
            .url(websocketUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected (mode=$mode)")
                synchronized(pendingAudio) {
                    var drained = 0
                    while (pendingAudio.isNotEmpty()) {
                        val chunk = pendingAudio.removeFirst()
                        webSocket.send(chunk)
                        drained += chunk.size
                    }
                    pendingBytes = 0
                    wsOpen = true
                    Log.d(TAG, "WS open, drained ${drained / 1024}KB pre-handshake audio")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!text.contains("\"transcript\":\"\"")) {
                    Log.d(TAG, "WS message: ${text.take(200)}")
                }
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
                if (!intentionallyStopping) {
                    onError("Connection closed: $code $reason")
                }
            }
        })
    }

    fun switchMode(newMode: ListenMode) {
        Log.d(TAG, "Mode: $mode -> $newMode")
        commandFinalizeJob?.cancel()
        commandFinalizeJob = null
        commandTranscript.clear()
        emptyCommandFinals = 0
        emptyWakeFinals = 0
        noCommandReported = false
        wakeDetected = newMode != ListenMode.WAKE_WORD
        mode = newMode
        if (newMode == ListenMode.COMMAND) {
            commandModeStartTime = System.currentTimeMillis()
            ignoreUntil = System.currentTimeMillis() + 150 // brief ignore to drop wake-word carryover
        }
    }

    fun stop() {
        intentionallyStopping = true
        wsOpen = false
        isRecording = false
        commandFinalizeJob?.cancel()
        commandFinalizeJob = null
        recordingJob?.cancel()
        recordingJob = null
        try { webSocket?.send("{\"type\": \"CloseStream\"}") } catch (_: Exception) {}
        webSocket?.close(1000, "Done")
        webSocket = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        synchronized(pendingAudio) {
            pendingAudio.clear()
            pendingBytes = 0
        }
    }

    // ─── Audio Capture ───────────────────────────────────────────────────────

    private fun startAudioCapture(context: Context) {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        // MIC first: on Samsung A54, MIC with DSP gain gives a louder signal
        // than UNPROCESSED. Avoid VOICE_RECOGNITION here: it can initialize
        // but degrade or silence wake-word audio on some Samsung devices.
        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
        )
        var selected: AudioRecord? = null
        var selectedSourceIndex = -1
        for (src in sources) {
            val rec = try {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
            } catch (_: Exception) { continue }
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Using audio source=$src")
                selected = rec
                selectedSourceIndex = sources.indexOf(src)
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
            var lastLevelMs = 0L
            var zeroPeakMs = 0L
            // DC offset tracker (running mean) and pre-emphasis state across reads —
            // pre-emphasis y[n] = x[n] - 0.97*x[n-1] boosts high frequencies so
            // consonants survive the gain/soft-clip stage.
            var dcOffset = 0.0
            var prevSample = 0
            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    // Apply software gain — Samsung A54 mic is heavily attenuated
                    // even with MIC source. Boost 4x with hard-clipping protection.
                    var i = 0
                    var peak = 0
                    var clippedSamples = 0
                    val gain = if (mode == ListenMode.WAKE_WORD) WAKE_GAIN else COMMAND_GAIN
                    while (i < read - 1) {
                        val raw = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                        // DC offset removal — slowly tracking mean
                        dcOffset = dcOffset * (1.0 - DC_ALPHA) + raw * DC_ALPHA
                        val centered = raw - dcOffset.toInt()
                        // Pre-emphasis filter
                        val emphasized = centered - (PRE_EMPHASIS * prevSample).toInt()
                        prevSample = centered
                        val sample = emphasized
                        val boosted = sample * gain
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
                    val frameMs = (read / 2) * 1000L / SAMPLE_RATE
                    if (peak <= DEAD_STREAM_PEAK_THRESHOLD) {
                        zeroPeakMs += frameMs
                    } else {
                        zeroPeakMs = 0L
                    }
                    if (zeroPeakMs >= SILENT_SOURCE_FALLBACK_MS && selectedSourceIndex < sources.lastIndex) {
                        val nextIndex = reopenAudioSource(sources, selectedSourceIndex + 1, bufferSize)
                        if (nextIndex != selectedSourceIndex) {
                            selectedSourceIndex = nextIndex
                            zeroPeakMs = 0L
                            dcOffset = 0.0
                            prevSample = 0
                            continue
                        }
                    }
                    val payload = buffer.copyOf(read).toByteString()
                    // Buffer raw PCM for Whisper transcription in command mode
                    if (mode == ListenMode.COMMAND) {
                        synchronized(commandAudioBuffer) {
                            commandAudioBuffer.write(buffer, 0, read)
                        }
                    }
                    var sent = false
                    synchronized(pendingAudio) {
                        if (wsOpen) {
                            sent = webSocket?.send(payload) ?: false
                        } else {
                            // WS still handshaking — buffer locally so first word survives.
                            pendingAudio.addLast(payload)
                            pendingBytes += payload.size
                            // Cap at 3 seconds (~96KB at 16kHz mono PCM16) — drop oldest
                            // if backend/handshake is taking too long.
                            while (pendingBytes > PENDING_MAX_BYTES && pendingAudio.size > 1) {
                                val dropped = pendingAudio.removeFirst()
                                pendingBytes -= dropped.size
                            }
                            sent = true
                        }
                    }
                    if (sent) bytesSent += read
                    val now = System.currentTimeMillis()
                    if (mode == ListenMode.COMMAND && now - lastLevelMs > AUDIO_LEVEL_INTERVAL_MS) {
                        lastLevelMs = now
                        onAudioLevel((peak / 32767f).coerceIn(0f, 1f))
                    }
                    if (now - lastLogMs > 3000) {
                        lastLogMs = now
                        Log.d(TAG, "Audio: ${bytesSent / 1024}KB sent, ws=${wsOpen}, peak=$peak/32767 (x${gain}, clipped=${clippedSamples})")
                    }
                }
            }
            Log.d(TAG, "Audio capture stopped, total=${bytesSent / 1024}KB")
        }
    }

    private fun reopenAudioSource(sources: IntArray, startIndex: Int, bufferSize: Int): Int {
        if (!isRecording) return startIndex - 1

        val previous = audioRecord
        try { previous?.stop() } catch (_: Exception) {}
        previous?.release()
        audioRecord = null

        for (index in startIndex until sources.size) {
            val source = sources[index]
            val rec = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Audio source=$source failed: ${e.message}")
                continue
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord = rec
                rec.startRecording()
                Log.w(TAG, "Wake audio source was silent; switched to source=$source")
                return index
            }
            rec.release()
        }

        Log.e(TAG, "All fallback audio sources failed after silent wake stream")
        isRecording = false
        onError("Microphone stream is silent")
        return sources.lastIndex
    }

    // ─── Message Handling ────────────────────────────────────────────────────

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            when (json.get("type")?.asString) {
                "Error" -> {
                    val message = json.get("message")?.asString
                        ?: json.get("description")?.asString
                        ?: text.take(200)
                    onError("Deepgram error event: $message")
                }
                "TurnInfo" -> {
                    val transcript = json.get("transcript")?.asString?.trim()
                    if (!transcript.isNullOrBlank() && mode == ListenMode.WAKE_WORD) {
                        handleWakeWordResult(transcript)
                    }
                }
                "Results" -> {
                    val isFinal = json.get("is_final")?.asBoolean ?: false
                    val speechFinal = json.get("speech_final")?.asBoolean ?: false
                    val transcript = extractTranscript(json)
                    if (transcript.isNullOrBlank()) {
                        handleEmptyResult(isFinal, speechFinal)
                        return
                    }
                    when (mode) {
                        ListenMode.WAKE_WORD -> handleWakeWordResult(transcript)
                        ListenMode.COMMAND -> handleCommandResult(transcript, isFinal, speechFinal)
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
        return transcript?.trim()
    }

    private fun handleEmptyResult(isFinal: Boolean, speechFinal: Boolean) {
        if (!isFinal && !speechFinal) return

        if (mode == ListenMode.WAKE_WORD) {
            emptyWakeFinals += 1
            Log.d(TAG, "Empty wake final count=$emptyWakeFinals")
            if (emptyWakeFinals >= MAX_EMPTY_WAKE_FINALS) {
                onError("Deepgram wake stream heard audio but returned no transcript")
            }
            return
        }

        val elapsed = System.currentTimeMillis() - commandModeStartTime
        if (elapsed < MIN_COMMAND_LISTEN_MS) return

        emptyCommandFinals += 1
        Log.d(TAG, "Empty command final count=$emptyCommandFinals")
        if (emptyCommandFinals >= MAX_EMPTY_COMMAND_FINALS) {
            reportNoCommandIfReady()
        }
    }

    private fun handleWakeWordResult(transcript: String) {
        emptyWakeFinals = 0
        onPartialResult(transcript)
        if (isStopWordMatch(transcript)) {
            Log.d(TAG, "Stop word detected: \"$transcript\"")
            onStopWordDetected()
            return
        }
        val match = wakeWordMatch(transcript)
        if (!wakeDetected && match != null) {
            wakeDetected = true
            Log.d(TAG, "Wake word detected ($match): \"$transcript\"")
            onWakeWordDetected()
        }
    }

    private fun isStopWordMatch(text: String): Boolean {
        val normalized = normalizePhrase(text)
        if (STOP_PHRASES.any { normalized.contains(it) }) return true
        val words = normalized.split(" ").filter { it.isNotBlank() }
        val stopIndex = words.indexOfFirst { it in STOP_VERBS }
        if (stopIndex == -1 || stopIndex == words.lastIndex) return false
        val candidate = words[stopIndex + 1]
        return candidate in WAKE_WORD_ALIASES || editDistance(candidate, "omni") <= 2
    }

    private fun isWakeWordMatch(text: String): Boolean {
        return wakeWordMatch(text) != null
    }

    private fun wakeWordMatch(text: String): String? {
        val normalized = normalizePhrase(text)
        if (normalized.isBlank()) return null

        normalizedWakeWords.firstOrNull { normalized.contains(it) }?.let {
            return "phrase:$it"
        }

        val words = normalized.split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val wakeTokens = normalizePhrase(wakeWord)
            .split(" ")
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("hey", "omni") }

        val phraseScore = orderedPhraseScore(words, wakeTokens)
        if (phraseScore >= WAKE_PHRASE_SCORE_THRESHOLD) {
            return "phraseScore:${"%.2f".format(phraseScore)}"
        }

        val anchorToken = wakeTokens.lastOrNull() ?: "omni"
        val bestAnchor = words
            .map { it to tokenSimilarity(it, anchorToken) }
            .maxByOrNull { it.second }
        if (bestAnchor != null && bestAnchor.second >= WAKE_ANCHOR_SCORE_THRESHOLD) {
            return "anchor:${bestAnchor.first}:${"%.2f".format(bestAnchor.second)}"
        }

        if (words.size >= wakeTokens.size) {
            var bestWindow = 0.0
            for (i in 0..(words.size - wakeTokens.size)) {
                val score = wakeTokens.indices
                    .map { tokenSimilarity(words[i + it], wakeTokens[it]) }
                    .average()
                if (score > bestWindow) bestWindow = score
            }
            if (bestWindow >= WAKE_WINDOW_SCORE_THRESHOLD) {
                return "window:${"%.2f".format(bestWindow)}"
            }
        }

        return null
    }

    private fun orderedPhraseScore(words: List<String>, wakeTokens: List<String>): Double {
        if (words.isEmpty() || wakeTokens.isEmpty()) return 0.0
        var searchFrom = 0
        var score = 0.0
        var matched = 0
        for (target in wakeTokens) {
            var bestIndex = -1
            var bestScore = 0.0
            for (i in searchFrom until words.size) {
                val candidateScore = tokenSimilarity(words[i], target)
                if (candidateScore > bestScore) {
                    bestScore = candidateScore
                    bestIndex = i
                }
            }
            if (bestIndex == -1) continue
            score += bestScore
            matched += 1
            searchFrom = bestIndex + 1
        }
        val coverage = matched.toDouble() / wakeTokens.size
        return (score / wakeTokens.size) * coverage
    }

    private fun tokenSimilarity(candidate: String, target: String): Double {
        if (candidate.isBlank() || target.isBlank()) return 0.0
        if (candidate == target) return 1.0

        val maxLength = maxOf(candidate.length, target.length).toDouble()
        val editScore = 1.0 - (editDistance(candidate, target) / maxLength)
        val prefixScore = commonPrefixLength(candidate, target) / maxLength
        val substringScore = if (candidate.contains(target) || target.contains(candidate)) 0.86 else 0.0

        return maxOf(editScore, prefixScore, substringScore).coerceIn(0.0, 1.0)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val max = minOf(a.length, b.length)
        for (i in 0 until max) {
            if (a[i] != b[i]) return i
        }
        return max
    }

    private fun normalizePhrase(text: String): String {
        return text
            .lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val substitutionCost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitutionCost,
                )
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }

    private fun handleCommandResult(transcript: String, isFinal: Boolean, speechFinal: Boolean) {
        if (System.currentTimeMillis() < ignoreUntil) return

        emptyCommandFinals = 0

        // Strip wake word if it leaked into the command
        val cleaned = transcript
            .replace(Regex("(?i)${Regex.escape(wakeWord)}"), "")
            .trim()
        if (cleaned.isBlank() || isOnlyWakePhrase(transcript)) {
            if (isFinal || speechFinal) reportNoCommandIfReady()
            return
        }

        if (isFinal || speechFinal) {
            Log.d(TAG, "Command final: $cleaned")
            commandTranscript.append(cleaned).append(" ")
            onFinalResult(cleaned)
            scheduleCommandFinalize()
        } else {
            onPartialResult(cleaned)
        }
        if (speechFinal) finalizeCommand()
    }

    private fun scheduleCommandFinalize() {
        commandFinalizeJob?.cancel()
        commandFinalizeJob = scope.launch {
            val minListenRemaining = (MIN_COMMAND_LISTEN_MS - (System.currentTimeMillis() - commandModeStartTime))
                .coerceAtLeast(0L)
            delay(maxOf(COMMAND_FINALIZE_DEBOUNCE_MS, minListenRemaining))
            finalizeCommand()
        }
    }

    private fun finalizeCommand() {
        commandFinalizeJob?.cancel()
        commandFinalizeJob = null
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
        } else {
            reportNoCommandIfReady()
        }
    }

    private fun reportNoCommandIfReady() {
        if (noCommandReported) return
        val elapsed = System.currentTimeMillis() - commandModeStartTime
        if (elapsed < MIN_COMMAND_LISTEN_MS) {
            commandFinalizeJob?.cancel()
            commandFinalizeJob = scope.launch {
                delay(MIN_COMMAND_LISTEN_MS - elapsed)
                reportNoCommandIfReady()
            }
            return
        }
        noCommandReported = true
        commandTranscript.clear()
        Log.d(TAG, "No command captured")
        onNoCommand()
    }

    private fun isOnlyWakePhrase(text: String): Boolean {
        val normalized = normalizePhrase(text)
        return normalizedWakeWords.any { normalized == it }
    }

    companion object {
        private const val TAG = "DeepgramClient"
        private const val SAMPLE_RATE = 16000
        private const val MIN_COMMAND_LISTEN_MS = 2000L
        private const val COMMAND_FINALIZE_DEBOUNCE_MS = 900L
        private const val AUDIO_LEVEL_INTERVAL_MS = 50L
        private const val MAX_EMPTY_COMMAND_FINALS = 2
        private const val MAX_EMPTY_WAKE_FINALS = 6
        private const val COMMAND_GAIN = 4
        private const val WAKE_GAIN = 3
        private const val SOFT_KNEE = 24000  // start soft-clipping at ~73% of full scale
        private const val DC_ALPHA = 0.001  // running-mean rate for DC offset tracker
        private const val PRE_EMPHASIS = 0.97  // y[n] = x[n] - 0.97*x[n-1]
        private const val PENDING_MAX_BYTES = 96000  // ~3s of buffered audio while WS handshakes
        private const val DEAD_STREAM_PEAK_THRESHOLD = 8
        private const val SILENT_SOURCE_FALLBACK_MS = 4_000L
        private val WAKE_WORD_ALIASES = setOf("omni", "umini", "umni", "mini", "omie", "homie")
        private const val WAKE_PHRASE_SCORE_THRESHOLD = 0.55
        private const val WAKE_ANCHOR_SCORE_THRESHOLD = 0.50
        private const val WAKE_WINDOW_SCORE_THRESHOLD = 0.58
        private val STOP_VERBS = setOf("stop", "para", "pare")
        private val STOP_PHRASES = setOf("stop omni", "stop homie", "para omni", "pare omni")
    }
}
