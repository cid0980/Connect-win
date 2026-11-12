package com.cid0980.connectwin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Base64
import com.cid0980.connectwin.realtime.BinaryProtocol
import com.cid0980.connectwin.realtime.ReflexEngine
import com.cid0980.connectwin.realtime.ScreenCaptureService
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

class BridgeHttpServer(
    private val context: Context,
    port: Int = BridgeState.HTTP_PORT
) : NanoWSD("127.0.0.1", port) {

    private fun jsonResp(status: Response.IStatus, obj: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString())

    private fun ok(obj: JSONObject): Response = jsonResp(Response.Status.OK, obj)

    private fun err(status: Response.IStatus, msg: String): Response =
        jsonResp(status, JSONObject().put("ok", false).put("error", msg).put("detail", msg))

    private fun q(session: IHTTPSession, key: String): String? =
        session.parameters[key]?.firstOrNull()

    private fun readBodyFiles(session: IHTTPSession): Map<String, String> {
        val files = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT || session.method == Method.PATCH) {
            runCatching { session.parseBody(files) }
        }
        return files
    }

    private fun readJson(session: IHTTPSession, preParsed: Map<String, String>? = null): JSONObject {
        val map = preParsed ?: readBodyFiles(session)
        val raw = map["postData"]?.trim() ?: ""
        if (raw.isEmpty()) return JSONObject()
        return runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
    }

    private fun requireA11y(): BridgeAccessibilityService =
        BridgeAccessibilityService.instance
            ?: throw IllegalStateException("AccessibilityService not enabled. Enable Connect-win in Settings -> Accessibility.")

    private fun requireNotifs(): BridgeNotificationService =
        BridgeNotificationService.instance
            ?: throw IllegalStateException("NotificationListener not enabled. Grant Notification Access in Connect-win.")

    // ------------------------------------------------------------------
    // Fast frame acquisition: prefer the MediaProjection frame store (no rate
    // limit) and fall back to the accessibility screenshot when capture is off.
    // ------------------------------------------------------------------
    private data class FrameResult(
        val bytes: ByteArray, val w: Int, val h: Int,
        val origW: Int, val origH: Int, val seq: Long, val source: String
    )

    private fun physicalScreen(): Pair<Int, Int> =
        BridgeAccessibilityService.instance?.screenSize() ?: Pair(0, 0)

    // Shared single-entry encode cache: when a stream and an on-demand shot (or two
    // consumers) want the same frame at the same size/quality, encode once and reuse.
    private val frameCacheLock = Any()
    private var cachedFrameKey: String? = null
    private var cachedFrame: FrameResult? = null

    private fun grabFrame(maxWidth: Int, quality: Int): FrameResult {
        if (ScreenCaptureService.isRunning()) {
            val seq = ScreenCaptureService.frameStore.seq
            val q = quality.coerceIn(10, 95)
            val key = "$seq:$maxWidth:$q"
            synchronized(frameCacheLock) {
                if (cachedFrameKey == key) cachedFrame?.let { return it }
            }
            val bmp = ScreenCaptureService.frameStore.copy()
            if (bmp != null) {
                try {
                    val (pw, ph) = physicalScreen()
                    val origW = if (pw > 0) pw else bmp.width
                    val origH = if (ph > 0) ph else bmp.height
                    var work = bmp
                    if (maxWidth in 1 until bmp.width) {
                        val ratio = maxWidth.toFloat() / bmp.width
                        val th = (bmp.height * ratio).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(bmp, maxWidth, th, true)
                        if (scaled !== bmp) work = scaled
                    }
                    val out = ByteArrayOutputStream()
                    work.compress(Bitmap.CompressFormat.JPEG, q, out)
                    val res = FrameResult(
                        out.toByteArray(), work.width, work.height, origW, origH,
                        seq, "mediaprojection"
                    )
                    if (work !== bmp) work.recycle()
                    synchronized(frameCacheLock) { cachedFrameKey = key; cachedFrame = res }
                    return res
                } finally {
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }
        }
        val shot = requireA11y().captureScreenshot(null, if (maxWidth > 0) maxWidth else null, "jpg", quality)
        return FrameResult(shot.bytes, shot.outWidth, shot.outHeight, shot.origWidth, shot.origHeight, -1L, "accessibility")
    }

    private fun captureStatusJson(): JSONObject = JSONObject().apply {
        put("ok", true)
        put("running", ScreenCaptureService.isRunning())
        put("seq", ScreenCaptureService.frameStore.seq)
        put("width", ScreenCaptureService.frameStore.width)
        put("height", ScreenCaptureService.frameStore.height)
        put("frame_age_ms", ScreenCaptureService.frameStore.ageMs())
        put("source", if (ScreenCaptureService.isRunning()) "mediaprojection" else "accessibility")
    }

    private fun activityJson(s: AgentActivityTracker.Snapshot): JSONObject = JSONObject().apply {
        put("state", s.state)
        put("message", s.message)
        put("explicit_state", s.explicitState)
        put("last_request_age_ms", s.lastRequestAgeMs ?: JSONObject.NULL)
        put("last_action_age_ms", s.lastActionAgeMs ?: JSONObject.NULL)
        put("heartbeat_age_ms", s.heartbeatAgeMs ?: JSONObject.NULL)
        put("expires_in_s", s.expiresInSeconds ?: JSONObject.NULL)
        put("request_count", s.requestCount)
        put("action_count", s.actionCount)
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri ?: "/"
        val method = session.method

        if (isWebsocketRequested(session)) {
            if (path != "/ws") return err(Response.Status.NOT_FOUND, "unknown websocket endpoint")
            // Never accept credentials in a URL or browser-origin requests.
            if (session.headers["origin"] != null) return err(Response.Status.FORBIDDEN, "browser origin not allowed")
            val token = session.headers["x-bridge-token"]
            if (!BridgeState.verifyToken(context, token)) {
                BridgeState.log("AUTH_FAIL WS /ws")
                return err(Response.Status.UNAUTHORIZED, "invalid X-Bridge-Token")
            }
            return super.serve(session)
        }

        // Unauthenticated local health probe
        if (path == "/health" && method == Method.GET) {
            val snap = BridgeState.snapshot.value
            return ok(JSONObject().apply {
                put("ok", true)
                put("app", "Connect-win")
                put("version", BridgeState.VERSION)
                put("tunnel_status", snap.tunnelStatus)
                put("accessibility", BridgeAccessibilityService.instance != null)
            })
        }

        // Constant-time token verification (NanoHTTPD lowercases header keys)
        val tokenHeader = session.headers["x-bridge-token"]
            ?: session.headers.entries.firstOrNull { it.key.equals("x-bridge-token", ignoreCase = true) }?.value
            ?: q(session, "token")

        if (!BridgeState.verifyToken(context, tokenHeader)) {
            BridgeState.log("AUTH_FAIL $method $path")
            return err(Response.Status.UNAUTHORIZED, "invalid X-Bridge-Token")
        }

        BridgeState.incrementRequests()
        AgentActivity.observeRequest(path, method.name, SystemClock.elapsedRealtime())
        val t0 = System.currentTimeMillis()

        return try {
            val resp = dispatch(session, method, path)
            val ms = System.currentTimeMillis() - t0
            if (path != "/admin/audit") {
                BridgeState.log("$method $path -> ${resp.status.requestStatus} (${ms}ms)")
            }
            resp
        } catch (ise: IllegalStateException) {
            BridgeState.log("ERR $method $path: ${ise.message}")
            err(Response.Status.SERVICE_UNAVAILABLE, ise.message ?: "service unavailable")
        } catch (iae: IllegalArgumentException) {
            err(Response.Status.BAD_REQUEST, iae.message ?: "bad request")
        } catch (t: Throwable) {
            BridgeState.log("EXC $method $path: ${t.javaClass.simpleName}: ${t.message}")
            err(Response.Status.INTERNAL_ERROR, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val token = handshake.headers["x-bridge-token"]
        return object : WebSocket(handshake) {
            private val sendLock = Any()
            @Volatile private var streaming = false
            private var streamThread: Thread? = null

            override fun onOpen() { BridgeState.log("WS connected") }
            override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
                stopStream()
                runCatching { BridgeAccessibilityService.instance?.touchCancelAll() }
                BridgeState.log("WS closed")
            }
            override fun onPong(pong: WebSocketFrame) {}
            override fun onException(exception: IOException) {
                stopStream()
                BridgeState.log("WS error: ${exception.javaClass.simpleName}")
            }

            private fun sendTextSafe(s: String) { synchronized(sendLock) { runCatching { send(s) } } }
            private fun sendBinarySafe(b: ByteArray) { synchronized(sendLock) { runCatching { send(b) } } }

            private fun stopStream() {
                streaming = false
                streamThread?.interrupt()
                streamThread = null
            }

            private fun startStream(fps: Int, maxW: Int, q: Int) {
                stopStream()
                streaming = true
                streamThread = Thread {
                    val interval = 1000L / fps
                    var lastSeq = -1L
                    while (streaming && isOpen) {
                        val t0 = System.currentTimeMillis()
                        try {
                            val f = grabFrame(maxW, q)
                            if (f.source != "mediaprojection" || f.seq != lastSeq) {
                                lastSeq = f.seq
                                sendBinarySafe(BinaryProtocol.encodeFrame(f.seq, f.w, f.h, f.origW, f.origH, f.bytes))
                            }
                        } catch (e: Exception) {
                            runCatching { Thread.sleep(100) }
                        }
                        val sleep = interval - (System.currentTimeMillis() - t0)
                        if (sleep > 0) runCatching { Thread.sleep(sleep) }
                    }
                }.apply { isDaemon = true; start() }
            }

            private fun handleBinary(data: ByteArray) {
                val req = BinaryProtocol.decode(data)
                if (req == null) { sendBinarySafe(BinaryProtocol.encodeTouchAck(255, false)); return }
                val type = req[0]; val a = req[1]; val b = req[2]; val c = req[3]; val seq = req[4]
                when (type) {
                    0x01 -> {
                        val f = grabFrame(a, b)
                        sendBinarySafe(BinaryProtocol.encodeFrame(f.seq, f.w, f.h, f.origW, f.origH, f.bytes))
                    }
                    0x02 -> { requireA11y().touchDown(a, b, c); sendBinarySafe(BinaryProtocol.encodeTouchAck(a, true)) }
                    0x03 -> { requireA11y().touchMove(a, b, c) } // no ack: keep move path minimal
                    0x04 -> { requireA11y().touchUp(a); sendBinarySafe(BinaryProtocol.encodeTouchAck(a, true)) }
                    0x05 -> sendBinarySafe(BinaryProtocol.encodePong(a, System.currentTimeMillis()))
                    0x10, 0x11, 0x12 -> { // sequenced touch: always ack in order
                        val svc = BridgeAccessibilityService.instance
                        if (svc == null) {
                            sendBinarySafe(BinaryProtocol.encodeTouchAckSeq(a, seq, BinaryProtocol.ACK_UNAVAILABLE))
                        } else if (a !in 0 until com.cid0980.connectwin.realtime.ContinuousTouchEngine.MAX_POINTERS) {
                            sendBinarySafe(BinaryProtocol.encodeTouchAckSeq(a, seq, BinaryProtocol.ACK_INVALID))
                        } else {
                            when (type) {
                                0x10 -> svc.touchDown(a, b, c)
                                0x11 -> svc.touchMove(a, b, c)
                                0x12 -> svc.touchUp(a)
                            }
                            sendBinarySafe(BinaryProtocol.encodeTouchAckSeq(a, seq, BinaryProtocol.ACK_QUEUED))
                        }
                    }
                }
                BridgeState.incrementRequests()
            }

            override fun onMessage(message: WebSocketFrame) {
                try {
                    if (!BridgeState.verifyToken(context, token)) {
                        close(WebSocketFrame.CloseCode.PolicyViolation, "token revoked", false)
                        return
                    }
                    if (message.opCode == WebSocketFrame.OpCode.Binary) {
                        handleBinary(message.binaryPayload)
                        return
                    }
                    val text = message.textPayload
                    require(text.length <= 65536) { "message too large" }
                    val request = JSONObject(text)
                    val op = request.optString("op")
                    val result = when (op) {
                        "ping" -> JSONObject().put("ok", true).put("pong", true).put("t", System.currentTimeMillis())
                        "ui_state" -> requireA11y().uiStateJson(request.optBoolean("full", false)).put("ok", true)
                        "batch" -> runBatch(request)
                        "shot" -> {
                            val f = grabFrame(
                                request.optInt("max_width", 540).coerceIn(160, 1080),
                                request.optInt("quality", 60).coerceIn(20, 90)
                            )
                            JSONObject().put("ok", true).put("mime", "image/jpeg")
                                .put("w", f.w).put("h", f.h).put("source", f.source)
                                .put("b64", Base64.encodeToString(f.bytes, Base64.NO_WRAP))
                        }
                        "touch_down" -> { requireA11y().touchDown(request.optInt("pointer", 0), request.getInt("x"), request.getInt("y")); JSONObject().put("ok", true) }
                        "touch_move" -> { requireA11y().touchMove(request.optInt("pointer", 0), request.getInt("x"), request.getInt("y")); JSONObject().put("ok", true) }
                        "touch_up" -> { requireA11y().touchUp(request.optInt("pointer", 0)); JSONObject().put("ok", true) }
                        "touch_press" -> { requireA11y().touchPress(request.optInt("pointer", 0), request.getInt("x"), request.getInt("y"), request.optLong("hold_ms", 80)); JSONObject().put("ok", true) }
                        "touch_cancel" -> { requireA11y().touchCancelAll(); JSONObject().put("ok", true) }
                        "touch_state" -> requireA11y().touchState()
                        "touch_tick" -> { requireA11y().setTouchTickMs(request.optLong("tick_ms", 16)); JSONObject().put("ok", true) }
                        "stream_start" -> {
                            val fps = request.optInt("fps", 15).coerceIn(1, 30)
                            startStream(fps, request.optInt("max_width", 480).coerceIn(160, 1080), request.optInt("quality", 55).coerceIn(20, 90))
                            JSONObject().put("ok", true).put("streaming", true).put("fps", fps)
                                .put("capture_running", ScreenCaptureService.isRunning())
                        }
                        "stream_stop" -> { stopStream(); JSONObject().put("ok", true).put("streaming", false) }
                        "capture_status" -> captureStatusJson()
                        "reflex_list" -> ReflexEngine.listJson()
                        "reflex_add" -> ReflexEngine.addRule(request.optJSONObject("rule") ?: request)
                        "reflex_remove" -> JSONObject().put("ok", ReflexEngine.removeRule(request.optString("id")))
                        "reflex_enable" -> { ReflexEngine.setEnabled(request.optBoolean("enabled", true)); JSONObject().put("ok", true) }
                        else -> throw IllegalArgumentException("unknown op")
                    }
                    if (request.has("id")) result.put("id", request.get("id"))
                    BridgeState.incrementRequests()
                    BridgeState.log("WS $op")
                    sendTextSafe(result.toString())
                } catch (e: Exception) {
                    sendTextSafe(JSONObject().put("ok", false).put("error", e.message ?: "request failed").toString())
                }
            }
        }
    }

    private fun runBatch(b: JSONObject): JSONObject {
            val steps = b.optJSONArray("steps") ?: throw IllegalArgumentException("Missing steps array")
            require(steps.length() <= 100) { "Maximum 100 batch steps" }
            val stopOnError = b.optBoolean("stop_on_error", true)
            val outArr = JSONArray()
            val tStart = System.currentTimeMillis()

            for (i in 0 until steps.length()) {
                val st = steps.getJSONObject(i)
                val op = st.optString("op", "").lowercase()
                val stStart = System.currentTimeMillis()
                try {
                    val stepRes: JSONObject = when (op) {
                        "click", "tap" -> {
                            val okTap = requireA11y().tap(
                                st.getInt("x"),
                                st.getInt("y"),
                                st.optLong("duration_ms", 55L)
                            )
                            JSONObject().put("ok", okTap)
                        }
                        "timed_gesture" -> {
                            val strokes = st.optJSONArray("strokes") ?: throw IllegalArgumentException("Missing strokes")
                            JSONObject().put("ok", requireA11y().timedGesture(strokes))
                        }
                        "longpress" -> {
                            val okLp = requireA11y().longPress(
                                st.getInt("x"),
                                st.getInt("y"),
                                st.optLong("duration_ms", 550L)
                            )
                            JSONObject().put("ok", okLp)
                        }
                        "swipe" -> {
                            val okSw = requireA11y().swipe(
                                st.getInt("x1"), st.getInt("y1"),
                                st.getInt("x2"), st.getInt("y2"),
                                st.optLong("duration_ms", 280L)
                            )
                            JSONObject().put("ok", okSw)
                        }
                        "scroll" -> requireA11y().scrollScreen(st.optInt("dy", -2))
                        "ui_click" -> requireA11y().uiClick(
                            ref = if (st.has("ref")) st.optInt("ref") else null,
                            text = st.optString("text", "").ifEmpty { null },
                            desc = st.optString("desc", "").ifEmpty { null },
                            resId = st.optString("id", "").ifEmpty { null },
                            exact = st.optBoolean("exact", false),
                            index = st.optInt("index", 0),
                            longClick = st.optBoolean("long", false),
                            method = st.optString("method", "auto"),
                            expectText = st.optString("expect_text", "").ifEmpty { null },
                            expectTimeoutMs = st.optLong("expect_timeout_ms", 3000)
                        )
                        "set_text", "type" -> requireA11y().uiSetText(
                            value = st.optString("text", ""),
                            ref = if (st.has("ref")) st.optInt("ref") else null,
                            target = st.optString("target", "").ifEmpty { null },
                            resId = st.optString("id", "").ifEmpty { null },
                            append = st.optBoolean("append", false),
                            submit = st.optBoolean("submit", false)
                        )
                        "key", "keys", "global" -> {
                            val act = st.optString("action", st.optString("combo", "back"))
                            JSONObject().put("ok", requireA11y().triggerGlobalAction(act)).put("action", act)
                        }
                        "open" -> PhoneController.openTarget(context, st.getString("target"))
                        "wait" -> requireA11y().uiWait(
                            text = st.optString("text", "").ifEmpty { null },
                            desc = st.optString("desc", "").ifEmpty { null },
                            resId = st.optString("id", "").ifEmpty { null },
                            gone = st.optBoolean("gone", false),
                            timeoutMs = st.optLong("timeout_ms", 5000L)
                        )
                        "sleep" -> {
                            val ms = if (st.has("ms")) st.optLong("ms")
                            else (st.optDouble("s", 0.4) * 1000.0).toLong()
                            Thread.sleep(ms.coerceIn(10L, 20_000L))
                            JSONObject().put("ok", true).put("slept_ms", ms)
                        }
                        "ui_state" -> requireA11y().uiStateJson().put("ok", true)
                        "touch" -> {
                            val a11y = requireA11y()
                            val p = st.optInt("pointer", 0)
                            when (st.optString("action", "press")) {
                                "down" -> a11y.touchDown(p, st.getInt("x"), st.getInt("y"))
                                "move" -> a11y.touchMove(p, st.getInt("x"), st.getInt("y"))
                                "up" -> a11y.touchUp(p)
                                "cancel" -> a11y.touchCancelAll()
                                else -> a11y.touchPress(p, st.getInt("x"), st.getInt("y"), st.optLong("hold_ms", 80L))
                            }
                            JSONObject().put("ok", true)
                        }
                        "shot" -> {
                            val region = st.optString("region", "").ifEmpty { null }
                            if (region == null) {
                                val f = grabFrame(st.optInt("max_width", 540), st.optInt("quality", 70))
                                JSONObject().apply {
                                    put("ok", true); put("mime", "image/jpeg"); put("bytes", f.bytes.size)
                                    put("w", f.w); put("h", f.h); put("source", f.source)
                                    put("b64", Base64.encodeToString(f.bytes, Base64.NO_WRAP))
                                }
                            } else {
                                val shot = requireA11y().captureScreenshot(
                                    region = region,
                                    maxWidth = st.optInt("max_width", 540).let { if (it > 0) it else null },
                                    format = st.optString("format", "jpg"),
                                    quality = st.optInt("quality", 70)
                                )
                                JSONObject().apply {
                                    put("ok", true); put("mime", shot.mime); put("bytes", shot.bytes.size)
                                    put("w", shot.outWidth); put("h", shot.outHeight)
                                    put("b64", Base64.encodeToString(shot.bytes, Base64.NO_WRAP))
                                }
                            }
                        }
                        else -> throw IllegalArgumentException("Unknown batch op: $op")
                    }
                    stepRes.put("i", i)
                    stepRes.put("op", op)
                    stepRes.put("ms", System.currentTimeMillis() - stStart)
                    outArr.put(stepRes)
                    if (!stepRes.optBoolean("ok", true) && stopOnError) break
                } catch (e: Exception) {
                    outArr.put(JSONObject().apply {
                        put("i", i)
                        put("op", op)
                        put("ok", false)
                        put("ms", System.currentTimeMillis() - stStart)
                        put("error", e.message ?: "step failed")
                    })
                    if (stopOnError) break
                }
            }

            return JSONObject().apply {
                put("steps", outArr)
                put("ran", outArr.length())
                put("of", steps.length())
                put("ms", System.currentTimeMillis() - tStart)
            }
    }

    private fun dispatch(session: IHTTPSession, method: Method, path: String): Response {
        // ==============================================================
        // 1. STATUS, VITALS & AUDIT
        // ==============================================================
        if (path == "/status" && method == Method.GET) {
            val snap = BridgeState.snapshot.value
            val uptimeSec = if (snap.startedAtMs > 0) (System.currentTimeMillis() - snap.startedAtMs) / 1000 else 0
            return ok(JSONObject().apply {
                put("ok", true)
                put("app", "Connect-win")
                put("version", BridgeState.VERSION)
                put("platform", "android")
                put("uptime_s", uptimeSec)
                put("requests", snap.requestCount)
                put("tunnel_status", snap.tunnelStatus)
                put("tunnel_url", snap.tunnelUrl ?: JSONObject.NULL)
                put("permissions", PhoneController.permissionsStatus(context))
                put("device", PhoneController.deviceStatus(context))
                put("notifications_active", BridgeNotificationService.instance?.listNotificationsJson(false)?.optInt("active_count", 0) ?: 0)
                put("agent_activity", activityJson(AgentActivity.snapshot(SystemClock.elapsedRealtime())))
                put("capture", captureStatusJson())
            })
        }

        if (path == "/agent/status" && method == Method.GET) {
            return ok(JSONObject().put("ok", true)
                .put("activity", activityJson(AgentActivity.snapshot(SystemClock.elapsedRealtime()))))
        }

        if (path == "/agent/status" && method == Method.POST) {
            val b = readJson(session)
            val state = b.optString("state", "working")
            val message = b.optString("message", "")
            val ttl = if (b.has("ttl_ms")) b.optLong("ttl_ms") else null
            val snap = AgentActivity.setAgent(state, message, SystemClock.elapsedRealtime(), ttl)
            BridgeState.log("AGENT ${snap.state}: ${snap.message}")
            return ok(JSONObject().put("ok", true).put("activity", activityJson(snap)))
        }

        if (path == "/system-info" && method == Method.GET) {
            return ok(PhoneController.deviceStatus(context))
        }

        if (path == "/admin/audit" && method == Method.GET) {
            val lines = q(session, "lines")?.toIntOrNull() ?: 60
            return ok(JSONObject().put("lines", JSONArray(BridgeState.recentAudit(lines))))
        }

        // ==============================================================
        // 2. SCREENSHOTS (/shot base64 JSON + /screenshot raw bytes)
        // ==============================================================
        if ((path == "/screenshot" || path == "/gui/screenshot") && method == Method.GET) {
            val region = q(session, "region")
            val fmt = (q(session, "format") ?: "jpg").lowercase()
            val maxW = q(session, "max_width")?.toIntOrNull() ?: 0
            val qual = q(session, "quality")?.toIntOrNull() ?: 75
            // Full-frame JPEG uses the shared fast path (MediaProjection when enabled,
            // accessibility fallback otherwise). Cropped/PNG shots still use a11y.
            if (region.isNullOrBlank() && (fmt == "jpg" || fmt == "jpeg")) {
                val f = grabFrame(maxW, qual)
                val resp = newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    ByteArrayInputStream(f.bytes), f.bytes.size.toLong()
                )
                resp.addHeader("X-Capture-Source", f.source)
                resp.addHeader("X-Frame-Seq", f.seq.toString())
                resp.addHeader("X-Frame-Age-Ms", ScreenCaptureService.frameStore.ageMs().toString())
                return resp
            }
            val shot = requireA11y().captureScreenshot(
                region = region,
                maxWidth = if (maxW > 0) maxW else null,
                format = fmt,
                quality = qual
            )
            return newFixedLengthResponse(
                Response.Status.OK,
                shot.mime,
                ByteArrayInputStream(shot.bytes),
                shot.bytes.size.toLong()
            )
        }

        if (path == "/shot" && (method == Method.POST || method == Method.GET)) {
            val body = if (method == Method.POST) readJson(session) else JSONObject()
            val region = body.optString("region", "").ifEmpty { q(session, "region") }
            val maxW = if (body.has("max_width")) body.optInt("max_width") else (q(session, "max_width")?.toIntOrNull() ?: 720)
            val fmt = body.optString("format", q(session, "format") ?: "jpg").lowercase()
            val qual = body.optInt("quality", q(session, "quality")?.toIntOrNull() ?: 72)

            if (region.isNullOrBlank() && (fmt == "jpg" || fmt == "jpeg")) {
                val f = grabFrame(maxW, qual)
                val b64 = Base64.encodeToString(f.bytes, Base64.NO_WRAP)
                return ok(JSONObject().apply {
                    put("ok", true)
                    put("mime", "image/jpeg")
                    put("bytes", f.bytes.size)
                    put("orig_w", f.origW)
                    put("orig_h", f.origH)
                    put("w", f.w)
                    put("h", f.h)
                    put("source", f.source)
                    put("seq", f.seq)
                    put("b64", b64)
                })
            }

            val shot = requireA11y().captureScreenshot(
                region = region,
                maxWidth = if (maxW > 0) maxW else null,
                format = fmt,
                quality = qual
            )
            val b64 = Base64.encodeToString(shot.bytes, Base64.NO_WRAP)
            return ok(JSONObject().apply {
                put("ok", true)
                put("mime", shot.mime)
                put("bytes", shot.bytes.size)
                put("orig_w", shot.origWidth)
                put("orig_h", shot.origHeight)
                put("w", shot.outWidth)
                put("h", shot.outHeight)
                put("source", "accessibility")
                put("b64", b64)
                if (shot.mime == "image/png") put("png_b64", b64)
            })
        }

        // ==============================================================
        // 3. UI ACCESSIBILITY DOM (/ui/state, /ui/tree, /ui/click, /ui/set-text, /ui/wait)
        // ==============================================================
        if (path == "/ui/state" && method == Method.GET) {
            return ok(requireA11y().uiStateJson(q(session, "full") == "true"))
        }

        if (path == "/ui/tree" && method == Method.GET) {
            val all = q(session, "all") == "true" || q(session, "all") == "1"
            return ok(requireA11y().uiTreeJson(all))
        }

        if (path == "/ui/click" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                requireA11y().uiClick(
                    ref = if (b.has("ref")) b.optInt("ref") else null,
                    text = b.optString("text", "").ifEmpty { null },
                    desc = b.optString("desc", "").ifEmpty { null },
                    resId = b.optString("id", "").ifEmpty { null },
                    exact = b.optBoolean("exact", false),
                    index = b.optInt("index", 0),
                    longClick = b.optBoolean("long", false),
                    method = b.optString("method", "auto"),
                    expectText = b.optString("expect_text", "").ifEmpty { null },
                    expectTimeoutMs = b.optLong("expect_timeout_ms", 3000)
                )
            )
        }

        if (path == "/ui/set-text" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                requireA11y().uiSetText(
                    value = b.optString("text", ""),
                    ref = if (b.has("ref")) b.optInt("ref") else null,
                    target = b.optString("target", "").ifEmpty { null },
                    resId = b.optString("id", "").ifEmpty { null },
                    append = b.optBoolean("append", false),
                    submit = b.optBoolean("submit", false)
                )
            )
        }

        if (path == "/ui/wait" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                requireA11y().uiWait(
                    text = b.optString("text", "").ifEmpty { null },
                    desc = b.optString("desc", "").ifEmpty { null },
                    resId = b.optString("id", "").ifEmpty { null },
                    gone = b.optBoolean("gone", false),
                    timeoutMs = b.optLong("timeout_ms", 6000L)
                )
            )
        }

        // ==============================================================
        // 4. GESTURES & ONE-CALL /gui/batch SPEED LAYER
        // ==============================================================
        if ((path == "/gui/click" || path == "/gui/click2" || path == "/gui/safeclick") && method == Method.POST) {
            val b = readJson(session)
            val x = b.getInt("x")
            val y = b.getInt("y")
            val dur = b.optLong("duration_ms", if (path == "/gui/click2") 110L else 55L)
            val okTap = requireA11y().tap(x, y, dur)
            return ok(JSONObject().put("ok", okTap).put("clicked", JSONArray(listOf(x, y))))
        }

        if (path == "/gui/longpress" && method == Method.POST) {
            val b = readJson(session)
            val x = b.getInt("x")
            val y = b.getInt("y")
            val dur = b.optLong("duration_ms", 600L)
            return ok(JSONObject().put("ok", requireA11y().longPress(x, y, dur)).put("long_pressed", JSONArray(listOf(x, y))))
        }

        if (path == "/gui/swipe" && method == Method.POST) {
            val b = readJson(session)
            val okSw = requireA11y().swipe(
                b.getInt("x1"), b.getInt("y1"),
                b.getInt("x2"), b.getInt("y2"),
                b.optLong("duration_ms", 280L)
            )
            return ok(JSONObject().put("ok", okSw))
        }

        if (path == "/gui/pinch" && method == Method.POST) {
            val b = readJson(session)
            val a11y = requireA11y()
            val (w, h) = a11y.screenSize()
            val okPinch = a11y.pinch(
                b.optInt("cx", w / 2),
                b.optInt("cy", h / 2),
                b.optInt("start_span", 220),
                b.optInt("end_span", 650),
                b.optLong("duration_ms", 350L)
            )
            return ok(JSONObject().put("ok", okPinch))
        }

        if (path == "/gui/scroll" && method == Method.POST) {
            val b = readJson(session)
            return ok(requireA11y().scrollScreen(b.optInt("dy", -2)))
        }

        if (path == "/gui/type" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                requireA11y().uiSetText(
                    value = b.optString("text", ""),
                    append = b.optBoolean("append", false),
                    submit = b.optBoolean("submit", false)
                )
            )
        }

        if (path == "/gui/keys" && method == Method.POST) {
            val b = readJson(session)
            val combo = b.optString("combo", b.optString("action", "back"))
            val okAct = requireA11y().triggerGlobalAction(combo)
            return ok(JSONObject().put("ok", okAct).put("action", combo))
        }

        if ((path == "/gui/open" || path == "/apps/launch") && method == Method.POST) {
            val b = readJson(session)
            val target = b.optString("target", b.optString("package", ""))
            if (target.isBlank()) throw IllegalArgumentException("Provide target or package")
            return ok(PhoneController.openTarget(context, target))
        }

        if (path == "/gui/batch" && method == Method.POST) {
            return ok(runBatch(readJson(session)))
        }

        // ==============================================================
        // 5. NOTIFICATIONS (/notifications, /notifications/act, /notifications/dismiss)
        // ==============================================================
        if (path == "/notifications" && method == Method.GET) {
            val hist = q(session, "history") != "false"
            return ok(requireNotifs().listNotificationsJson(hist))
        }

        if (path == "/notifications/act" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                requireNotifs().actOnNotification(
                    query = b.optString("query", b.optString("key", "")),
                    actionLabel = b.optString("action", "").ifEmpty { null },
                    replyText = b.optString("reply", "").ifEmpty { null }
                )
            )
        }

        if (path == "/notifications/dismiss" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                requireNotifs().dismissNotifications(
                    key = b.optString("key", b.optString("query", "")).ifEmpty { null },
                    all = b.optBoolean("all", false)
                )
            )
        }

        // ==============================================================
        // 6. APPS & INTENTS (/apps, /apps/intent, /apps/install)
        // ==============================================================
        if (path == "/apps" && method == Method.GET) {
            val userOnly = q(session, "user_only") == "true" || q(session, "user_only") == "1"
            return ok(PhoneController.listApps(context, q(session, "query"), userOnly))
        }

        if (path == "/apps/intent" && method == Method.POST) {
            return ok(PhoneController.sendCustomIntent(context, readJson(session)))
        }

        if (path == "/apps/install" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.installApk(context, b.getString("path")))
        }

        // ==============================================================
        // 7. FILES & STORAGE (/list, /read, /files/*)
        // ==============================================================
        if (path == "/list" && method == Method.GET) {
            return ok(PhoneController.listDirectory(context, q(session, "path")))
        }

        if (path == "/read" && method == Method.GET) {
            val p = q(session, "path") ?: throw IllegalArgumentException("Missing path query param")
            return ok(PhoneController.readFileText(context, p))
        }

        if (path == "/files/download" && method == Method.GET) {
            val p = q(session, "path") ?: throw IllegalArgumentException("Missing path query param")
            val f = PhoneController.resolvePath(context, p)
            if (!f.exists() || !f.isFile) {
                return err(Response.Status.NOT_FOUND, "file not found: ${f.absolutePath}")
            }
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                "application/octet-stream",
                FileInputStream(f),
                f.length()
            )
            resp.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
            return resp
        }

        if (path == "/files/write" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                PhoneController.writeFile(
                    context,
                    rawPath = b.getString("path"),
                    content = if (b.has("content")) b.optString("content") else null,
                    b64 = b.optString("b64", "").ifEmpty { null },
                    append = b.optBoolean("append", false)
                )
            )
        }

        if (path == "/files/upload" && method == Method.POST) {
            val files = readBodyFiles(session)
            val destParam = q(session, "path") ?: throw IllegalArgumentException("Missing form/query field 'path'")
            val tmpFilePath = files["file"] ?: files.values.firstOrNull()
                ?: throw IllegalArgumentException("Missing uploaded 'file'")
            val tmpFile = File(tmpFilePath)
            val destFile = PhoneController.resolvePath(context, destParam)
            destFile.parentFile?.mkdirs()
            tmpFile.copyTo(destFile, overwrite = true)
            return ok(JSONObject().put("ok", true).put("saved_to", destFile.absolutePath).put("bytes", destFile.length()))
        }

        if (path == "/files/delete" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.deleteFile(context, b.getString("path")))
        }

        // ==============================================================
        // 8. PHONE HARDWARE, SENSORS & COMMS (/phone/*)
        // ==============================================================
        if (path == "/phone/clipboard" && method == Method.GET) {
            return ok(PhoneController.getClipboard(context))
        }

        if (path == "/phone/clipboard" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.setClipboard(context, b.optString("text", "")))
        }

        if (path == "/phone/wake" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.wakeScreen(context, b.optBoolean("swipe_up", true)))
        }

        if (path == "/phone/torch" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.setTorch(context, b.optBoolean("on", true)))
        }

        if (path == "/phone/volume" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                PhoneController.setVolume(
                    context,
                    streamName = b.optString("stream", "music"),
                    level = if (b.has("level")) b.optInt("level") else null,
                    pct = if (b.has("pct")) b.optInt("pct") else null
                )
            )
        }

        if (path == "/phone/brightness" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.setBrightness(context, b.getInt("value")))
        }

        if (path == "/phone/media" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.mediaControl(context, b.optString("command", "play_pause")))
        }

        // Explicit end-of-task notification: works without the Arena page being visible.
        // The existing bridge authentication applies; no chat content is accepted or stored.
        if (path == "/phone/notify-complete" && method == Method.POST) {
            val enabled = ArenaCompletionMonitor.enabled(context)
            val posted = enabled && ArenaCompletionMonitor.notify(context, direct = true)
            return ok(JSONObject().put("ok", posted).put("posted", posted)
                .put("reason", if (posted) "posted" else if (!enabled) "feature_disabled" else "notifications_disabled_or_post_failed")
                .put("note", "Sound depends on Android channel, volume and Do Not Disturb settings."))
        }

        if (path == "/phone/vibrate" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.vibrate(context, b.optLong("ms", 250L)))
        }

        if (path == "/phone/tts" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.speakTts(context, b.getString("text")))
        }

        if (path == "/phone/location" && method == Method.GET) {
            val timeout = q(session, "timeout_ms")?.toLongOrNull() ?: 4000L
            return ok(PhoneController.getLocation(context, timeout))
        }

        if (path == "/phone/camera" && method == Method.POST) {
            val b = readJson(session)
            return ok(
                PhoneController.capturePhoto(
                    context,
                    facing = b.optString("facing", "back"),
                    savePath = b.optString("save_path", "").ifEmpty { null }
                )
            )
        }

        if (path == "/phone/sms" && method == Method.GET) {
            val lim = q(session, "limit")?.toIntOrNull() ?: 20
            return ok(PhoneController.readSms(context, lim, q(session, "query")))
        }

        if (path == "/phone/sms" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.sendSms(context, b.getString("to"), b.getString("message")))
        }

        if (path == "/phone/contacts" && method == Method.GET) {
            val lim = q(session, "limit")?.toIntOrNull() ?: 50
            return ok(PhoneController.searchContacts(context, q(session, "query"), lim))
        }

        if (path == "/phone/calls" && method == Method.GET) {
            val lim = q(session, "limit")?.toIntOrNull() ?: 25
            return ok(PhoneController.readCallLog(context, lim))
        }

        if (path == "/phone/call" && method == Method.POST) {
            val b = readJson(session)
            return ok(PhoneController.makeCall(context, b.getString("number"), b.optBoolean("direct", true)))
        }

        // ==============================================================
        // 9. SHELL & BACKGROUND JOBS (/run, /jobs/*)
        // ==============================================================
        if ((path == "/run" || path == "/shell/run") && method == Method.POST) {
            val b = readJson(session)
            val cmd = b.getString("command")
            val warn = PhoneController.checkBlastGuard(cmd)
            if (warn != null && !b.optBoolean("force", false)) {
                return err(Response.Status.CONFLICT, warn)
            }
            val res = PhoneController.runCommand(
                context,
                command = cmd,
                cwd = b.optString("cwd", "").ifEmpty { null },
                timeoutSec = b.optLong("timeout", 35L)
            )
            // Keep /shell/run output alias compatible with agent_bridge
            if (path == "/shell/run") {
                res.put("output", res.optString("result", ""))
            }
            return ok(res)
        }

        if (path == "/jobs/start" && method == Method.POST) {
            val b = readJson(session)
            val cmd = b.getString("command")
            val warn = PhoneController.checkBlastGuard(cmd)
            if (warn != null && !b.optBoolean("force", false)) {
                return err(Response.Status.CONFLICT, warn)
            }
            return ok(PhoneController.startJob(context, cmd, b.optString("cwd", "").ifEmpty { null }))
        }

        if (path == "/jobs" && method == Method.GET) {
            return ok(PhoneController.listJobs())
        }

        if (path.startsWith("/jobs/") && path.endsWith("/kill") && method == Method.POST) {
            val jid = path.removePrefix("/jobs/").removeSuffix("/kill")
            return ok(PhoneController.killJob(jid))
        }

        if (path.startsWith("/jobs/") && method == Method.GET) {
            val jid = path.removePrefix("/jobs/")
            val offset = q(session, "offset")?.toLongOrNull() ?: 0L
            return ok(PhoneController.pollJob(jid, offset))
        }

        // ==============================================================
        // 10. REALTIME: continuous touch, fast capture, on-device reflexes
        // ==============================================================
        if (path == "/gui/touch" && method == Method.POST) {
            val b = readJson(session)
            val a11y = requireA11y()
            val action = b.optString("action", "state")
            val pointer = b.optInt("pointer", 0)
            when (action) {
                "down" -> a11y.touchDown(pointer, b.getInt("x"), b.getInt("y"))
                "move" -> a11y.touchMove(pointer, b.getInt("x"), b.getInt("y"))
                "up" -> a11y.touchUp(pointer)
                "press" -> a11y.touchPress(pointer, b.getInt("x"), b.getInt("y"), b.optLong("hold_ms", 80L))
                "cancel" -> a11y.touchCancelAll()
                "tick" -> a11y.setTouchTickMs(b.optLong("tick_ms", 16L))
                "state" -> {}
                else -> throw IllegalArgumentException("unknown touch action: $action")
            }
            return ok(a11y.touchState().put("action", action))
        }

        if (path == "/bench/ping" && method == Method.GET) {
            return ok(JSONObject().put("ok", true).put("t", System.currentTimeMillis()))
        }

        if (path == "/capture/status" && method == Method.GET) {
            return ok(captureStatusJson())
        }

        if (path == "/capture/start" && method == Method.POST) {
            return ok(JSONObject().put("ok", ScreenCaptureService.isRunning())
                .put("running", ScreenCaptureService.isRunning())
                .put("note", "MediaProjection needs one-time on-device consent. Tap 'Enable fast capture' in the Connect-win app."))
        }

        if (path == "/capture/stop" && method == Method.POST) {
            ScreenCaptureService.stop(context)
            return ok(JSONObject().put("ok", true).put("running", false))
        }

        if (path == "/capture/frame" && method == Method.GET) {
            val f = grabFrame(
                q(session, "max_width")?.toIntOrNull() ?: 720,
                q(session, "quality")?.toIntOrNull() ?: 70
            )
            return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(f.bytes), f.bytes.size.toLong())
        }

        if (path == "/capture/pixel" && method == Method.GET) {
            val nx = q(session, "x")?.toDoubleOrNull() ?: 0.5
            val ny = q(session, "y")?.toDoubleOrNull() ?: 0.5
            val normalized = q(session, "normalized") != "false"
            val pixel = ScreenCaptureService.frameStore.use { bmp ->
                val px = (if (normalized) nx * bmp.width else nx).toInt().coerceIn(0, bmp.width - 1)
                val py = (if (normalized) ny * bmp.height else ny).toInt().coerceIn(0, bmp.height - 1)
                bmp.getPixel(px, py)
            }
            return ok(JSONObject().apply {
                put("ok", pixel != null)
                if (pixel != null) {
                    put("r", Color.red(pixel)); put("g", Color.green(pixel)); put("b", Color.blue(pixel))
                    put("hex", String.format("#%06X", 0xFFFFFF and pixel))
                } else put("error", "no frame — enable fast capture first")
            })
        }

        if (path == "/capture/find_color" && method == Method.POST) {
            val b = readJson(session)
            val target = parseColorArg(b.opt("color"))
                ?: throw IllegalArgumentException("provide color as #RRGGBB or [r,g,b]")
            val tol = b.optInt("tolerance", 24).coerceIn(0, 255)
            val found = ScreenCaptureService.frameStore.use { bmp ->
                val stride = (bmp.width / 160).coerceAtLeast(1)
                var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
                var count = 0; var sx = 0L; var sy = 0L
                var yy = 0
                while (yy < bmp.height) {
                    var xx = 0
                    while (xx < bmp.width) {
                        val p = bmp.getPixel(xx, yy)
                        if (kotlin.math.abs(Color.red(p) - Color.red(target)) <= tol &&
                            kotlin.math.abs(Color.green(p) - Color.green(target)) <= tol &&
                            kotlin.math.abs(Color.blue(p) - Color.blue(target)) <= tol) {
                            count++; sx += xx; sy += yy
                            if (xx < minX) minX = xx; if (xx > maxX) maxX = xx
                            if (yy < minY) minY = yy; if (yy > maxY) maxY = yy
                        }
                        xx += stride
                    }
                    yy += stride
                }
                JSONObject().apply {
                    put("matches", count)
                    if (count > 0) {
                        put("centroid", JSONArray(listOf(sx / count, sy / count)))
                        put("bbox", JSONArray(listOf(minX, minY, maxX, maxY)))
                        put("centroid_normalized", JSONArray(listOf(
                            (sx / count).toDouble() / bmp.width, (sy / count).toDouble() / bmp.height)))
                    }
                }
            }
            return ok((found ?: JSONObject().put("matches", 0)).put("ok", true))
        }

        if (path == "/reflex/rules" && method == Method.GET) {
            return ok(ReflexEngine.listJson())
        }
        if (path == "/reflex/rules" && method == Method.POST) {
            return ok(ReflexEngine.addRule(readJson(session)))
        }
        if (path == "/reflex/delete" && method == Method.POST) {
            val b = readJson(session)
            return ok(JSONObject().put("ok", ReflexEngine.removeRule(b.optString("id"))))
        }
        if (path == "/reflex/clear" && method == Method.POST) {
            ReflexEngine.clear()
            return ok(JSONObject().put("ok", true))
        }
        if (path == "/reflex/enable" && method == Method.POST) {
            val b = readJson(session)
            ReflexEngine.setEnabled(b.optBoolean("enabled", true))
            return ok(JSONObject().put("ok", true).put("enabled", ReflexEngine.enabled))
        }

        return err(Response.Status.NOT_FOUND, "Unknown endpoint: $method $path")
    }

    private fun parseColorArg(v: Any?): Int? = when (v) {
        is String -> runCatching { Color.parseColor(if (v.startsWith("#")) v else "#$v") }.getOrNull()
        is JSONArray -> if (v.length() >= 3) Color.rgb(v.optInt(0), v.optInt(1), v.optInt(2)) else null
        is Int -> v
        else -> null
    }
}
