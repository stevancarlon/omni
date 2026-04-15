package com.buddy.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.buddy.assistant.service.BuddyListenerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, BuddyListenerService::class.java).apply {
                action = BuddyListenerService.ACTION_START_WAKE_WORD
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
