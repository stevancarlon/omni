package com.buddy.assistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.buddy.assistant.BuddyApplication
import com.buddy.assistant.agent.AgentController

/**
 * Debug-only: receive a command over ADB without using the microphone.
 *
 *   adb shell am broadcast -a com.buddy.assistant.TEST_COMMAND --es text "abra o iFood"
 */
class TestCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.trim().orEmpty()
        if (text.isBlank()) {
            Log.w(TAG, "empty text extra")
            return
        }
        Log.d(TAG, "injected command: $text")
        val app = context.applicationContext as BuddyApplication
        AgentController.getInstance(app).onCommandReceived(text)
    }

    companion object {
        private const val TAG = "TestCommandReceiver"
        const val ACTION = "com.buddy.assistant.TEST_COMMAND"
    }
}
