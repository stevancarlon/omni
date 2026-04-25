package com.omni.assistant.llm

import android.content.Intent
import android.content.pm.PackageManager
import com.omni.assistant.OmniApplication
import com.omni.assistant.data.AgentAction
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class LLMResponse(
    val think: String,
    val actionType: String,
    val action: AgentAction,
    val rawJson: String
)

class LLMClient(private val app: OmniApplication) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getNextAction(
        goal: String,
        screenDescription: String,
        history: List<Map<String, String>>,
        voiceCandidates: List<String> = listOf(goal)
    ): LLMResponse {
        val provider = app.settingsRepository.llmProvider.first()
        val model = app.settingsRepository.llmModel.first()
        val apiKey = app.settingsRepository.apiKeyForProvider(provider).first()
        val systemPrompt = app.settingsRepository.systemPrompt.first()

        if (apiKey.isBlank()) throw IllegalStateException("API key not configured for $provider")

        val userMessage = buildString {
            appendLine("Goal: $goal")
            if (voiceCandidates.size > 1) {
                appendLine("Voice recognition alternatives: ${voiceCandidates.joinToString(" | ")}")
                appendLine("(Pick the interpretation that best matches an installed app or makes the most sense)")
            }
            appendLine()
            appendLine("Installed apps:")
            appendLine(getInstalledApps())
            appendLine()
            appendLine("Current screen state:")
            appendLine(screenDescription.take(4000))
        }

        val messages = mutableListOf<Map<String, String>>()
        messages.addAll(history.takeLast(10)) // Keep last 10 turns
        messages.add(mapOf("role" to "user", "content" to userMessage))

        return when (provider) {
            "claude" -> callClaudeAPI(apiKey, model, systemPrompt, messages)
            "openai" -> callOpenAIAPI(apiKey, model, systemPrompt, messages)
            "openrouter" -> callOpenRouterAPI(apiKey, model, systemPrompt, messages)
            "groq" -> callGroqAPI(apiKey, model, systemPrompt, messages)
            else -> callClaudeAPI(apiKey, model, systemPrompt, messages)
        }
    }

    private fun callClaudeAPI(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<Map<String, String>>
    ): LLMResponse {
        val body = gson.toJson(mapOf(
            "model" to model,
            "max_tokens" to 1024,
            "system" to systemPrompt,
            "messages" to messages
        ))

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("API error ${response.code}: $responseBody")

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val content = json.getAsJsonArray("content")
            .get(0).asJsonObject
            .get("text").asString

        return parseAgentResponse(content)
    }

    private fun callOpenAIAPI(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<Map<String, String>>
    ): LLMResponse {
        val allMessages = mutableListOf(mapOf("role" to "system", "content" to systemPrompt))
        allMessages.addAll(messages)

        val body = gson.toJson(mapOf(
            "model" to model,
            "max_tokens" to 1024,
            "messages" to allMessages,
            "response_format" to mapOf("type" to "json_object")
        ))

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("API error ${response.code}: $responseBody")

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val content = json.getAsJsonArray("choices")
            .get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString

        return parseAgentResponse(content)
    }

    private fun callOpenRouterAPI(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<Map<String, String>>
    ): LLMResponse {
        val allMessages = mutableListOf(mapOf("role" to "system", "content" to systemPrompt))
        allMessages.addAll(messages)

        val body = gson.toJson(mapOf(
            "model" to model,
            "max_tokens" to 1024,
            "messages" to allMessages
        ))

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("API error ${response.code}: $responseBody")

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val content = json.getAsJsonArray("choices")
            .get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString

        return parseAgentResponse(content)
    }

    private fun callGroqAPI(
        apiKey: String,
        model: String,
        systemPrompt: String,
        messages: List<Map<String, String>>
    ): LLMResponse {
        val allMessages = mutableListOf(mapOf("role" to "system", "content" to systemPrompt))
        allMessages.addAll(messages)

        val body = gson.toJson(mapOf(
            "model" to model,
            "max_tokens" to 1024,
            "messages" to allMessages,
            "response_format" to mapOf("type" to "json_object")
        ))

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response")
        if (!response.isSuccessful) throw IOException("API error ${response.code}: $responseBody")

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val content = json.getAsJsonArray("choices")
            .get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString

        return parseAgentResponse(content)
    }

    private fun getInstalledApps(): String {
        val pm = app.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
        return apps
            .map { it.activityInfo }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .joinToString("\n") { info ->
                val label = info.loadLabel(pm)
                "${info.packageName} — $label"
            }
    }

    private fun parseAgentResponse(rawJson: String): LLMResponse {
        // Try parsing the whole response first, then try extracting JSON
        val json = tryParseJson(rawJson)
            ?: tryParseJson(extractJsonBlock(rawJson))
            ?: throw IOException("Failed to parse LLM response as JSON: ${rawJson.take(500)}")

        val think = json.get("think")?.asString ?: ""
        val actionObj = json.getAsJsonObject("action") ?: throw IOException("No action in response")
        val actionType = actionObj.get("type")?.asString ?: "wait"
        val params = actionObj.getAsJsonObject("params") ?: JsonObject()

        val action: AgentAction = when (actionType) {
            "tap" -> AgentAction.Tap(
                nodeId = params.get("nodeId")?.asString,
                x = params.get("x")?.asInt,
                y = params.get("y")?.asInt
            )
            "type" -> AgentAction.TypeText(
                text = params.get("text")?.asString ?: "",
                nodeId = params.get("nodeId")?.asString
            )
            "swipe" -> AgentAction.Swipe(params.get("direction")?.asString ?: "up")
            "scroll" -> AgentAction.Scroll(
                direction = params.get("direction")?.asString ?: "down",
                nodeId = params.get("nodeId")?.asString
            )
            "press_back" -> AgentAction.PressBack
            "press_home" -> AgentAction.PressHome
            "press_recents" -> AgentAction.PressRecents
            "open_app" -> AgentAction.OpenApp(
                packageName = params.get("package")?.asString ?: "",
                name = params.get("name")?.asString
            )
            "open_url" -> AgentAction.OpenUrl(params.get("url")?.asString ?: "")
            "wait" -> AgentAction.Wait(params.get("ms")?.asLong ?: 2000L)
            "done" -> AgentAction.Done(
                success = params.get("success")?.asBoolean ?: true,
                reason = params.get("reason")?.asString ?: "Task completed"
            )
            else -> AgentAction.Wait(1000L)
        }

        return LLMResponse(think, actionType, action, rawJson)
    }

    private fun tryParseJson(text: String): JsonObject? {
        return try {
            val obj = gson.fromJson(text.trim(), JsonObject::class.java)
            if (obj?.has("action") == true) obj else null
        } catch (_: Exception) { null }
    }

    private fun extractJsonBlock(text: String): String {
        // Find balanced JSON object containing "action"
        var depth = 0
        var start = -1
        for (i in text.indices) {
            when (text[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val candidate = text.substring(start, i + 1)
                        if (candidate.contains("\"action\"")) return candidate
                        start = -1
                    }
                }
            }
        }
        // Fallback: first { to last }
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first >= 0 && last > first) text.substring(first, last + 1) else text
    }
}
