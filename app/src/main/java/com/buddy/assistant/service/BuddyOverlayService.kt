package com.buddy.assistant.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            ACTION_SHOW -> showOverlay()
            ACTION_HIDE -> hideOverlay()
        }
        return START_NOT_STICKY
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
    }

    companion object {
        const val ACTION_SHOW = "com.buddy.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.buddy.OVERLAY_HIDE"
    }
}

@Composable
private fun OverlayContent(
    agentController: AgentController,
    onDismiss: () -> Unit
) {
    val status by agentController.status.collectAsState()

    val statusColor by animateColorAsState(
        targetValue = when (status) {
            is AgentStatus.VoiceListening -> Color(0xFF6C63FF)
            is AgentStatus.Processing, is AgentStatus.Executing -> Color(0xFF3ECFCF)
            is AgentStatus.Done -> if ((status as AgentStatus.Done).success) Color(0xFF6BCB77) else Color(0xFFFF6B6B)
            is AgentStatus.Error -> Color(0xFFFF6B6B)
            else -> Color(0xFF6C63FF)
        },
        label = "statusColor"
    )

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val isActive = status is AgentStatus.VoiceListening || status is AgentStatus.Processing || status is AgentStatus.Executing

    val statusText = when (status) {
        is AgentStatus.VoiceListening -> "Listening..."
        is AgentStatus.Processing -> "Thinking..."
        is AgentStatus.Executing -> {
            val s = status as AgentStatus.Executing
            "Step ${s.step}/${s.maxSteps}"
        }
        is AgentStatus.Done -> {
            val d = status as AgentStatus.Done
            if (d.success) "Done!" else d.reason
        }
        is AgentStatus.Error -> "Error"
        else -> "Buddy"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF0D0D2B), Color(0xFF1A1A3E))
                    )
                )
                .clickable { onDismiss() }
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Pulsing dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .scale(if (isActive) scale else 1f)
                    .clip(CircleShape)
                    .background(statusColor)
            )

            Text(
                text = statusText,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f, fill = false)
            )

            Text(
                text = "✕",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 16.sp
            )
        }
    }
}
