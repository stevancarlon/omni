package com.omni.assistant.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

/**
 * Simple audio recorder that captures PCM16 mono 16kHz audio and detects
 * silence to auto-stop. Designed for voice command capture → Whisper.
 */
class AudioRecorder(
    private val onAudioLevel: (Float) -> Unit = {},
    private val onNoSpeechTimeout: () -> Unit = {},
    private val onSilenceTimeout: (ByteArray) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioBuffer = ByteArrayOutputStream()

    fun start(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Microphone permission not granted")
            return false
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $bufferSize")
            return false
        }

        val sources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
        )
        var selected: AudioRecord? = null
        for (src in sources) {
            val rec = try {
                AudioRecord(src, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2)
            } catch (_: Exception) { continue }
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Using command audio source=$src")
                selected = rec
                break
            }
            rec.release()
        }
        audioRecord = selected ?: run {
            Log.e(TAG, "Failed to initialize command audio recorder")
            return false
        }

        audioBuffer.reset()
        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start command audio recorder: ${e.message}")
            audioRecord?.release()
            audioRecord = null
            return false
        }
        isRecording = true

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            var hasSpeech = false
            var totalMs = 0L
            var silentMs = 0L
            var speechMs = 0L
            var noiseFloor = 0.0
            var calibrationMs = 0L
            var lastLogMs = 0L

            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read <= 0) continue

                val frameMs = (read / 2) * 1000L / SAMPLE_RATE
                totalMs += frameMs

                // Calculate peak and RMS amplitude. RMS is less sensitive to
                // brief noise spikes than peak, which makes endpointing more
                // reliable in cars and outdoor environments.
                var peak = 0
                var sumSquares = 0.0
                var sampleCount = 0
                var i = 0
                while (i < read - 1) {
                    val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                    val abs = if (sample < 0) -sample else sample
                    if (abs > peak) peak = abs
                    sumSquares += sample.toDouble() * sample.toDouble()
                    sampleCount++
                    i += 2
                }
                val rms = if (sampleCount > 0) kotlin.math.sqrt(sumSquares / sampleCount) else 0.0

                val level = (rms / 12000.0).toFloat().coerceIn(0f, 1f)
                onAudioLevel(level)

                // Buffer audio
                synchronized(audioBuffer) {
                    audioBuffer.write(buffer, 0, read)
                }

                if (calibrationMs < NOISE_CALIBRATION_MS) {
                    noiseFloor = if (calibrationMs == 0L) rms else noiseFloor * 0.85 + rms * 0.15
                    calibrationMs += frameMs
                } else if (!hasSpeech) {
                    // Before speech starts, keep adapting to ambient noise so
                    // steady car/road noise does not count as a command.
                    noiseFloor = noiseFloor * 0.96 + rms * 0.04
                } else if (rms < noiseFloor * 1.4) {
                    // After speech starts, adapt only during likely silence.
                    noiseFloor = noiseFloor * 0.98 + rms * 0.02
                }

                val speechThreshold = maxOf(
                    MIN_SPEECH_RMS,
                    noiseFloor * SPEECH_OVER_NOISE_RATIO + SPEECH_OVER_NOISE_OFFSET,
                )
                val silenceThreshold = maxOf(
                    MIN_SILENCE_RMS,
                    noiseFloor * SILENCE_OVER_NOISE_RATIO + SILENCE_OVER_NOISE_OFFSET,
                )
                val requiredSilenceMs = requiredSilenceMs(noiseFloor)
                val isSpeech = rms >= speechThreshold
                val isSilent = rms <= silenceThreshold

                if (isSpeech) {
                    hasSpeech = true
                    silentMs = 0
                    speechMs += frameMs
                } else if (hasSpeech && isSilent) {
                    silentMs += frameMs
                } else if (hasSpeech) {
                    // Ambiguous low-level noise is common in a moving car. It
                    // should move us toward endpointing, but much slower than
                    // true silence so brief pauses between words are preserved.
                    silentMs += frameMs / AMBIGUOUS_SILENCE_DIVISOR
                }

                // Stop after sustained silence following speech
                if (hasSpeech && silentMs >= requiredSilenceMs) {
                    Log.d(TAG, "Adaptive silence detected after speech, stopping (${audioBuffer.size() / 1024}KB, silence=${silentMs}ms/${requiredSilenceMs}ms, noise=${noiseFloor.toInt()}, rms=${rms.toInt()})")
                    break
                }

                if (hasSpeech && speechMs >= MAX_RECORDING_AFTER_SPEECH_MS) {
                    Log.d(TAG, "Max command duration after speech reached")
                    break
                }

                if (!hasSpeech && totalMs >= MAX_WAIT_FOR_SPEECH_MS) {
                    Log.d(TAG, "No speech detected within command window")
                    break
                }

                // Hard timeout
                if (totalMs >= MAX_RECORDING_MS) {
                    Log.d(TAG, "Max recording time reached")
                    break
                }

                if (totalMs - lastLogMs >= 3000L) {
                    lastLogMs = totalMs
                    Log.d(TAG, "VAD: rms=${rms.toInt()} noise=${noiseFloor.toInt()} speechTh=${speechThreshold.toInt()} silenceTh=${silenceThreshold.toInt()} requiredSilence=${requiredSilenceMs}ms hasSpeech=$hasSpeech")
                }
            }

            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val audio = synchronized(audioBuffer) { audioBuffer.toByteArray() }
            withContext(Dispatchers.Main) {
                if (hasSpeech && audio.isNotEmpty()) {
                    onSilenceTimeout(audio)
                } else {
                    onNoSpeechTimeout()
                }
            }
        }

        return true
    }

    private fun requiredSilenceMs(noiseFloor: Double): Long {
        val noiseRatio = ((noiseFloor - QUIET_NOISE_FLOOR) / (NOISY_NOISE_FLOOR - QUIET_NOISE_FLOOR))
            .coerceIn(0.0, 1.0)
        return (MIN_SILENCE_DURATION_MS + (MAX_SILENCE_DURATION_MS - MIN_SILENCE_DURATION_MS) * noiseRatio).toLong()
    }

    fun stop(): ByteArray {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        return synchronized(audioBuffer) { audioBuffer.toByteArray() }
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val NOISE_CALIBRATION_MS = 500L
        private const val MIN_SPEECH_RMS = 900.0
        private const val MIN_SILENCE_RMS = 650.0
        private const val SPEECH_OVER_NOISE_RATIO = 2.4
        private const val SPEECH_OVER_NOISE_OFFSET = 450.0
        private const val SILENCE_OVER_NOISE_RATIO = 1.45
        private const val SILENCE_OVER_NOISE_OFFSET = 250.0
        private const val MIN_SILENCE_DURATION_MS = 1700L
        private const val MAX_SILENCE_DURATION_MS = 3200L
        private const val QUIET_NOISE_FLOOR = 350.0
        private const val NOISY_NOISE_FLOOR = 1800.0
        private const val AMBIGUOUS_SILENCE_DIVISOR = 4
        private const val MAX_WAIT_FOR_SPEECH_MS = 6_000L
        private const val MAX_RECORDING_AFTER_SPEECH_MS = 12_000L
        private const val MAX_RECORDING_MS = 30_000L  // 30s max
    }
}
