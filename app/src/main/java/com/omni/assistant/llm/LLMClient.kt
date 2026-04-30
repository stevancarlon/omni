package com.omni.assistant.llm

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
    private var cachedInventoryPrompt: String? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getNextAction(
        goal: String,
        screenDescription: String,
        history: List<Map<String, String>>,
        voiceCandidates: List<String> = listOf(goal),
        warnings: String? = null,
        screenshotBase64: String? = null,
        isFirstStep: Boolean = false,
        foregroundApp: String = "",
        pastGuidance: String? = null,
    ): LLMResponse {
        val authToken = app.settingsRepository.authToken.first()
        val backendUrl = app.settingsRepository.backendUrl.first()
        val systemPrompt = app.settingsRepository.systemPrompt.first()

        if (authToken.isBlank()) throw IllegalStateException("Not logged in — please sign in first")

        val userText = buildString {
            appendLine("Goal: $goal")
            if (voiceCandidates.size > 1) {
                appendLine("Voice recognition alternatives: ${voiceCandidates.joinToString(" | ")}")
                appendLine("(Pick the interpretation that best matches an installed app or makes the most sense)")
            }
            if (!warnings.isNullOrBlank()) {
                appendLine()
                appendLine(warnings)
            }
            // Only include the full app inventory on the first step to save tokens
            if (isFirstStep) {
                val inventoryPrompt = cachedInventoryPrompt ?: run {
                    val report = app.appInventory.getOrGenerate()
                    app.appInventory.formatForPrompt(report).also { cachedInventoryPrompt = it }
                }
                appendLine()
                appendLine(inventoryPrompt)
                // Include past successful sequences as guidance
                if (!pastGuidance.isNullOrBlank()) {
                    appendLine()
                    appendLine("═══ PAST SUCCESSFUL EXECUTION (adapt to current screen) ═══")
                    appendLine(pastGuidance)
                }
            }
            appendLine()
            if (foregroundApp.isNotBlank()) {
                appendLine("Currently in: $foregroundApp")
            }
            appendLine("Current screen state:")
            appendLine(screenDescription.take(4000))
        }

        // Build the user message — with screenshot as multipart content if available
        val userMessage: Any = if (screenshotBase64 != null) {
            // Claude multipart content: image + text
            listOf(
                mapOf(
                    "type" to "image",
                    "source" to mapOf(
                        "type" to "base64",
                        "media_type" to "image/jpeg",
                        "data" to screenshotBase64
                    )
                ),
                mapOf("type" to "text", "text" to userText)
            )
        } else {
            userText
        }

        val messages = mutableListOf<Map<String, Any>>()
        // History messages are plain text (no images in history to save tokens)
        for (msg in history.takeLast(10)) {
            messages.add(mapOf("role" to (msg["role"] ?: "user"), "content" to (msg["content"] ?: "")))
        }
        messages.add(mapOf("role" to "user", "content" to userMessage))

        return callBackend(backendUrl, authToken, systemPrompt, messages)
    }

    private suspend fun callBackend(
        backendUrl: String,
        authToken: String,
        systemPrompt: String,
        messages: List<Map<String, Any>>
    ): LLMResponse {
        val body = gson.toJson(mapOf(
            "system" to systemPrompt,
            "messages" to messages
        ))

        val request = Request.Builder()
            .url("$backendUrl/api/llm/completions")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .build()

        val call = client.newCall(request)
        val response = kotlinx.coroutines.suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    cont.resumeWith(Result.success(response))
                }
            })
        }
        return response.use { resp ->
            val responseBody = resp.body?.string() ?: throw IOException("Empty response")
            if (!resp.isSuccessful) throw IOException("Backend error ${resp.code}: $responseBody")

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val content = json.get("content")?.asString
                ?: throw IOException("No content in backend response")

            parseAgentResponse(content)
        }
    }

    private fun parseAgentResponse(rawJson: String): LLMResponse {
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
                nodeId = params.get("nodeId")?.asString,
                x = params.get("x")?.asInt,
                y = params.get("y")?.asInt
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
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        return if (first >= 0 && last > first) text.substring(first, last + 1) else text
    }

    // ─── MobileRAG: Action Memory ───────────────────────────────────────────

    suspend fun searchMemories(goal: String): String? {
        val authToken = app.settingsRepository.authToken.first()
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        if (authToken.isBlank() || goal.length < 3) return null

        val request = Request.Builder()
            .url("$backendUrl/api/agent/memories/search?goal=${java.net.URLEncoder.encode(goal, "UTF-8")}")
            .get()
            .header("Authorization", "Bearer $authToken")
            .build()

        val call = client.newCall(request)
        val response = kotlinx.coroutines.suspendCancellableCoroutine<Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    cont.resumeWith(Result.success(response))
                }
            })
        }

        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string() ?: return@use null
            val json = gson.fromJson(body, JsonObject::class.java)
            val memories = json.getAsJsonArray("memories") ?: return@use null
            if (memories.size() == 0) return@use null

            val best = memories[0].asJsonObject
            val sim = best.get("similarity")?.asFloat ?: 0f
            val pastGoal = best.get("goal_text")?.asString ?: ""
            val actions = best.getAsJsonArray("action_sequence") ?: return@use null

            if (sim < 0.4f) return@use null

            val actionList = (0 until actions.size()).joinToString("\n") { i ->
                val a = actions[i].asJsonObject
                "  ${i + 1}. ${a.get("action")?.asString ?: "?"}"
            }

            "Similar past task (${(sim * 100).toInt()}% match): \"$pastGoal\"\nActions taken:\n$actionList"
        }
    }

    suspend fun saveMemory(goal: String, actions: List<Map<String, Any?>>, appContext: String, steps: Int) {
        val authToken = app.settingsRepository.authToken.first()
        val backendUrl = app.settingsRepository.backendUrl.first().trimEnd('/')
        if (authToken.isBlank()) return

        val body = gson.toJson(mapOf(
            "goal_text" to goal,
            "action_sequence" to actions,
            "app_context" to appContext,
            "steps_taken" to steps,
            "success" to true
        ))

        val request = Request.Builder()
            .url("$backendUrl/api/agent/memories")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $authToken")
            .build()

        // Fire and forget — don't block the agent
        val call = client.newCall(request)
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    cont.resumeWith(Result.success(Unit))
                }
            })
        }
    }
}
