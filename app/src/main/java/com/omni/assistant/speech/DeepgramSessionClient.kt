package com.omni.assistant.speech

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.omni.assistant.OmniApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DeepgramSession(val url: String, val accessToken: String)

class DeepgramSessionClient(private val app: OmniApplication) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun create(language: String): DeepgramSession = withContext(Dispatchers.IO) {
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val authToken = app.settingsRepository.authToken.first()
        val body = gson.toJson(mapOf("language" to language, "model" to "nova-3"))

        val request = Request.Builder()
            .url("$backendUrl/api/deepgram/token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException("Speech session failed (${response.code}): ${responseBody.take(200)}")
        }
        val json = gson.fromJson(responseBody, JsonObject::class.java)
        DeepgramSession(
            url = json.get("url")?.asString ?: throw IOException("Speech session missing URL"),
            accessToken = json.get("access_token")?.asString
                ?: json.get("token")?.asString
                ?: throw IOException("Speech session missing token"),
        )
    }
}
