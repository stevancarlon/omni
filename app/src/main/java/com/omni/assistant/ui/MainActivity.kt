package com.omni.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.omni.assistant.OmniApplication
import com.omni.assistant.agent.AgentController
import com.omni.assistant.service.OmniListenerService
import com.omni.assistant.service.OmniOverlayService
import com.omni.assistant.ui.theme.OmniTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var agentController: AgentController

    private var pendingServiceAction: String? = null

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val action = pendingServiceAction ?: OmniListenerService.ACTION_START_COMMAND_LISTENING
            sendServiceAction(action)
            pendingServiceAction = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        agentController = AgentController.getInstance(application as OmniApplication)

        setContent {
            OmniTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    OmniApp(agentController = agentController)
                }
            }
        }

        // Auto-start wake word listening if we have mic permission
        startWakeWordIfReady()
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val onboardingComplete = (application as OmniApplication)
                .settingsRepository
                .onboardingComplete
                .first()
            if (!onboardingComplete) return@launch

            // Request battery exemption only after onboarding so system prompts
            // follow the designed permission sequence.
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val prefs = getSharedPreferences("omni_flags", MODE_PRIVATE)
                if (!prefs.getBoolean("battery_asked", false)) {
                    prefs.edit().putBoolean("battery_asked", true).apply()
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    fun startWakeWordIfReady() {
        lifecycleScope.launch {
            val repo = (application as OmniApplication).settingsRepository
            val authToken = repo.authToken.first()
            val onboardingComplete = repo.onboardingComplete.first()
            val subscriptionActive = repo.subscriptionStatus.first() == "active" &&
                repo.subscriptionPlan.first() != "free"
            if (authToken.isBlank() || !onboardingComplete || !subscriptionActive) {
                Log.d("OmniMain", "Skipping wake word until sign-in, subscription, and onboarding complete")
                return@launch
            }

            val hasPermission = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            Log.d("OmniMain", "startWakeWordIfReady: hasPermission=$hasPermission")
            if (hasPermission) {
                Log.d("OmniMain", "Starting wake word service...")
                sendServiceAction(OmniListenerService.ACTION_START_WAKE_WORD)
            } else {
                Log.d("OmniMain", "Requesting mic permission for wake word...")
                pendingServiceAction = OmniListenerService.ACTION_START_WAKE_WORD
                requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    fun startListenerService() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) launchListenerService()
        else requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun stopListenerService() {
        sendServiceAction(OmniListenerService.ACTION_STOP)
    }

    fun stopCommandListening() {
        sendServiceAction(OmniListenerService.ACTION_STOP_COMMAND)
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private var debugOverlayVisible = false
    fun debugToggleOverlay() {
        val intent = Intent(this, OmniOverlayService::class.java)
        if (debugOverlayVisible) {
            intent.action = OmniOverlayService.ACTION_HIDE
            startService(intent)
        } else {
            intent.action = OmniOverlayService.ACTION_SHOW
            startForegroundService(intent)
        }
        debugOverlayVisible = !debugOverlayVisible
    }

    private fun launchListenerService() {
        sendServiceAction(OmniListenerService.ACTION_START_COMMAND_LISTENING)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, OmniListenerService::class.java).apply {
            this.action = action
        }
        if (action == OmniListenerService.ACTION_STOP) startService(intent)
        else startForegroundService(intent)
    }
}
