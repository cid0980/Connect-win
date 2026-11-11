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
