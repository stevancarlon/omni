package com.omni.assistant.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Builds a detailed app inventory report by sending the installed app list
 * to the backend, which uses Claude to generate region-aware navigation
 * scenarios. The report is cached locally and only regenerated when the
 * installed app set changes.
 */
class AppInventory(private val context: Context) {

    private val gson = Gson()
    private val reportFile = File(context.filesDir, "app_inventory.json")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private var inFlightReport: Deferred<CachedReport>? = null

    data class CachedReport(
        val appHash: String,
        val generatedAt: Long,
        val report: String,
    )

    /**
     * Returns the cached LLM report, or generates a fresh one via the backend.
     */
    suspend fun getOrGenerate(): CachedReport = coroutineScope {
        val currentHash = computeAppHash()
        val cached = loadCached()
        if (cached != null && cached.appHash == currentHash) {
            Log.d(TAG, "App inventory cache hit")
            return@coroutineScope cached
        }

        val existing = synchronized(this@AppInventory) {
            inFlightReport?.takeIf { it.isActive }
        }
        if (existing != null) {
            Log.d(TAG, "Waiting for in-flight app inventory")
            return@coroutineScope existing.await()
        }

        val job = async(Dispatchers.IO) { generateReport(currentHash) }
        synchronized(this@AppInventory) { inFlightReport = job }

        try {
            job.await()
        } finally {
            synchronized(this@AppInventory) {
                if (inFlightReport == job) inFlightReport = null
            }
        }
    }

    suspend fun getCachedOrFallback(): CachedReport = withContext(Dispatchers.IO) {
        val currentHash = computeAppHash()
        loadCached()?.takeIf { it.appHash == currentHash } ?: CachedReport(
            appHash = currentHash,
            generatedAt = System.currentTimeMillis(),
            report = generateFallback(),
        )
    }

    /**
     * Returns the report text ready for injection into the LLM prompt.
     */
    fun formatForPrompt(report: CachedReport): String = report.report

    // ─── Backend Generation ──────────────────────────────────────────────────

    private suspend fun generateViaBackend(appHash: String): CachedReport {
        val settingsRepo = (context.applicationContext as com.omni.assistant.OmniApplication).settingsRepository
        val backendUrl = settingsRepo.backendUrl.first().trimEnd('/')
        val authToken = settingsRepo.authToken.first()

        val apps = getInstalledAppList()
        val language = settingsRepo.speechLanguage.first().ifBlank { Locale.getDefault().toLanguageTag() }
        val region = Locale.getDefault().country.ifBlank { "US" }

        val body = gson.toJson(mapOf(
            "apps" to apps,
            "language" to language,
            "region" to region,
        ))

        val request = Request.Builder()
            .url("$backendUrl/api/apps/inventory")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful) {
            throw RuntimeException("Backend returned ${response.code}: ${responseBody.take(200)}")
        }

        val json = gson.fromJson(responseBody, JsonObject::class.java)
        val report = json.get("report")?.asString
            ?: throw RuntimeException("No report in response")

        return CachedReport(
            appHash = appHash,
            generatedAt = System.currentTimeMillis(),
            report = report,
        )
    }

    private suspend fun generateReport(appHash: String): CachedReport {
        Log.d(TAG, "Generating app inventory via backend...")
        val report = try {
            generateViaBackend(appHash)
        } catch (e: Exception) {
            Log.e(TAG, "Backend inventory failed: ${e.message}, using fallback")
            CachedReport(
                appHash = appHash,
                generatedAt = System.currentTimeMillis(),
                report = generateFallback(),
            )
        }
        save(report)
        Log.d(TAG, "App inventory ready (${report.report.length} chars)")
        return report
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun getInstalledAppList(): List<Map<String, String>> {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
            .map { resolveInfo ->
                val info = resolveInfo.activityInfo
                mapOf(
                    "package" to info.packageName,
                    "label" to info.loadLabel(pm).toString(),
                )
            }
            .sortedBy { it["label"]?.lowercase() }
    }

    private fun generateFallback(): String {
        val apps = getInstalledAppList()
        return buildString {
            appendLine("# Installed Apps")
            appendLine()
            apps.forEach { app ->
                appendLine("- ${app["label"]} (${app["package"]})")
            }
        }.trimEnd()
    }

    // ─── Persistence ─────────────────────────────────────────────────────────

    private fun computeAppHash(): String {
        val pm = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = pm.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .sorted()
            .joinToString(",")
        return packages.hashCode().toString(16)
    }

    private fun loadCached(): CachedReport? {
        if (!reportFile.exists()) return null
        return try {
            gson.fromJson(reportFile.readText(), CachedReport::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached inventory: ${e.message}")
            null
        }
    }

    private fun save(report: CachedReport) {
        try {
            reportFile.writeText(gson.toJson(report))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save inventory: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AppInventory"
    }
}
