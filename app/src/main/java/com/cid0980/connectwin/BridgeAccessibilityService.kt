package com.cid0980.connectwin

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: BridgeAccessibilityService? = null
            private set

        @Volatile
        var currentPackage: String = ""
            private set

        @Volatile
        var currentActivity: String = ""
            private set
    }

    private val shotExecutor = Executors.newSingleThreadExecutor()

    data class ParsedNode(
        val ref: Int,
        val text: String,
        val desc: String,
        val id: String,
        val fullId: String,
        val cls: String,
        val pkg: String,
        val clickable: Boolean,
        val longClickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val focused: Boolean,
        val enabled: Boolean,
        val bounds: Rect,
        val rawNode: AccessibilityNodeInfo
    ) {
        val cx: Int get() = bounds.centerX()
        val cy: Int get() = bounds.centerY()

        fun toJson(): JSONObject = JSONObject().apply {
            put("ref", ref)
            put("text", text)
            put("desc", desc)
            put("id", id)
            put("class", cls)
            put("pkg", pkg)
            put("clickable", clickable)
            put("editable", editable)
            put("scrollable", scrollable)
            if (checkable) put("checked", checked)
            if (focused) put("focused", true)
            put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
            put("center", JSONArray(listOf(cx, cy)))
        }
    }

    @Volatile
    private var lastNodes: List<ParsedNode> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        BridgeState.log("AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""
            val cls = event.className?.toString() ?: ""
            if (pkg.isNotBlank() && pkg != "com.android.systemui") {
                currentPackage = pkg
                if (cls.isNotBlank()) currentActivity = cls
            } else if (pkg.isNotBlank() && currentPackage.isBlank()) {
                currentPackage = pkg
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        BridgeState.log("AccessibilityService disconnected")
        super.onDestroy()
    }

    fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    // ==================================================================
    // UI DOM SNAPSHOT & ELEMENT RESOLUTION
    // ==================================================================
    fun collectNodes(includeAll: Boolean = false): List<ParsedNode> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching {
            val winList = windows
            if (!winList.isNullOrEmpty()) {
                // Sort active/application windows first, system overlays included
                val sorted = winList.sortedByDescending {
                    (if (it.isActive) 10 else 0) +
                        (if (it.type == AccessibilityWindowInfo.TYPE_APPLICATION) 5 else 0) +
                        it.layer
                }
                for (w in sorted) {
                    w.root?.let { roots.add(it) }
                }
            }
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }

        val out = mutableListOf<ParsedNode>()
        var nextRef = 1
        val (sw, sh) = screenSize()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 60) return
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val validBounds = rect.width() > 2 && rect.height() > 2 &&
                    rect.right > 0 && rect.bottom > 0 && rect.left < sw && rect.top < sh
                if (validBounds) {
                    val text = node.text?.toString()?.trim() ?: ""
                    val desc = node.contentDescription?.toString()?.trim() ?: ""
                    val fullId = node.viewIdResourceName ?: ""
                    val shortId = fullId.substringAfterLast("/", fullId)
                    val clsName = node.className?.toString()?.substringAfterLast(".") ?: ""
                    val editable = node.isEditable || clsName.contains("EditText", ignoreCase = true)
                    val interesting = text.isNotEmpty() || desc.isNotEmpty() ||
                        node.isClickable || editable || node.isScrollable || node.isCheckable ||
                        (includeAll && shortId.isNotEmpty())
                    if (interesting) {
                        out.add(
                            ParsedNode(
                                ref = nextRef++,
                                text = text,
                                desc = desc,
                                id = shortId,
                                fullId = fullId,
                                cls = clsName,
                                pkg = node.packageName?.toString() ?: "",
                                clickable = node.isClickable,
                                longClickable = node.isLongClickable,
                                editable = editable,
                                scrollable = node.isScrollable,
                                checkable = node.isCheckable,
                                checked = node.isChecked,
                                focused = node.isFocused,
                                enabled = node.isEnabled,
                                bounds = rect,
                                rawNode = node
                            )
                        )
                    }
                }
            }
            val count = node.childCount
            for (i in 0 until count) {
                walk(node.getChild(i), depth + 1)
            }
        }

        for (r in roots) {
            walk(r, 0)
        }
        lastNodes = out
        return out
    }

    fun uiStateJson(): JSONObject {
        val nodes = collectNodes(false)
        val (w, h) = screenSize()
        val fgPkg = nodes.firstOrNull { it.pkg.isNotBlank() && it.pkg != "com.android.systemui" }?.pkg
            ?: currentPackage

        val texts = JSONArray()
        val buttons = JSONArray()
        val inputs = JSONArray()
        val seenBtn = HashSet<String>()
        val seenTxt = HashSet<String>()

        for (n in nodes) {
            val label = when {
                n.text.isNotEmpty() -> n.text
                n.desc.isNotEmpty() -> n.desc
                else -> n.id
            }.take(90)

            if (n.editable) {
                inputs.put(JSONObject().apply {
                    put("ref", n.ref)
                    put("label", label)
                    put("id", n.id)
                    put("focused", n.focused)
                    put("center", JSONArray(listOf(n.cx, n.cy)))
                })
            } else if (n.clickable) {
                val dedupKey = "$label@${n.cx / 24},${n.cy / 24}"
                if (label.isNotEmpty() && seenBtn.add(dedupKey) && buttons.length() < 45) {
                    buttons.put(JSONObject().apply {
                        put("ref", n.ref)
                        put("label", label)
                        if (n.id.isNotEmpty()) put("id", n.id)
                        put("center", JSONArray(listOf(n.cx, n.cy)))
                    })
                }
            } else if (n.text.isNotEmpty() && seenTxt.add(label) && texts.length() < 40) {
                texts.put(label)
            }
        }

        return JSONObject().apply {
            put("foreground_package", fgPkg)
            put("foreground_activity", currentActivity)
            put("screen", JSONArray(listOf(w, h)))
            put("total_nodes", nodes.size)
            put("texts", texts)
            put("buttons", buttons)
            put("inputs", inputs)
        }
    }

    fun uiTreeJson(includeAll: Boolean = false): JSONObject {
        val nodes = collectNodes(includeAll)
        val (w, h) = screenSize()
        val arr = JSONArray()
        nodes.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("screen", JSONArray(listOf(w, h)))
            put("count", nodes.size)
            put("nodes", arr)
        }
    }

    private fun matchNode(
        ref: Int? = null,
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        exact: Boolean = false,
        index: Int = 0,
        preferEditable: Boolean = false
    ): List<ParsedNode> {
        val pool = if (ref != null && lastNodes.isNotEmpty()) lastNodes else collectNodes(true)
        if (ref != null) {
            val hit = pool.filter { it.ref == ref }
            if (hit.isNotEmpty()) return hit
        }

        fun matchesStr(actual: String, query: String): Boolean {
            if (actual.isEmpty()) return false
            return if (exact) actual.equals(query, ignoreCase = true)
            else actual.contains(query, ignoreCase = true)
        }

        val fresh = if (pool === lastNodes) collectNodes(true) else pool
        val filtered = fresh.filter { n ->
            val okText = text.isNullOrEmpty() || matchesStr(n.text, text) || matchesStr(n.desc, text)
            val okDesc = desc.isNullOrEmpty() || matchesStr(n.desc, desc)
            val okId = resId.isNullOrEmpty() || matchesStr(n.id, resId) || matchesStr(n.fullId, resId)
            okText && okDesc && okId
        }
        if (preferEditable) {
            val eds = filtered.filter { it.editable }
            if (eds.isNotEmpty()) return eds
        }
        val clickables = filtered.filter { it.clickable }
        return if (clickables.isNotEmpty() && !preferEditable) clickables + (filtered - clickables.toSet())
        else filtered
    }

    private fun performClickableNodeOrParent(node: AccessibilityNodeInfo, longClick: Boolean): Boolean {
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK
        else AccessibilityNodeInfo.ACTION_CLICK

        var cur: AccessibilityNodeInfo? = node
        var hops = 0
        while (cur != null && hops < 6) {
            val canAct = if (longClick) cur.isLongClickable else cur.isClickable
            if (canAct && cur.performAction(action)) {
                return true
            }
            cur = cur.parent
            hops++
        }
        return false
    }

    fun uiClick(
        ref: Int? = null,
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        exact: Boolean = false,
        index: Int = 0,
        longClick: Boolean = false
    ): JSONObject {
        val matches = matchNode(ref = ref, text = text, desc = desc, resId = resId, exact = exact)
        if (matches.isEmpty()) {
            val visible = JSONArray()
            lastNodes.mapNotNull {
                it.text.ifEmpty { it.desc }.ifEmpty { null }
            }.distinct().take(25).forEach { visible.put(it) }
            return JSONObject().apply {
                put("ok", false)
                put("error", "not_found")
                put("visible_labels", visible)
            }
        }
        val chosen = matches[index.coerceIn(0, matches.size - 1)]
        val viaAction = performClickableNodeOrParent(chosen.rawNode, longClick)
        val viaGesture = if (!viaAction) {
            if (longClick) longPress(chosen.cx, chosen.cy, 550) else tap(chosen.cx, chosen.cy, 55)
        } else true

        return JSONObject().apply {
            put("ok", viaAction || viaGesture)
            put("method", if (viaAction) "accessibility_action" else "gesture_tap")
            put("clicked_center", JSONArray(listOf(chosen.cx, chosen.cy)))
            put("matched", chosen.toJson())
        }
    }

    fun uiSetText(
        value: String,
        ref: Int? = null,
        target: String? = null,
        resId: String? = null,
        append: Boolean = false,
        submit: Boolean = false
    ): JSONObject {
        var chosen: ParsedNode? = null
        if (ref != null || !target.isNullOrEmpty() || !resId.isNullOrEmpty()) {
            val matches = matchNode(ref = ref, text = target, resId = resId, preferEditable = true)
            chosen = matches.firstOrNull()
        }
        if (chosen == null) {
            val all = collectNodes(true)
            chosen = all.firstOrNull { it.editable && it.focused }
                ?: all.firstOrNull { it.editable }
        }

        if (chosen == null) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "no_editable_node_found")
            }
        }

        val raw = chosen.rawNode
        if (!raw.isFocused) {
            raw.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            raw.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val finalText = if (append && chosen.text.isNotEmpty()) chosen.text + value else value
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, finalText)
        }
        val setOk = raw.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        var submitted = false
        if (submit) {
            Thread.sleep(80)
            submitted = raw.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }

        return JSONObject().apply {
            put("ok", setOk)
            put("length", finalText.length)
            put("submitted", submitted)
            put("target", chosen.toJson())
        }
    }

    fun uiWait(
        text: String? = null,
        desc: String? = null,
        resId: String? = null,
        gone: Boolean = false,
        timeoutMs: Long = 6000L
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(200L, 25_000L)
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() < deadline) {
            val matches = matchNode(text = text, desc = desc, resId = resId)
            val satisfied = if (gone) matches.isEmpty() else matches.isNotEmpty()
            if (satisfied) {
                return JSONObject().apply {
                    put("ok", true)
                    put("waited_ms", System.currentTimeMillis() - t0)
                    if (!gone && matches.isNotEmpty()) {
                        put("matched", matches.first().toJson())
                    }
                    put("state", uiStateJson())
                }
            }
            Thread.sleep(150)
        }
        return JSONObject().apply {
            put("ok", false)
            put("timeout", true)
            put("waited_ms", System.currentTimeMillis() - t0)
            put("state", uiStateJson())
        }
    }

    // ==================================================================
    // GESTURE ENGINE (TAP, LONG-PRESS, SWIPE, PINCH, GLOBAL ACTIONS)
    // ==================================================================
    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long = 8000L): Boolean {
        val latch = CountDownLatch(1)
        var completed = false
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    latch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completed = false
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) return false
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return completed
    }

    // One bounded Android gesture can include overlapping taps and swipes.
    fun timedGesture(strokes: org.json.JSONArray): Boolean {
        require(strokes.length() in 1..10) { "Provide 1 to 10 strokes" }
        val builder = GestureDescription.Builder()
        for (i in 0 until strokes.length()) {
            val s = strokes.getJSONObject(i)
            val start = s.optLong("start_ms", 0)
            val duration = s.optLong("duration_ms", 60)
            require(start in 0..9950 && duration in 20..5000 && start + duration <= 10000) {
                "Stroke timing must fit within 10 seconds"
            }
            val x = s.getInt("x")
            val y = s.getInt("y")
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                if (s.has("x2") && s.has("y2")) lineTo(s.getInt("x2").toFloat(), s.getInt("y2").toFloat())
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, start, duration))
        }
        return dispatchGestureSync(builder.build(), 12000L)
    }

    fun tap(x: Int, y: Int, durationMs: Long = 55L): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(p, 0L, durationMs.coerceIn(20L, 2000L))
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 550L): Boolean =
        tap(x, y, durationMs.coerceIn(350L, 5000L))

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 280L): Boolean {
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(p, 0L, durationMs.coerceIn(40L, 8000L))
        return dispatchGestureSync(GestureDescription.Builder().addStroke(stroke).build())
    }

    fun pinch(cx: Int, cy: Int, startSpan: Int, endSpan: Int, durationMs: Long = 350L): Boolean {
        val half1 = (startSpan / 2).coerceAtLeast(10)
        val half2 = (endSpan / 2).coerceAtLeast(10)
        val p1 = Path().apply {
            moveTo((cx - half1).toFloat(), cy.toFloat())
            lineTo((cx - half2).toFloat(), cy.toFloat())
        }
        val p2 = Path().apply {
            moveTo((cx + half1).toFloat(), cy.toFloat())
            lineTo((cx + half2).toFloat(), cy.toFloat())
        }
        val dur = durationMs.coerceIn(100L, 4000L)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0L, dur))
            .addStroke(GestureDescription.StrokeDescription(p2, 0L, dur))
            .build()
        return dispatchGestureSync(g)
    }

    fun scrollScreen(dy: Int = -2): JSONObject {
        val (w, h) = screenSize()
        val cx = w / 2
        val (y1, y2) = if (dy < 0) {
            // Scroll down page -> finger swipes up
            Pair((h * 0.73).toInt(), (h * 0.27).toInt())
        } else {
            // Scroll up page -> finger swipes down
            Pair((h * 0.27).toInt(), (h * 0.73).toInt())
        }
        val steps = kotlin.math.abs(dy).coerceIn(1, 6)
        var ok = true
        repeat(steps) { idx ->
            ok = swipe(cx, y1, cx, y2, 260L) && ok
            if (idx + 1 < steps) Thread.sleep(140)
        }
        return JSONObject().apply {
            put("ok", ok)
            put("scrolled", dy)
            put("from_y", y1)
            put("to_y", y2)
        }
    }

    fun triggerGlobalAction(name: String): Boolean {
        val act = when (name.trim().lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents", "app_switch", "overview" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog", "power" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen", "lock", "sleep" -> GLOBAL_ACTION_LOCK_SCREEN
            "split_screen" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            "take_screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            "dismiss_shade" -> if (android.os.Build.VERSION.SDK_INT >= 31) 15 else GLOBAL_ACTION_BACK
            else -> return false
        }
        return performGlobalAction(act)
    }

    // ==================================================================
    // SCREENSHOT (ANDROID 11+ ZERO-POPUP + CROP / DOWNSCALE / JPEG)
    // ==================================================================
    data class CapturedShot(
        val bytes: ByteArray,
        val mime: String,
        val origWidth: Int,
        val origHeight: Int,
        val outWidth: Int,
        val outHeight: Int
    )

    fun captureScreenshot(
        region: String? = null,
        maxWidth: Int? = null,
        format: String = "jpg",
        quality: Int = 75
    ): CapturedShot {
        var bitmap: Bitmap? = null
        var lastError = -1

        // Android rate-limits takeScreenshot (~333ms cooldown -> errorCode 3). Retry smoothly.
        for (attempt in 1..4) {
            val latch = CountDownLatch(1)
            val bmpRef = AtomicReference<Bitmap?>(null)
            var errCode = 0

            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                shotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle()
                            hb.close()
                            bmpRef.set(soft)
                        } catch (_: Throwable) {
                            errCode = -99
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        errCode = errorCode
                        latch.countDown()
                    }
                }
            )

            latch.await(6, TimeUnit.SECONDS)
            bitmap = bmpRef.get()
            if (bitmap != null) break
            lastError = errCode
            if (errCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && attempt < 4) {
                Thread.sleep(380L * attempt)
            } else {
                break
            }
        }

        val src = bitmap ?: throw IllegalStateException(
            "takeScreenshot failed (errorCode=$lastError). Note: FLAG_SECURE screens block capture."
        )

        val origW = src.width
        val origH = src.height
        var working = src

        try {
            if (!region.isNullOrBlank()) {
                val parts = region.split(",").map { it.trim().toInt() }
                if (parts.size == 4) {
                    val x = parts[0].coerceIn(0, origW - 1)
                    val y = parts[1].coerceIn(0, origH - 1)
                    val w = parts[2].coerceIn(1, origW - x)
                    val h = parts[3].coerceIn(1, origH - y)
                    val cropped = Bitmap.createBitmap(working, x, y, w, h)
                    if (cropped !== working) {
                        working.recycle()
                        working = cropped
                    }
                }
            }

            if (maxWidth != null && maxWidth > 32 && working.width > maxWidth) {
                val ratio = maxWidth.toFloat() / working.width.toFloat()
                val targetH = (working.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(working, maxWidth, targetH, true)
                if (scaled !== working) {
                    working.recycle()
                    working = scaled
                }
            }

            val outW = working.width
            val outH = working.height
            val out = ByteArrayOutputStream()
            val isJpg = format.lowercase() in listOf("jpg", "jpeg")
            if (isJpg) {
                working.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 95), out)
            } else {
                working.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val mime = if (isJpg) "image/jpeg" else "image/png"
            return CapturedShot(out.toByteArray(), mime, origW, origH, outW, outH)
        } finally {
            if (!working.isRecycled) working.recycle()
        }
    }
}
