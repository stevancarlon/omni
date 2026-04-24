package com.buddy.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.buddy.assistant.data.SettingsRepository

class BuddyApplication : Application() {

    lateinit var settingsRepository: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        settingsRepository = SettingsRepository(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val listeningChannel = NotificationChannel(
            CHANNEL_LISTENING,
            "Omni Listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown when Omni is actively listening"
            setShowBadge(false)
        }

        val agentChannel = NotificationChannel(
            CHANNEL_AGENT,
            "Omni Agent",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Shown when Omni is executing a task"
        }

        manager.createNotificationChannel(listeningChannel)
        manager.createNotificationChannel(agentChannel)
    }

    companion object {
        lateinit var instance: BuddyApplication
            private set
        const val CHANNEL_LISTENING = "buddy_listening"
        const val CHANNEL_AGENT = "buddy_agent"
    }
}
