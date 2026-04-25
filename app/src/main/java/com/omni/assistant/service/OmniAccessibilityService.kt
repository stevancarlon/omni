package com.omni.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.omni.assistant.data.AgentAction
import com.omni.assistant.data.Rect
import com.omni.assistant.data.ScreenElement
import com.omni.assistant.util.SensitiveAppGuard
import kotlinx.coroutines.flow.MutableStateFlow

class OmniAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == "com.android.systemui") return
            foregroundPackage.value = pkg
            suspended.value = SensitiveAppGuard.isSensitive(pkg)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    // ─── Screen Perception ──────────────────────────────────────────────────

    fun getScreenElements(): List<ScreenElement> {
        if (suspended.value) return emptyList()
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
            id = node.viewIdResourceName ?: "n_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}",
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
        if (suspended.value) return "[suspended - sensitive app in foreground]"
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
        if (suspended.value) return false
        return when (action) {
            is AgentAction.Tap -> executeTap(action)
            is AgentAction.TypeText -> executeType(action)
            is AgentAction.Swipe -> executeSwipe(action)
            is AgentAction.Scroll -> executeScroll(action)
            is AgentAction.PressBack -> performGlobalAction(GLOBAL_ACTION_BACK)
            is AgentAction.PressHome -> performGlobalAction(GLOBAL_ACTION_HOME)
            is AgentAction.PressRecents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            is AgentAction.OpenApp -> openApp(action.packageName, action.name)
            is AgentAction.OpenUrl -> openUrl(action.url)
            is AgentAction.Wait -> { Thread.sleep(action.ms); true }
            is AgentAction.Done -> true
        }
    }

    private fun executeTap(action: AgentAction.Tap): Boolean {
        if (action.nodeId != null) {
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    val node = findNodeById(root, action.nodeId)
                    if (node != null) {
                        val clickable = nearestClickable(node)
                        val target = clickable ?: node
                        val clickResult = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clickResult) {
                            if (clickable != null && clickable != node) clickable.recycle()
                            node.recycle()
                            return true
                        }
                        // Node found but click didn't stick — tap its bounds center
                        val b = android.graphics.Rect()
                        target.getBoundsInScreen(b)
                        if (clickable != null && clickable != node) clickable.recycle()
                        node.recycle()
                        if (b.width() > 0 && b.height() > 0) {
                            return performTapGesture(b.exactCenterX(), b.exactCenterY())
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
            // Node not found — try to parse bounds from our synthetic ID
            parseBoundsId(action.nodeId)?.let { (l, t, r, b) ->
                Log.d(TAG, "tap: node '${action.nodeId}' not found, tapping bounds center")
                return performTapGesture((l + r) / 2f, (t + b) / 2f)
            }
        }
        val x = action.x?.toFloat() ?: return false
        val y = action.y?.toFloat() ?: return false
        return performTapGesture(x, y)
    }

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) return current
            val next = current.parent
            current.recycle()
            current = next
            depth++
        }
        current?.recycle()
        return null
    }

    private fun parseBoundsId(id: String): IntArray? {
        // Matches "n_<l>_<t>_<r>_<b>"
        val m = Regex("^n_(-?\\d+)_(-?\\d+)_(-?\\d+)_(-?\\d+)$").matchEntire(id) ?: return null
        return intArrayOf(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].toInt()
        )
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

    private fun openApp(packageName: String, name: String? = null): Boolean {
        Log.d(TAG, "openApp: package='$packageName' name='$name'")
        val pkg = packageName.trim()
        val direct = tryLaunch(pkg)
        if (direct) return true

        // Fallback: fuzzy-match against installed launchable apps.
        val hint = listOfNotNull(name?.trim()?.takeIf { it.isNotBlank() }, pkg.takeIf { it.isNotBlank() })
            .joinToString(" ")
        val resolved = resolveLaunchablePackage(hint)
        if (resolved != null && resolved != pkg) {
            Log.d(TAG, "openApp: resolved '$hint' -> '$resolved'")
            return tryLaunch(resolved)
        }
        return false
    }

    private fun tryLaunch(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
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

    private fun resolveLaunchablePackage(query: String): String? {
        val q = query.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        if (q.isEmpty()) return null
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launchIntent, android.content.pm.PackageManager.MATCH_ALL)

        data class Candidate(val pkg: String, val score: Double)
        val scored = apps.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            val label = ri.activityInfo.loadLabel(packageManager).toString().lowercase()
            val pkgNorm = pkg.lowercase()
            val s = maxOf(
                similarity(q, label),
                similarity(q, pkgNorm.substringAfterLast('.')),
                if (label.contains(q) || q.contains(label)) 0.9 else 0.0,
                if (pkgNorm.contains(q.replace(" ", ""))) 0.85 else 0.0
            )
            Candidate(pkg, s)
        }.sortedByDescending { it.score }

        val best = scored.firstOrNull() ?: return null
        Log.d(TAG, "openApp: best match for '$q' = ${best.pkg} (score=${"%.2f".format(best.score)})")
        return if (best.score >= 0.55) best.pkg else null
    }

    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            )
        }
        return 1.0 - dp[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }

    companion object {
        private const val TAG = "OmniAccessibility"
        var instance: OmniAccessibilityService? = null
        val foregroundPackage = MutableStateFlow("")
        val suspended = MutableStateFlow(false)
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
        if (id.startsWith("n_")) {
            val b = android.graphics.Rect()
            root.getBoundsInScreen(b)
            if ("n_${b.left}_${b.top}_${b.right}_${b.bottom}" == id) return root
        }
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
