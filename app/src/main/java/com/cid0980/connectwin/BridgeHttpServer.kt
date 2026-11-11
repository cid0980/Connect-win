package com.cid0980.connectwin

import android.content.Context
import android.util.Base64
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
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
            override fun onOpen() { BridgeState.log("WS connected") }
            override fun onClose(code: WebSocketFrame.CloseCode, reason: String, initiatedByRemote: Boolean) {
                BridgeState.log("WS closed")
            }
            override fun onPong(pong: WebSocketFrame) {}
            override fun onException(exception: IOException) { BridgeState.log("WS error: ${exception.javaClass.simpleName}") }
            override fun onMessage(message: WebSocketFrame) {
                try {
                    if (!BridgeState.verifyToken(context, token)) {
                        close(WebSocketFrame.CloseCode.PolicyViolation, "token revoked", false)
                        return
                    }
                    val text = message.textPayload
                    require(text.length <= 65536) { "message too large" }
                    val request = JSONObject(text)
                    val op = request.optString("op")
                    val result = when (op) {
                        "ping" -> JSONObject().put("ok", true).put("pong", true)
                        "ui_state" -> requireA11y().uiStateJson().put("ok", true)
                        "batch" -> runBatch(request)
                        "shot" -> {
                            val shot = requireA11y().captureScreenshot(
                                region = null,
                                maxWidth = request.optInt("max_width", 540).coerceIn(160, 1080),
                                format = "jpg",
                                quality = request.optInt("quality", 60).coerceIn(20, 90)
                            )
                            JSONObject().put("ok", true).put("mime", shot.mime)
                                .put("w", shot.outWidth).put("h", shot.outHeight)
                                .put("b64", Base64.encodeToString(shot.bytes, Base64.NO_WRAP))
                        }
                        else -> throw IllegalArgumentException("unknown op")
                    }
                    if (request.has("id")) result.put("id", request.get("id"))
                    BridgeState.incrementRequests()
                    BridgeState.log("WS $op")
                    send(result.toString())
                } catch (e: Exception) {
                    runCatching { send(JSONObject().put("ok", false).put("error", e.message ?: "request failed").toString()) }
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
                            longClick = st.optBoolean("long", false)
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
                        "shot" -> {
                            val shot = requireA11y().captureScreenshot(
                                region = st.optString("region", "").ifEmpty { null },
                                maxWidth = st.optInt("max_width", 540).let { if (it > 0) it else null },
                                format = st.optString("format", "jpg"),
                                quality = st.optInt("quality", 70)
                            )
                            JSONObject().apply {
                                put("ok", true)
                                put("mime", shot.mime)
                                put("bytes", shot.bytes.size)
                                put("w", shot.outWidth)
                                put("h", shot.outHeight)
                                put("b64", Base64.encodeToString(shot.bytes, Base64.NO_WRAP))
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
            })
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
            val a11y = requireA11y()
            val shot = a11y.captureScreenshot(
                region = q(session, "region"),
                maxWidth = q(session, "max_width")?.toIntOrNull(),
                format = q(session, "format") ?: "jpg",
                quality = q(session, "quality")?.toIntOrNull() ?: 75
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
            val fmt = body.optString("format", q(session, "format") ?: "jpg")
            val qual = body.optInt("quality", q(session, "quality")?.toIntOrNull() ?: 72)

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
                put("b64", b64)
                if (shot.mime == "image/png") put("png_b64", b64)
            })
        }

        // ==============================================================
        // 3. UI ACCESSIBILITY DOM (/ui/state, /ui/tree, /ui/click, /ui/set-text, /ui/wait)
        // ==============================================================
        if (path == "/ui/state" && method == Method.GET) {
            return ok(requireA11y().uiStateJson())
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
                    longClick = b.optBoolean("long", false)
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

        return err(Response.Status.NOT_FOUND, "Unknown endpoint: $method $path")
    }
}
