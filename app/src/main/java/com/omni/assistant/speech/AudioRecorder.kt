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
    private val onSilenceTimeout: (ByteArray) -> Unit,
) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioBuffer = ByteArrayOutputStream()

    fun start(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

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
                selected = rec
                break
            }
            rec.release()
        }
        audioRecord = selected ?: return

        audioBuffer.reset()
        audioRecord?.startRecording()
        isRecording = true

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            var silentFrames = 0
            var hasSpeech = false
            var totalFrames = 0

            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read <= 0) continue

                // Calculate peak amplitude
                var peak = 0
                var i = 0
                while (i < read - 1) {
                    val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                    val abs = if (sample < 0) -sample else sample
                    if (abs > peak) peak = abs
                    i += 2
                }

                val level = (peak / 32767f).coerceIn(0f, 1f)
                onAudioLevel(level)

                // Buffer audio
                synchronized(audioBuffer) {
                    audioBuffer.write(buffer, 0, read)
                }
                totalFrames++

                // Silence detection
                val isSilent = peak < SILENCE_THRESHOLD
                if (!isSilent) {
                    hasSpeech = true
                    silentFrames = 0
                } else if (hasSpeech) {
                    silentFrames++
                }

                // Stop after sustained silence following speech
                val silentMs = silentFrames * (read / 2) * 1000L / SAMPLE_RATE
                if (hasSpeech && silentMs >= SILENCE_DURATION_MS) {
                    Log.d(TAG, "Silence detected after speech, stopping (${audioBuffer.size() / 1024}KB)")
                    break
                }

                // Hard timeout
                val totalMs = totalFrames * (read / 2) * 1000L / SAMPLE_RATE
                if (totalMs >= MAX_RECORDING_MS) {
                    Log.d(TAG, "Max recording time reached")
                    break
                }
            }

            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            val audio = synchronized(audioBuffer) { audioBuffer.toByteArray() }
            if (hasSpeech && audio.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    onSilenceTimeout(audio)
                }
            }
        }
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
        private const val SILENCE_THRESHOLD = 800   // ~2.4% of max amplitude
        private const val SILENCE_DURATION_MS = 1500L // 1.5s of silence = done
        private const val MAX_RECORDING_MS = 30_000L  // 30s max
    }
}
