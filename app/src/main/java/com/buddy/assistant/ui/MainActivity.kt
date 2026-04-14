package com.buddy.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.buddy.assistant.BuddyApplication
import com.buddy.assistant.agent.AgentController
import com.buddy.assistant.service.BuddyListenerService
import com.buddy.assistant.ui.theme.BuddyTheme

class MainActivity : ComponentActivity() {

    private lateinit var agentController: AgentController

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchListenerService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        agentController = AgentController.getInstance(application as BuddyApplication)

        setContent {
            BuddyTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BuddyApp(agentController = agentController)
                }
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
        sendServiceAction(BuddyListenerService.ACTION_STOP)
    }

    fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun launchListenerService() {
        sendServiceAction(BuddyListenerService.ACTION_START_COMMAND_LISTENING)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, BuddyListenerService::class.java).apply {
            this.action = action
        }
        if (action == BuddyListenerService.ACTION_STOP) startService(intent)
        else startForegroundService(intent)
    }
}
