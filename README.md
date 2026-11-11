# 📱 Connect-win — Native Android Bridge for AI Agents

Give an AI agent (Arena Agent Mode, Cursor, or any HTTP-capable agent) **fast, native, full-device control over your Android phone** through **one HTTPS URL + one token** — no Termux, no ADB, no PC, works on both **Wi-Fi and 4G/5G**.

Built as the mobile counterpart to [`cid0980/agent_bridge`](https://github.com/cid0980/agent_bridge), carrying over its **v3.2 speed layer** (`POST /gui/batch`, cropped/downscaled JPEG vision, DOM-level click-by-text, blast-radius guard, and live audit logging).

---

## ✨ Architecture & Superpowers

- **Embedded `cloudflared` (`libcloudflared.so`)** — compiled in GitHub Actions with an Android DNS + `AndroidCAStore` TLS resolver patch so Quick Tunnels (`*.trycloudflare.com`) and Named Tunnels work natively on Android 11–16 over Wi-Fi and cellular data.
- **Zero-Screenshot UI DOM (`/ui/state`, `/ui/tree`, `/ui/click`, `/ui/set-text`, `/ui/wait`)** — reads every visible button, label, and input field across all windows in ~10 ms; clicks elements by text/`ref` and injects text directly via `ACTION_SET_TEXT` without keyboard lag.
- **Touch & Gesture Engine (`/gui/*`, `/gui/batch`)** — native `AccessibilityService.dispatchGesture` taps, long-presses, swipes, scrolls, 2-finger pinch/zoom, and system actions (`home`, `back`, `recents`, `notifications`, `quick_settings`, `lock_screen`).
- **One-Call Sequences (`POST /gui/batch`)** — run 10+ UI steps (`open` → `wait` → `ui_click` → `set_text` → `shot`) in **one HTTP round-trip**.
- **Zero-Popup Screenshots (`/shot`, `/screenshot`)** — Android 11+ `AccessibilityService.takeScreenshot` with on-device region crop, `max_width` downscaling, and JPEG compression.
- **Live Notification Control (`/notifications/*`)** — reads active + recent notifications (OTPs, WhatsApp, Gmail), triggers action buttons, and sends **background inline `RemoteInput` replies** without switching apps.
- **Full Storage, Apps & Hardware (`/files/*`, `/apps/*`, `/phone/*`)** — `/sdcard` upload/download/list/read/write, fuzzy app launcher + custom Intents + APK installer, clipboard, GPS location, headless Camera2 front/back capture, torch, volume/brightness/wake, media keys, TTS, vibrate, SMS read/send, contacts, call log & dialer, plus `/run` & `/jobs/*`.

---

## ⚡ Realtime layer (v1.4) — human-like, low-latency control

Built for seamless agent control and game-style tasks, so the only real delay is the network:

- **Continuous multi-touch** — held, smoothly-dragging fingers (`/gui/touch`, WS `touch_*`, binary), not discrete taps. Up to 5 pointers.
- **Fast frame source** — MediaProjection capture after a one-time consent breaks the ~3 fps accessibility-screenshot limit (`/capture/*`). Enable it in-app: **Realtime fast capture**.
- **On-device reflexes** — stimulus→response rules run at frame rate in-process (`/reflex/*`), so the phone can react/fight without an AI round-trip per frame.
- **Binary WebSocket + live stream** — frames and touch skip JSON/base64; `stream_start` pushes binary JPEG frames.
- **Benchmark harness** — `python agent/connectwin_client.py bench --n 40` measures every path.

See **[REALTIME.md](REALTIME.md)** for the full API, reflex examples, and tuning tips. A desktop
`Connect-win-agent.exe` is built by GitHub Actions and attached to the latest release.

---

## 🚀 Setup on Your Phone (2 Minutes)

1. Download **`Connect-win-arm64.apk`** from [**Releases**](../../releases/latest) (or the latest GitHub Actions run artifact) and install it.
2. **Android 13–16 one-time unlock:**
   - Open **Connect-win** → tap **"Open Connect-win App Info"** → tap **⋮ (top-right)** → **"Allow restricted settings"**.
3. In **Connect-win**, tap **GRANT** on:
   - **1. Accessibility Service** *(turn ON Connect-win Bridge Control)*
   - **2. Notification Access**
   - **3. All Files Access**
   - **4. Unrestricted Battery**
   - *(Optional)* **5–8** for Overlay, Brightness, APK Installs, GPS/Camera/SMS/Calls.
4. Tap **Start Bridge & Tunnel** → wait ~3 seconds for `https://….trycloudflare.com` → tap **Copy URL + Token** and paste into your agent chat!

---

## ⚡ Agent Speed Cheat-Sheet

```bash
H="X-Bridge-Token: <TOKEN>"; U="https://<TUNNEL>.trycloudflare.com"

# 1. Full device + permissions + battery + foreground app in one call
curl -s -H "$H" "$U/status"

# 2. Read live screen DOM (buttons, texts, inputs + ref IDs — ~2 KB, 10 ms!)
curl -s -H "$H" "$U/ui/state"

# 3. Execute a whole multi-step mobile workflow in ONE round-trip
curl -s -X POST "$U/gui/batch" -H "$H" -H "Content-Type: application/json" -d '{
  "steps": [
    {"op": "open", "target": "whatsapp"},
    {"op": "wait", "text": "Search", "timeout_ms": 5000},
    {"op": "ui_click", "text": "Search"},
    {"op": "set_text", "text": "Notes", "submit": true},
    {"op": "sleep", "ms": 500},
    {"op": "shot", "max_width": 540, "format": "jpg", "quality": 70}
  ]
}'
```

---

## 🔒 Transparency & Safety

- Binds locally to `127.0.0.1:8765` behind constant-time `X-Bridge-Token` authentication.
- Shows a **persistent Foreground Notification** whenever active with a **1-Tap STOP** kill-switch.
- Logs every request to the in-app **Live Audit Log**.

## Experimental persistent control

The authenticated WebSocket endpoint `/ws` accepts an `X-Bridge-Token` header; browser-origin connections and URL tokens are rejected. It offers JSON operations `ping`, `ui_state`, `shot` (JPEG, base64), and `batch` using the same ordered steps as `/gui/batch` (maximum 100). A token rotation invalidates subsequent commands on existing sockets. Example client: `agent/fast_bridge.py` (requires `websocket-client`). Use only on a trusted device and do not share the token. Screenshots are on-demand, not a video stream. Continuous real-time play and latency improvements have not yet been verified on a device.

A `timed_gesture` batch step accepts up to 10 strokes with `x`, `y`, optional `x2`, `y2`, `start_ms` and `duration_ms`. Strokes may overlap, and the whole gesture is limited to 10 seconds. Android or the target app may cancel gestures; verify outcomes before using them in a longer sequence.

### Reliable UI actions (1.2)

`GET /ui/state?full=true` includes labels up to 4096 characters and additional entries; default remains compact. `screen` is physical display pixels, including system bars, matching screenshot and gesture coordinates. UI refs are snapshot references: a ref is re-identified against a fresh tree and rejected if moved, changed or ambiguous; get a fresh state before acting. For `/ui/click` or a batch `ui_click`, optionally set `method` to `gesture` or `accessibility` (default `auto`), and supply `expect_text` plus optional `expect_timeout_ms` to check a visible result. `accepted` means Android accepted the input, not that the app navigated; `verified` means the requested text was subsequently observed. A failed verification never automatically repeats an action. Text already present before the click is not proof of a transition. WebView and system overlays can still cause stale data; confirm sensitive actions manually.

### Arena completion sound (v1.3.0)

Connect-win can show a sound-enabled Android notification after it observes an Arena Agent response finish. The switch **Notify when Arena finishes** is enabled by default and can be turned off in Connect-win; use **Test notification sound** to check your Android notification settings. Enable Connect-win's accessibility service and notification permission/channel. Sound and vibration follow Android's volume, Do Not Disturb, and channel settings.

This is a best-effort on-device accessibility heuristic, not an Arena API. Keep the Arena agent page visible in Chrome while a response is running. It requires observing the page's `Stop generating` control followed by the same page's composer without that control for at least three seconds. It does not track background tabs, another app, or a locked screen; it can miss transitions or notify when a response is stopped or paused for a choice. It reads the visible Chrome address and the two composer control labels; it does not save or transmit conversation text for this feature. Arena/Chrome interface changes may break detection. The test button tests Android notification delivery, not the end-to-end Arena detector.

**Direct completion alert (v1.3.1):** An authorized bridge agent can call `POST /phone/notify-complete` with the normal bridge token as its final action. This posts the same Android notification without requiring Arena to be visible or the screen to be unlocked. It respects the Connect-win switch and Android notification settings and returns `posted: false` when disabled or posting fails; it cannot guarantee audible sound under silent mode or Do Not Disturb. The agent must explicitly call this endpoint; it is not an automatic server-side integration with Arena and cannot work when the bridge is disconnected. The optional visible-page heuristic remains a fallback and is suppressed briefly after a direct alert to avoid a duplicate.
