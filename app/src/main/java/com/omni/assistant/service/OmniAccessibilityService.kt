package com.omni.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.omni.assistant.data.ActionResult
import com.omni.assistant.data.AgentAction
import com.omni.assistant.data.Rect
import com.omni.assistant.data.ScreenElement
import com.omni.assistant.util.SensitiveAppGuard
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class AnnotatedScreen(
    val screenshotBase64: String,
    val legend: String,
)

class OmniAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        bankingProtectionActive.value = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isForegroundTransitionEvent(event.eventType)) return

        val pkg = resolveForegroundPackage(event) ?: return
        if (pkg == "com.android.systemui") return

        foregroundPackage.value = pkg
        val sensitive = SensitiveAppGuard.isSensitive(pkg)
        suspended.value = sensitive
        if (sensitive) {
            disableForSensitiveApp(pkg)
            return
        }

        if (SensitiveAppGuard.isLauncher(pkg) && launcherShowsSensitiveApp()) {
            disableForSensitiveApp("$pkg launcher target")
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    private fun disableForSensitiveApp(packageName: String) {
        if (bankingProtectionActive.value) return
        bankingProtectionActive.value = true
        Log.i(TAG, "Disabling Omni accessibility service while sensitive app is foreground: $packageName")
        disableForBankingMode()
    }

    private fun disableForBankingMode() {
        bankingProtectionActive.value = true
        Toast.makeText(
            this,
            "Omni disabled for banking app security. Re-enable it after banking.",
            Toast.LENGTH_LONG,
        ).show()
        disableSelf()
    }

    private fun isForegroundTransitionEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent): String? {
        val eventPackage = event.packageName?.toString()
        if (!eventPackage.isNullOrBlank()) return eventPackage

        val root = rootInActiveWindow ?: return null
        return try {
            root.packageName?.toString()
        } finally {
            root.recycle()
        }
    }

    private fun launcherShowsSensitiveApp(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            containsSensitiveLauncherLabel(root)
        } finally {
            root.recycle()
        }
    }

    private fun containsSensitiveLauncherLabel(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 8) return false

        val labels = sequenceOf(node.text, node.contentDescription)
            .mapNotNull { it?.toString() }
        if (labels.any { SensitiveAppGuard.isSensitiveLabel(it) }) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                containsSensitiveLauncherLabel(child, depth + 1)
            } finally {
                child.recycle()
            }
            if (found) return true
        }
        return false
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

    // ─── Action Validation & Execution ────────────────────────────────────

    fun executeAction(action: AgentAction): ActionResult {
        if (suspended.value) return ActionResult(false, "Blocked: sensitive app in foreground")

        // Pre-validate actions that target specific nodes
        val validation = validateAction(action)
        if (validation != null) return validation

        return when (action) {
            is AgentAction.Tap -> {
                val ok = executeTap(action)
                val target = action.nodeId ?: "(${action.x}, ${action.y})"
                if (ok) ActionResult(true, "Tapped $target")
                else ActionResult(false, "Tap failed on $target — element may have moved or is not interactable")
            }
            is AgentAction.TypeText -> {
                val ok = executeType(action)
                val target = action.nodeId ?: action.x?.let { "(${action.x}, ${action.y})" } ?: "focused field"
                if (ok) ActionResult(true, "Typed \"${action.text.take(50)}\" into $target")
                else ActionResult(false, "Type failed — no editable field at $target. Try tapping the input field first, then type.")
            }
            is AgentAction.Swipe -> {
                val ok = executeSwipe(action)
                if (ok) ActionResult(true, "Swiped ${action.direction}")
                else ActionResult(false, "Swipe ${action.direction} failed")
            }
            is AgentAction.Scroll -> {
                val ok = executeScroll(action)
                if (ok) ActionResult(true, "Scrolled ${action.direction}")
                else ActionResult(false, "Scroll failed — ${action.nodeId?.let { "node '$it' not scrollable" } ?: "no scrollable element found"}")
            }
            is AgentAction.PressBack -> {
                val ok = performGlobalAction(GLOBAL_ACTION_BACK)
                ActionResult(ok, if (ok) "Pressed back" else "Press back failed")
            }
            is AgentAction.PressHome -> {
                val ok = performGlobalAction(GLOBAL_ACTION_HOME)
                ActionResult(ok, if (ok) "Pressed home" else "Press home failed")
            }
            is AgentAction.PressRecents -> {
                val ok = performGlobalAction(GLOBAL_ACTION_RECENTS)
                ActionResult(ok, if (ok) "Opened recents" else "Open recents failed")
            }
            is AgentAction.OpenApp -> {
                val ok = openApp(action.packageName, action.name)
                val label = action.name ?: action.packageName
                if (ok) ActionResult(true, "Opened app: $label")
                else ActionResult(false, "Failed to open '$label' — app not found or not installed")
            }
            is AgentAction.OpenUrl -> {
                val ok = openUrl(action.url)
                if (ok) ActionResult(true, "Opened URL: ${action.url.take(60)}")
                else ActionResult(false, "Failed to open URL: ${action.url.take(60)}")
            }
            is AgentAction.Wait -> {
                Thread.sleep(action.ms)
                ActionResult(true, "Waited ${action.ms}ms")
            }
            is AgentAction.Done -> ActionResult(true, "Done")
        }
    }

    private fun validateAction(action: AgentAction): ActionResult? {
        val root = rootInActiveWindow ?: return null
        try {
            when (action) {
                is AgentAction.Tap -> {
                    if (action.nodeId != null) {
                        val node = findNodeById(root, action.nodeId)
                        if (node == null) {
                            // Check if it's a synthetic bounds ID we can still tap
                            if (parseBoundsId(action.nodeId) == null) {
                                return ActionResult(false, "Node '${action.nodeId}' not found on screen. Check the current screen state for valid node IDs.")
                            }
                        } else {
                            if (!node.isEnabled) {
                                node.recycle()
                                return ActionResult(false, "Node '${action.nodeId}' exists but is disabled.")
                            }
                            node.recycle()
                        }
                    }
                }
                is AgentAction.TypeText -> {
                    if (action.nodeId != null) {
                        val node = findNodeById(root, action.nodeId)
                        if (node == null) {
                            return ActionResult(false, "Node '${action.nodeId}' not found. Cannot type into non-existent field.")
                        }
                        if (!node.isEditable) {
                            val label = node.text?.toString()?.take(30) ?: node.className?.toString() ?: ""
                            node.recycle()
                            return ActionResult(false, "Node '${action.nodeId}' ($label) is not editable. Look for an EditText or input field.")
                        }
                        node.recycle()
                    }
                }
                is AgentAction.Scroll -> {
                    if (action.nodeId != null) {
                        val node = findNodeById(root, action.nodeId)
                        if (node == null) {
                            return ActionResult(false, "Scroll target '${action.nodeId}' not found on screen.")
                        }
                        if (!node.isScrollable) {
                            node.recycle()
                            return ActionResult(false, "Node '${action.nodeId}' is not scrollable. Use swipe instead or find a scrollable container.")
                        }
                        node.recycle()
                    }
                }
                else -> {}
            }
        } finally {
            root.recycle()
        }
        return null // validation passed
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
        // If x,y coordinates provided, tap there first to focus the field
        if (action.x != null && action.y != null) {
            performTapGesture(action.x.toFloat(), action.y.toFloat())
            Thread.sleep(300)
        }
        // Type into currently focused node
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

    // ─── Screenshot Capture ───────────────────────────────────────────────

    fun captureScreenBase64(): String? {
        if (suspended.value) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        var result: String? = null
        val latch = CountDownLatch(1)

        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        ) ?: run {
                            screenshot.hardwareBuffer.close()
                            latch.countDown()
                            return
                        }
                        // Scale down to reduce payload size — 720px wide is enough for the LLM
                        val scale = 720f / bitmap.width.coerceAtLeast(1)
                        val scaled = if (scale < 1f) {
                            Bitmap.createScaledBitmap(
                                bitmap,
                                (bitmap.width * scale).toInt(),
                                (bitmap.height * scale).toInt(),
                                true
                            )
                        } else bitmap

                        val stream = ByteArrayOutputStream()
                        // Copy to software bitmap if needed (hardware bitmaps can't compress directly)
                        val swBitmap = scaled.copy(Bitmap.Config.ARGB_8888, false)
                        swBitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                        result = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                        if (swBitmap != scaled) swBitmap.recycle()
                        if (scaled != bitmap) scaled.recycle()
                        bitmap.recycle()
                        screenshot.hardwareBuffer.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot encode failed: ${e.message}")
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Screenshot failed: errorCode=$errorCode")
                    latch.countDown()
                }
            }
        )

        latch.await(3, TimeUnit.SECONDS)
        return result
    }

    // ─── Annotated Screenshot (Set-of-Marks) ──────────────────────────────

    fun captureAnnotatedScreen(): AnnotatedScreen? {
        if (suspended.value) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        // Get interactive elements, deduplicate overlapping ones
        val elements = getScreenElements()
        val raw = elements.filter { el ->
            val hasContent = !el.text.isNullOrBlank() || !el.contentDescription.isNullOrBlank()
            val isInteractive = el.isClickable || el.isEditable || el.isScrollable
            (hasContent || isInteractive) && el.bounds != null &&
                el.bounds!!.let { b -> b.right > b.left && b.bottom > b.top } // skip zero-size
        }
        // Prioritize likely task-progress controls before capping. Accessibility
        // traversal order often lists containers before the bottom CTA.
        val ranked = raw.sortedWith(
            compareByDescending<ScreenElement> { elementPriority(it) }
                .thenBy { it.bounds?.top ?: Int.MAX_VALUE }
                .thenBy { it.bounds?.left ?: Int.MAX_VALUE }
        )

        // Deduplicate: skip marks too close to an existing one (within 30px)
        val interactive = mutableListOf<ScreenElement>()
        val usedCenters = mutableListOf<Pair<Int, Int>>()
        for (el in ranked) {
            val b = el.bounds!!
            val cx = b.centerX; val cy = b.centerY
            val tooClose = usedCenters.any { (ox, oy) ->
                kotlin.math.abs(cx - ox) < 30 && kotlin.math.abs(cy - oy) < 30
            }
            if (!tooClose) {
                interactive.add(el)
                usedCenters.add(cx to cy)
            }
        }
        // Cap at 40 marks max to keep the legend readable
        val capped = interactive.take(40)

        // Capture screenshot
        var rawBitmap: Bitmap? = null
        val latch = CountDownLatch(1)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            rawBitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer, screenshot.colorSpace
                            )
                            screenshot.hardwareBuffer.close()
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot decode failed: ${e.message}")
                        }
                        latch.countDown()
                    }
                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Screenshot failed: $errorCode")
                        latch.countDown()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot exception: ${e.message}")
            return null
        }

        latch.await(3, TimeUnit.SECONDS)
        val captured = rawBitmap ?: return null

        // Convert to mutable software bitmap
        val sw = captured.copy(Bitmap.Config.ARGB_8888, true)
        captured.recycle()

        // Scale down to 720px wide
        val scale = 720f / sw.width.coerceAtLeast(1)
        val scaled = if (scale < 1f) {
            val s = Bitmap.createScaledBitmap(sw, (sw.width * scale).toInt(), (sw.height * scale).toInt(), true)
            sw.recycle()
            s
        } else sw

        // Draw numbered marks
        val canvas = Canvas(scaled)
        val bgPaint = Paint().apply {
            color = Color.rgb(220, 50, 50)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val outlinePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 16f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }

        val legend = StringBuilder()
        if (capped.isEmpty()) {
            legend.appendLine("(No interactive elements detected — screen may be loading. Try wait or press_back.)")
        }
        capped.forEachIndexed { index, el ->
            val b = el.bounds ?: return@forEachIndexed
            val markNum = index + 1
            val cx = b.centerX * scale
            val cy = b.centerY * scale

            // Draw mark on screenshot
            val radius = if (markNum < 10) 12f else 16f
            canvas.drawCircle(cx, cy, radius, bgPaint)
            canvas.drawCircle(cx, cy, radius, outlinePaint)
            canvas.drawText("$markNum", cx, cy + 5.5f, textPaint)

            // Build legend entry with ORIGINAL coordinates
            val label = (el.text ?: el.contentDescription ?: "").take(40)
            val attrs = buildString {
                if (el.isClickable) append("clickable")
                if (el.isEditable) { if (isNotEmpty()) append(","); append("editable") }
                if (el.isScrollable) { if (isNotEmpty()) append(","); append("scrollable") }
                if (!el.isEnabled) { if (isNotEmpty()) append(","); append("disabled") }
                val role = actionRole(el)
                if (role != null) { if (isNotEmpty()) append(","); append(role) }
            }
            legend.appendLine("[$markNum] \"$label\" ($attrs) at x=${b.centerX}, y=${b.centerY}")
        }

        // Encode to base64 JPEG
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, stream)
        val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        scaled.recycle()

        return AnnotatedScreen(base64, legend.toString())
    }

    private fun elementPriority(element: ScreenElement): Int {
        val label = elementLabel(element).lowercase()
        var score = 0
        if (element.isClickable) score += 20
        if (element.isEditable) score += 18
        if (!element.isEnabled) score -= 60
        if (isPrimaryActionLabel(label)) score += 80
        if (isSecondaryOptionLabel(label)) score -= 25
        val bounds = element.bounds
        if (bounds != null) {
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            if (width >= 120 && height in 32..180) score += 8
            if (bounds.top > resources.displayMetrics.heightPixels * 0.55f) score += 6
        }
        return score
    }

    private fun actionRole(element: ScreenElement): String? {
        val label = elementLabel(element).lowercase()
        return when {
            isPrimaryActionLabel(label) -> "primary_candidate"
            isSecondaryOptionLabel(label) -> "secondary_option"
            else -> null
        }
    }

    private fun elementLabel(element: ScreenElement): String {
        return element.text?.takeIf { it.isNotBlank() }
            ?: element.contentDescription?.takeIf { it.isNotBlank() }
            ?: ""
    }

    private fun isPrimaryActionLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val normalized = normalizeActionLabel(label)
        val primaryWords = listOf(
            "start", "go", "continue", "next", "done", "send", "confirm", "save",
            "order", "book", "play", "call", "navigate", "open", "join", "pay",
            "begin", "submit", "finish", "route", "directions", "ok", "yes",
            "iniciar", "comecar", "começar", "ir", "continuar", "proximo", "próximo",
            "concluir", "enviar", "confirmar", "salvar", "pedir", "reservar",
            "reproduzir", "ligar", "navegar", "abrir", "entrar", "pagar",
        )
        return primaryWords.any { normalized == it || normalized.contains(" $it ") || normalized.startsWith("$it ") || normalized.endsWith(" $it") }
    }

    private fun isSecondaryOptionLabel(label: String): Boolean {
        if (label.isBlank()) return false
        val normalized = normalizeActionLabel(label)
        val secondaryWords = listOf(
            "more", "options", "filter", "filters", "sort", "details", "share",
            "settings", "menu", "overflow", "category", "categories", "suggestions",
            "opcoes", "opções", "filtro", "filtros", "ordenar", "detalhes",
            "compartilhar", "configuracoes", "configurações", "menu", "categorias",
            "sugestoes", "sugestões",
        )
        return secondaryWords.any { normalized == it || normalized.contains(" $it ") || normalized.startsWith("$it ") || normalized.endsWith(" $it") }
    }

    private fun normalizeActionLabel(label: String): String {
        return label.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
            .let { " $it " }
    }

    companion object {
        private const val TAG = "OmniAccessibility"
        var instance: OmniAccessibilityService? = null
        val foregroundPackage = MutableStateFlow("")
        val suspended = MutableStateFlow(false)
        val bankingProtectionActive = MutableStateFlow(false)
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
