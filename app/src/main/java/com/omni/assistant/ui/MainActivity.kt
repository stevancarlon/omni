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
import com.omni.assistant.OmniApplication
import com.omni.assistant.agent.AgentController
import com.omni.assistant.service.OmniListenerService
import com.omni.assistant.service.OmniOverlayService
import com.omni.assistant.ui.theme.OmniTheme

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
        // Request battery exemption once, after first launch
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

    private fun startWakeWordIfReady() {
        val hasPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
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
