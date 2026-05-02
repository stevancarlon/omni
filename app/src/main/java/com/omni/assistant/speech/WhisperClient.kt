package com.omni.assistant.speech

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.omni.assistant.OmniApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Sends recorded PCM audio to the backend's Whisper endpoint for
 * high-accuracy transcription. Used as a post-correction step after
 * Deepgram provides real-time partials.
 */
class WhisperClient(private val app: OmniApplication) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    data class TranscriptionResult(val text: String, val language: String)

    /**
     * Transcribes raw PCM16 audio (16kHz mono) via the backend Whisper endpoint.
     * Returns the transcribed text or null on failure.
     */
    suspend fun transcribe(
        pcmAudio: ByteArray,
        language: String = "",
    ): TranscriptionResult? = withContext(Dispatchers.IO) {
        if (pcmAudio.size < 3200) { // less than 0.1s of audio
            Log.w(TAG, "Audio too short (${pcmAudio.size} bytes), skipping Whisper")
            return@withContext null
        }

        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val authToken = app.settingsRepository.authToken.first()

        // Wrap PCM in a WAV container — Whisper needs a proper audio format
        val wav = wrapPcmInWav(pcmAudio, sampleRate = 16000, channels = 1, bitsPerSample = 16)

        val audioBody = wav.toRequestBody("audio/wav".toMediaType())
        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", "command.wav", audioBody)
            .apply {
                if (language.isNotBlank()) {
                    addFormDataPart("language", language)
                }
            }
            .build()

        val request = Request.Builder()
            .url("$backendUrl/api/speech/transcribe")
            .post(multipartBody)
            .header("Authorization", "Bearer $authToken")
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "Whisper failed (${response.code}): ${body.take(200)}")
                return@withContext null
            }
            val json = gson.fromJson(body, JsonObject::class.java)
            val text = json.get("text")?.asString?.trim().orEmpty()
            val lang = json.get("language")?.asString.orEmpty()
            Log.d(TAG, "Whisper transcribed: \"$text\" (lang=$lang, audio=${pcmAudio.size / 1024}KB)")
            TranscriptionResult(text, lang)
        } catch (e: Exception) {
            Log.e(TAG, "Whisper error: ${e.message}")
            null
        }
    }

    private fun wrapPcmInWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val out = ByteArrayOutputStream(44 + dataSize)

        // RIFF header
        out.write("RIFF".toByteArray())
        out.write(intToLittleEndian(36 + dataSize))
        out.write("WAVE".toByteArray())

        // fmt chunk
        out.write("fmt ".toByteArray())
        out.write(intToLittleEndian(16))             // chunk size
        out.write(shortToLittleEndian(1))             // PCM format
        out.write(shortToLittleEndian(channels))
        out.write(intToLittleEndian(sampleRate))
        out.write(intToLittleEndian(byteRate))
        out.write(shortToLittleEndian(blockAlign))
        out.write(shortToLittleEndian(bitsPerSample))

        // data chunk
        out.write("data".toByteArray())
        out.write(intToLittleEndian(dataSize))
        out.write(pcm)

        return out.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    private fun shortToLittleEndian(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        )

    companion object {
        private const val TAG = "WhisperClient"
    }
}
