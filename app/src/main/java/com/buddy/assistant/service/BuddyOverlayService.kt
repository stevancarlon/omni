package com.buddy.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.buddy.assistant.R
import com.buddy.assistant.ui.MainActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buddy.assistant.ui.components.BuddyOrb
import com.buddy.assistant.ui.theme.BuddyColors
import com.buddy.assistant.ui.theme.BuddyGradients
import com.buddy.assistant.ui.theme.BuddyShapes
import com.buddy.assistant.ui.theme.dockPillStyle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.buddy.assistant.BuddyApplication
import com.buddy.assistant.agent.AgentController
import com.buddy.assistant.data.AgentStatus
import kotlinx.coroutines.*

class BuddyOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private lateinit var agentController: AgentController
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        agentController = AgentController.getInstance(application as BuddyApplication)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startAsForeground()
                showOverlay()
            }
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, BuddyApplication.CHANNEL_AGENT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Omni")
            .setContentText("Working\u2026")
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundAndSelf() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        hideOverlay()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        super.onDestroy()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w("BuddyOverlay", "Overlay permission not granted, skipping")
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@BuddyOverlayService)
            setViewTreeSavedStateRegistryOwner(this@BuddyOverlayService)
            setContent {
                OverlayContent(agentController) {
                    hideOverlay()
                }
            }
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager?.addView(composeView, params)
        overlayView = composeView

        // Auto-hide when task completes
        scope.launch {
            agentController.status.collect { status ->
                if (status is AgentStatus.Done || status is AgentStatus.Error) {
                    delay(3000)
                    hideOverlay()
                }
            }
        }
    }

    private fun hideOverlay() {
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        stopForegroundAndSelf()
    }

    companion object {
        const val ACTION_SHOW = "com.buddy.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.buddy.OVERLAY_HIDE"
        private const val NOTIFICATION_ID = 4242
    }
}

@Composable
private fun OverlayContent(
    agentController: AgentController,
    onDismiss: () -> Unit
) {
    val status by agentController.status.collectAsState()

    val statusText = when (status) {
        is AgentStatus.VoiceListening -> "Listening\u2026"
        is AgentStatus.Processing -> "Thinking\u2026"
        is AgentStatus.Executing -> {
            val s = status as AgentStatus.Executing
            "Step ${s.step}/${s.maxSteps}"
        }
        is AgentStatus.Done -> {
            val d = status as AgentStatus.Done
            if (d.success) "Done!" else d.reason
        }
        is AgentStatus.Error -> "Error"
        else -> "Omni"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .dockPillStyle()
                .clickable { onDismiss() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Mini orb — renders the live shader-style orb
            BuddyOrb(
                status = status,
                modifier = Modifier.size(40.dp)
            )

            // Status pill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(BuddyShapes.Pill)
                    .background(BuddyColors.Surface.copy(alpha = 0.4f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = BuddyShapes.Pill,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = statusText,
                    style = TextStyle(
                        brush = BuddyGradients.SilverText,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    ),
                    maxLines = 1,
                )
            }

            // Stop button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BuddyColors.Surface.copy(alpha = 0.5f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .clickable {
                        agentController.reset()
                        onDismiss()
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Stop icon — small rounded square
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(BuddyColors.InkDim)
                )
            }
        }
    }
}
