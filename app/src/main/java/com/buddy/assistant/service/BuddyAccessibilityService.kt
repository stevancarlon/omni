package com.buddy.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.buddy.assistant.data.AgentAction
import com.buddy.assistant.data.Rect
import com.buddy.assistant.data.ScreenElement

class BuddyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We poll screen state when needed rather than reacting to events
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ─── Screen Perception ──────────────────────────────────────────────────

    fun getScreenElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        return try {
            buildElementTree(root)
        } finally {
            root.recycle()
        }
    }

    private fun buildElementTree(node: AccessibilityNodeInfo, depth: Int = 0): List<ScreenElement> {
        if (depth > 10) return emptyList()
        val elements = mutableListOf<ScreenElement>()
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val element = ScreenElement(
            id = node.viewIdResourceName ?: "node_${System.nanoTime()}",
            type = node.className?.toString()?.substringAfterLast('.') ?: "View",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            bounds = Rect(bounds.left, bounds.top, bounds.right, bounds.bottom),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isEnabled = node.isEnabled
        )
        elements.add(element)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                elements.addAll(buildElementTree(child, depth + 1))
                child.recycle()
            }
        }
        return elements
    }

    fun getCompactScreenDescription(): String {
        val elements = getScreenElements()
        val sb = StringBuilder()
        var index = 0
        for (element in elements) {
            val hasContent = !element.text.isNullOrBlank() || !element.contentDescription.isNullOrBlank()
            val isInteractive = element.isClickable || element.isEditable || element.isScrollable
            if (hasContent || isInteractive) {
                val label = element.text ?: element.contentDescription ?: ""
                val attrs = buildString {
                    if (element.isClickable) append("[clickable]")
                    if (element.isEditable) append("[editable]")
                    if (element.isScrollable) append("[scrollable]")
                    if (!element.isEnabled) append("[disabled]")
                }
                val bounds = element.bounds
                sb.appendLine("[$index] id=${element.id} type=${element.type} $attrs text=\"$label\" bounds=(${bounds?.left},${bounds?.top},${bounds?.right},${bounds?.bottom})")
                index++
            }
        }
        return sb.toString()
    }

    // ─── Action Execution ───────────────────────────────────────────────────

    fun executeAction(action: AgentAction): Boolean {
        return when (action) {
            is AgentAction.Tap -> executeTap(action)
            is AgentAction.TypeText -> executeType(action)
            is AgentAction.Swipe -> executeSwipe(action)
            is AgentAction.Scroll -> executeScroll(action)
            is AgentAction.PressBack -> performGlobalAction(GLOBAL_ACTION_BACK)
            is AgentAction.PressHome -> performGlobalAction(GLOBAL_ACTION_HOME)
            is AgentAction.PressRecents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            is AgentAction.OpenApp -> openApp(action.packageName)
            is AgentAction.OpenUrl -> openUrl(action.url)
            is AgentAction.Wait -> { Thread.sleep(action.ms); true }
            is AgentAction.Done -> true
        }
    }

    private fun executeTap(action: AgentAction.Tap): Boolean {
        // Try by node ID first
        if (action.nodeId != null) {
            val root = rootInActiveWindow ?: return false
            try {
                val node = findNodeById(root, action.nodeId)
                if (node != null) {
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    return result
                }
            } finally {
                root.recycle()
            }
        }
        // Fall back to coordinate tap
        val x = action.x?.toFloat() ?: return false
        val y = action.y?.toFloat() ?: return false
        return performTapGesture(x, y)
    }

    private fun performTapGesture(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                result = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                latch.countDown()
            }
        }, null)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun executeType(action: AgentAction.TypeText): Boolean {
        // Focus the node first if nodeId provided
        if (action.nodeId != null) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val node = findNodeById(root, action.nodeId)
                    node?.let {
                        it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Thread.sleep(200)
                        val args = Bundle()
                        args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                        val result = it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        it.recycle()
                        return result
                    }
                } finally {
                    root.recycle()
                }
            }
        }
        // Use currently focused node
        val root = rootInActiveWindow ?: return false
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val args = Bundle()
                args.putString(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text)
                val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                focused.recycle()
                return result
            }
        } finally {
            root.recycle()
        }
        return false
    }

    private fun executeSwipe(action: AgentAction.Swipe): Boolean {
        val display = resources.displayMetrics
        val w = display.widthPixels.toFloat()
        val h = display.heightPixels.toFloat()
        val cx = w / 2
        val cy = h / 2
        val (startX, startY, endX, endY) = when (action.direction.lowercase()) {
            "up" -> listOf(cx, cy + 400, cx, cy - 400)
            "down" -> listOf(cx, cy - 400, cx, cy + 400)
            "left" -> listOf(cx + 400, cy, cx - 400, cy)
            "right" -> listOf(cx - 400, cy, cx + 400, cy)
            else -> listOf(cx, cy + 400, cx, cy - 400)
        }
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { result = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription) { latch.countDown() }
        }, null)
        latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
        return result
    }

    private fun executeScroll(action: AgentAction.Scroll): Boolean {
        if (action.nodeId != null) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val node = findNodeById(root, action.nodeId)
                    if (node != null && node.isScrollable) {
                        val scrollAction = if (action.direction == "down")
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        val result = node.performAction(scrollAction)
                        node.recycle()
                        return result
                    }
                } finally {
                    root.recycle()
                }
            }
        }
        return executeSwipe(AgentAction.Swipe(action.direction))
    }

    private fun openApp(packageName: String): Boolean {
        Log.d(TAG, "openApp: trying package='$packageName'")
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName.trim())
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "openApp: launched '$packageName'")
                true
            } else {
                Log.w(TAG, "openApp: no launch intent for '$packageName'")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "openApp: failed for '$packageName'", e)
            false
        }
    }

    companion object {
        private const val TAG = "BuddyAccessibility"
        var instance: BuddyAccessibilityService? = null
    }

    private fun openUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: Exception) { false }
    }

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        if (root.viewIdResourceName == id) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findNodeById(child, id)
            if (found != null) {
                if (found != child) child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

}
