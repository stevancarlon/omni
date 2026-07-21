package com.omni.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.omni.assistant.service.OmniListenerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, OmniListenerService::class.java).apply {
                action = OmniListenerService.ACTION_START_WAKE_WORD
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
