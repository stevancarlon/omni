package com.omni.assistant.auth

import android.util.Log
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

data class AuthSession(
    val authToken: String,
    val email: String,
    val name: String,
    val subscriptionStatus: String,
    val subscriptionPlan: String,
)

class AuthRepository(private val app: OmniApplication) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun signInWithGoogle(
        idToken: String,
        emailHint: String,
        nameHint: String,
    ): AuthSession = withContext(Dispatchers.IO) {
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val body = gson.toJson(
            mapOf(
                "idToken" to idToken,
                "platform" to "android",
            )
        )

        val request = Request.Builder()
            .url("$backendUrl/api/auth/google")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        Log.i(TAG, "Signing in with Google via $backendUrl/api/auth/google")

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            Log.e(TAG, "Google sign-in request failed for $backendUrl", error)
            throw error
        }
        val responseBody = response.body?.string().orEmpty()
        Log.i(TAG, "Google sign-in backend response code=${response.code}, bytes=${responseBody.length}")
        if (!response.isSuccessful) {
            throw IOException("Google sign-in failed (${response.code}): ${responseBody.take(200)}")
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val authToken = firstString(json, "authToken", "token", "accessToken")
            ?: throw IOException("Google sign-in response did not include an auth token")

        AuthSession(
            authToken = authToken,
            email = firstString(json, "email", "accountEmail") ?: emailHint,
            name = firstString(json, "name", "displayName") ?: nameHint,
            subscriptionStatus = subscriptionStatus(json),
            subscriptionPlan = subscriptionPlan(json),
        )
    }

    suspend fun signInForCommunity(): AuthSession = withContext(Dispatchers.IO) {
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val body = gson.toJson(mapOf("device_name" to "android-community"))
        val request = Request.Builder()
            .url("$backendUrl/api/auth/community")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IOException(
                "Community sign-in failed (${response.code}). " +
                    "Start a self-hosted backend with COMMUNITY_MODE=true."
            )
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val user = json.getAsJsonObject("user")
        AuthSession(
            authToken = firstString(json, "authToken", "token", "accessToken")
                ?: throw IOException("Community sign-in did not return an auth token"),
            email = firstString(user, "email").orEmpty(),
            name = firstString(user, "name") ?: "Community user",
            subscriptionStatus = subscriptionStatus(json),
            subscriptionPlan = subscriptionPlan(json),
        )
    }

    suspend fun refreshSession(): AuthSession = withContext(Dispatchers.IO) {
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val authToken = app.settingsRepository.authToken.first()
        if (authToken.isBlank()) throw IOException("Not signed in")

        val request = Request.Builder()
            .url("$backendUrl/api/auth/me")
            .get()
            .header("Authorization", "Bearer $authToken")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()
        if (response.code == 401) throw UnauthorizedException()
        if (!response.isSuccessful) {
            throw IOException("Session refresh failed (${response.code}): ${responseBody.take(200)}")
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val user = json.getAsJsonObject("user")

        AuthSession(
            authToken = authToken,
            email = firstString(user, "email").orEmpty(),
            name = firstString(user, "name").orEmpty(),
            subscriptionStatus = subscriptionStatus(json),
            subscriptionPlan = subscriptionPlan(json),
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        val authToken = app.settingsRepository.authToken.first()
        if (authToken.isBlank()) return@withContext

        val request = Request.Builder()
            .url("$backendUrl/api/auth/logout")
            .delete()
            .header("Authorization", "Bearer $authToken")
            .build()

        client.newCall(request).execute().close()
    }

    private fun firstString(json: JsonObject?, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            json?.get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        }

    private fun subscriptionStatus(json: JsonObject): String {
        val subscription = json.getAsJsonObject("subscription")
        val active = subscription?.get("active")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
        if (!active) return "inactive"
        return firstString(json, "subscriptionStatus", "planStatus")
            ?: firstString(subscription, "status")
            ?: "inactive"
    }

    private fun subscriptionPlan(json: JsonObject): String {
        val subscription = json.getAsJsonObject("subscription")
        return firstString(subscription, "plan") ?: "free"
    }

    private companion object {
        const val TAG = "OmniAuth"
    }
}

class UnauthorizedException : IOException("Session expired")
