package com.omni.assistant.service

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
import com.omni.assistant.R
import com.omni.assistant.ui.MainActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import com.omni.assistant.ui.components.OmniOrb
import com.omni.assistant.ui.theme.OmniColors
import com.omni.assistant.ui.theme.OmniGradients
import com.omni.assistant.ui.theme.OmniShapes
import com.omni.assistant.ui.theme.dockPillStyle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.omni.assistant.OmniApplication
import com.omni.assistant.agent.AgentController
import com.omni.assistant.data.AgentStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.omni.assistant.ui.components.OmniOrbPerformance

class OmniOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var isRequested = false
    private var removeJob: Job? = null
    private var terminalHideJob: Job? = null
    private lateinit var agentController: AgentController
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Drives the slide+fade in Compose. We mount the view, then flip this flag
    // to true on the next frame so AnimatedVisibility actually animates in.
    private val _visible = MutableStateFlow(false)
    private val visible: StateFlow<Boolean> = _visible.asStateFlow()

    // Only mount the overlay view when the whole app is in the background.
    // While the user is inside Omni's own UI, the main screen already reflects
    // the agent state natively, so the floating pill would just get in the way.
    private val foregroundObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            // App went foreground — hide the overlay view (keep service alive),
            // but only if the agent isn't actively working. During execution the
            // user may briefly pass through Omni while navigating between apps;
            // removing the overlay here would kill the executing/done indicator.
            val status = agentController.status.value
            val agentBusy = status is AgentStatus.Processing ||
                status is AgentStatus.Executing ||
                status is AgentStatus.Done ||
                status is AgentStatus.Error
            if (!agentBusy) {
                removeOverlayView()
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            // App went background — show the overlay if a task is in flight.
            if (isRequested && !OmniAccessibilityService.suspended.value) addOverlayView()
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        agentController = AgentController.getInstance(application as OmniApplication)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundObserver)
        observeTaskCompletion()
        scope.launch {
            OmniAccessibilityService.suspended.collect { isSuspended ->
                if (isSuspended) {
                    removeOverlayView()
                } else if (isRequested) {
                    val state = ProcessLifecycleOwner.get().lifecycle.currentState
                    if (!state.isAtLeast(Lifecycle.State.STARTED)) {
                        addOverlayView()
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startAsForeground()
                isRequested = true
                terminalHideJob?.cancel()
                terminalHideJob = null
                // Only attach the view right now if the app is backgrounded;
                // otherwise we'll attach from the lifecycle observer once it is.
                val state = ProcessLifecycleOwner.get().lifecycle.currentState
                if (!state.isAtLeast(Lifecycle.State.STARTED)) {
                    addOverlayView()
                }
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
        val notification: Notification = NotificationCompat.Builder(this, OmniApplication.CHANNEL_AGENT)
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
        // We're being torn down — no animation is going to finish. Just drop
        // the view synchronously and clean up.
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        removeJob?.cancel()
        removeJob = null
        terminalHideJob?.cancel()
        terminalHideJob = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(foregroundObserver)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        scope.cancel()
        super.onDestroy()
    }

    private fun addOverlayView() {
        if (OmniAccessibilityService.suspended.value) return

        // Cancel any pending remove: user re-backgrounded before the exit
        // animation finished, so reuse the existing view and animate back in.
        removeJob?.cancel()
        removeJob = null

        if (overlayView != null) {
            _visible.value = true
            return
        }
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w("OmniOverlay", "Overlay permission not granted, skipping")
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

        _visible.value = false
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OmniOverlayService)
            setViewTreeSavedStateRegistryOwner(this@OmniOverlayService)
            setContent {
                OverlayContent(
                    agentController = agentController,
                    visibleFlow = visible,
                    onDismiss = { hideOverlay() },
                    onCancel = { cancelAgentAndListener() },
                )
            }
        }

        if (lifecycleRegistry.currentState != Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        windowManager?.addView(composeView, params)
        overlayView = composeView

        // Flip to visible on the next frame so AnimatedVisibility sees the
        // transition from false → true and runs the enter animation.
        scope.launch {
            delay(16)
            _visible.value = true
        }
    }

    private fun removeOverlayView(thenStop: Boolean = false) {
        val view = overlayView
        if (view == null) {
            if (thenStop) stopForegroundAndSelf()
            return
        }
        if (removeJob?.isActive == true) {
            // An exit is already in flight. If we now want to stop after, just
            // chain that onto the existing job.
            if (thenStop) {
                val existing = removeJob
                removeJob = scope.launch {
                    existing?.join()
                    stopForegroundAndSelf()
                }
            }
            return
        }
        _visible.value = false
        removeJob = scope.launch {
            // Must outlast the exit animation in OverlayContent (~220ms).
            delay(OVERLAY_EXIT_DURATION_MS + 40L)
            try { windowManager?.removeView(view) } catch (_: Exception) {}
            if (overlayView === view) overlayView = null
            removeJob = null
            if (thenStop) stopForegroundAndSelf()
        }
    }

    private fun observeTaskCompletion() {
        scope.launch {
            agentController.status.collect { status ->
                if (status is AgentStatus.Done || status is AgentStatus.Error) {
                    terminalHideJob?.cancel()
                    terminalHideJob = scope.launch {
                        delay(TERMINAL_HIDE_DELAY_MS)
                        val current = agentController.status.value
                        if (current is AgentStatus.Done || current is AgentStatus.Error) {
                            hideOverlay()
                        }
                    }
                } else {
                    terminalHideJob?.cancel()
                    terminalHideJob = null
                }
            }
        }
    }

    private fun hideOverlay() {
        terminalHideJob?.cancel()
        terminalHideJob = null
        isRequested = false
        // Play the exit animation, then tear down. stopForegroundAndSelf runs
        // onDestroy which cancels the scope, so we must defer it until after
        // the animation frame has rendered.
        removeOverlayView(thenStop = true)
    }

    private fun cancelAgentAndListener() {
        agentController.reset()
        try {
            startService(Intent(this, OmniListenerService::class.java).apply {
                action = OmniListenerService.ACTION_STOP_COMMAND
            })
        } catch (_: Exception) {}
        hideOverlay()
    }

    companion object {
        const val ACTION_SHOW = "com.omni.OVERLAY_SHOW"
        const val ACTION_HIDE = "com.omni.OVERLAY_HIDE"
        private const val NOTIFICATION_ID = 4242
        private const val OVERLAY_ENTER_DURATION_MS = 280
        private const val OVERLAY_EXIT_DURATION_MS = 220
        private const val TERMINAL_HIDE_DELAY_MS = 3000L
    }
}

@Composable
private fun OverlayContent(
    agentController: AgentController,
    visibleFlow: StateFlow<Boolean>,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {
    val status by agentController.status.collectAsState()
    val visible by visibleFlow.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = tween(durationMillis = 280),
            initialOffsetY = { it },
        ) + fadeIn(animationSpec = tween(durationMillis = 220)),
        exit = slideOutVertically(
            animationSpec = tween(durationMillis = 220),
            targetOffsetY = { it },
        ) + fadeOut(animationSpec = tween(durationMillis = 180)),
    ) {
    OverlayPill(status, onDismiss, onCancel)
    }
}

@Composable
private fun OverlayPill(
    status: AgentStatus,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
) {

    val statusText = when (status) {
        is AgentStatus.VoiceListening -> "Listening\u2026"
        is AgentStatus.Processing -> "Thinking\u2026"
        is AgentStatus.Executing -> "Step ${status.step}/${status.maxSteps}"
        is AgentStatus.Done -> if (status.success) "Done!" else status.reason
        is AgentStatus.Error -> "Error"
        else -> "Omni"
    }
    // Figma overlay variants (257:4..257:42): Listening shows orb + status only
    // (no Cancel). Thinking / Executing show orb + status + Cancel.
    val showCancel = status is AgentStatus.Processing || status is AgentStatus.Executing

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
            OmniOrb(
                status = status,
                modifier = Modifier.size(40.dp),
                performance = OmniOrbPerformance.Static,
            )

            // Status pill
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(OmniShapes.Pill)
                    .background(OmniColors.Surface.copy(alpha = 0.4f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = OmniShapes.Pill,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = statusText,
                    style = TextStyle(
                        brush = OmniGradients.SilverText,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    ),
                    maxLines = 1,
                )
            }

            // Cancel pill — text per Figma anatomy (256:11), only for active work
            if (showCancel) {
                Box(
                    modifier = Modifier
                        .clip(OmniShapes.Pill)
                        .background(OmniColors.Surface.copy(alpha = 0.5f))
                        .border(
                            width = 1.dp,
                            color = Color.White.copy(alpha = 0.08f),
                            shape = OmniShapes.Pill,
                        )
                        .clickable {
                            onCancel()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Cancel",
                        style = TextStyle(
                            brush = OmniGradients.SilverText,
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
