package com.buddy.assistant.llm

import android.content.Intent
import android.content.pm.PackageManager
import com.buddy.assistant.BuddyApplication
import com.buddy.assistant.data.AgentAction
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

class LLMClient(private val app: BuddyApplication) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getNextAction(
        goal: String,
        screenDescription: String,
        history: List<Map<String, String>>
    ): LLMResponse {
        val provider = app.settingsRepository.llmProvider.first()
        val model = app.settingsRepository.llmModel.first()
        val apiKey = app.settingsRepository.apiKey.first()
        val systemPrompt = app.settingsRepository.systemPrompt.first()

        if (apiKey.isBlank()) throw IllegalStateException("API key not configured")

        val userMessage = buildString {
            appendLine("Goal: $goal")
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
        // Extract JSON from markdown code blocks if present
        val jsonStr = rawJson.let {
            val jsonStart = it.indexOf('{')
            val jsonEnd = it.lastIndexOf('}')
            if (jsonStart >= 0 && jsonEnd > jsonStart) it.substring(jsonStart, jsonEnd + 1) else it
        }

        val json = try {
            gson.fromJson(jsonStr, JsonObject::class.java)
        } catch (e: Exception) {
            throw IOException("Failed to parse LLM response as JSON: $rawJson")
        }

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
            "open_app" -> AgentAction.OpenApp(params.get("package")?.asString ?: "")
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
}
