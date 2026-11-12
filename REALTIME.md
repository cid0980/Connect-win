# Connect-win Realtime Layer (v1.4)

Goal: make agent control feel **continuous and human-like**, and make the only
meaningful delay the **network** — not a screenshot→think→tap loop.

## Why the old loop was slow
- `AccessibilityService.takeScreenshot` is rate-limited to ~3 fps and each call is a
  full round-trip. "Screenshot → send to AI → decide → tap" therefore caps you at a
  few actions per second and adds the AI's latency to every step.
- Discrete `tap` gestures can't hold a finger down or drag smoothly, so games that
  need a joystick / aim / hold feel robotic.

## What v1.4 adds
1. **Continuous multi-touch** (`ContinuousTouchEngine`) — held fingers extended with
   `StrokeDescription.continueStroke`, coalesced moves, up to 5 pointers. Smooth
   drag / aim / joystick instead of tap-tap-tap.
2. **Fast frame source** (`ScreenCaptureService`) — MediaProjection + VirtualDisplay +
   ImageReader after a **one-time** on-device consent. Frames at display cadence
   (no 3 fps cap, no per-frame popup). Latest frame kept in `FrameStore`.
3. **On-device reflexes** (`ReflexEngine`) — stimulus→response rules evaluated against
   the latest frame **in-process at frame rate**. The AI configures rules once; the
   phone reacts locally with zero network/AI latency. This is the "auto-fight" path.
4. **Binary WebSocket protocol** — frames and touch skip JSON + base64 (base64 adds
   ~33% size). A live `stream_start` pushes binary JPEG frames continuously.
5. **Benchmark harness** — `agent/connectwin_client.py bench` measures RTT/throughput
   across every path so you can tune empirically.

> Honest limits: MediaProjection needs one consent per capture session and shows a
> persistent notification. Some games block screen capture (FLAG_SECURE) or detect
> synthetic input. Accessibility gesture cadence is ~30–60 Hz, not 120 Hz. Continuous
> multi-touch and game performance must be validated on your specific device/game.

---

## Enable fast capture (one-time)
1. Open **Connect-win** → **Realtime fast capture** → **Enable fast capture**.
2. Approve the Android screen-share prompt. A notification stays while it's active.
3. `GET /capture/status` should now report `running: true, source: mediaprojection`.

Since v1.5.0, full-frame JPEG `/screenshot`, `/gui/screenshot`, and `/shot` also use this
fast frame store (with accessibility fallback). Cropped or PNG requests still use the
accessibility screenshot path.

## Agent activity indicator (v1.5.0)
The bridge notification is an honest progress indicator, not a guess:
- `POST /agent/status {"state":"working|paused|idle|done","message":"...","ttl_ms":90000}`
- `GET /agent/status` → `{activity:{state,message,last_action_age_ms,last_request_age_ms,...}}`
- `working` expires after `ttl_ms` (default 90 s, clamped 5–300 s). The notification refreshes
  every 5 s and shows the last move/request age, so a dead agent cannot stay "working".
- The agent client can hold a heartbeat: `connectwin_client.py heartbeat --message "..." --interval 15`.

Without this, frames fall back to the rate-limited accessibility screenshot.

## Continuous touch
HTTP:
```bash
H="X-Bridge-Token: $BRIDGE_TOKEN"
# hold a finger, drag it, release — coordinates are physical screen pixels
curl -s -X POST "$BRIDGE_URL/gui/touch" -H "$H" -d '{"action":"down","pointer":0,"x":300,"y":1500}'
curl -s -X POST "$BRIDGE_URL/gui/touch" -H "$H" -d '{"action":"move","pointer":0,"x":500,"y":1400}'
curl -s -X POST "$BRIDGE_URL/gui/touch" -H "$H" -d '{"action":"up","pointer":0}'
# quick human-like press
curl -s -X POST "$BRIDGE_URL/gui/touch" -H "$H" -d '{"action":"press","x":540,"y":1200,"hold_ms":90}'
```
WebSocket (JSON): `touch_down` / `touch_move` / `touch_up` / `touch_press` /
`touch_cancel` / `touch_state` / `touch_tick`.
Binary (lowest latency): see `BinaryProtocol.kt` — DOWN/MOVE/UP are 2–6 bytes.

### Sequenced touch (v2) — consistent input/output
Every command carries a sequence number and gets an **ordered ack**, so the agent
always knows each action was accepted (no fire-and-forget). Use the persistent-WS
client:
```bash
# press-and-release, confirmed by acks
python agent/connectwin_client.py ws-touch press --x 540 --y 1200 --hold-ms 90
# smooth drag from (300,1500) to (700,900) in 24 steps, each acked
python agent/connectwin_client.py ws-touch drag --x 300 --y 1500 --x2 700 --y2 900 --steps 24
```
Binary v2 opcodes `0x10/0x11/0x12` (down/move/up) return a `0x90` ack
`[pointer][seq][status]` (0=queued, 1=invalid, 2=accessibility unavailable).

### v1.4.1 reliability
- **Press/release race fixed** — a delayed press-release can no longer lift a newer
  finger that reused the same pointer id (per-pointer generation).
- **Cached screen size** — a 60 Hz move stream no longer makes a WindowManager call
  per event.
- **Reflex backpressure** — per-rule coalescing + a bounded action queue, so a noisy
  rule drops stale triggers instead of growing latency (`coalesced_drops` in stats).
- **Shared frame encode cache** — a stream and an on-demand shot reuse one JPEG encode
  for the same frame/size/quality.
- **Protocol unit tests** — the binary wire format is covered by JVM tests in CI.

## On-device reflexes (auto-fight / auto-task)
Rules use **normalized 0..1 coordinates** by default so they survive resolution changes.
```bash
# When a pixel at (0.5,0.85) turns red-ish, tap the attack button at (0.9,0.8).
curl -s -X POST "$BRIDGE_URL/reflex/rules" -H "$H" -d '{
  "id":"attack",
  "condition":{"type":"pixel_color","x":0.5,"y":0.85,"color":"#ff3b30","tolerance":40},
  "action":{"type":"tap","x":0.9,"y":0.8},
  "cooldown_ms":300
}'
curl -s -X POST "$BRIDGE_URL/reflex/enable" -H "$H" -d '{"enabled":true}'
curl -s "$BRIDGE_URL/reflex/rules" -H "$H"     # list + fire counts
```
Conditions: `pixel_color`, `region_color` (fraction of a region matching a color).
Actions: `tap`, `key`, `swipe`, `touch` (down/move/up/press).
Because evaluation runs on the phone at frame rate, reaction time ≈ one frame, not a
network round-trip. Use the AI to *choose* rules and targets; let reflexes *execute*.

## Live binary stream
```bash
python agent/connectwin_client.py stream --fps 20 --max-width 480 --seconds 8 --outdir frames
```
Server pushes binary frames (`0x81` header + JPEG). Skips duplicate frames.

## Benchmark
```bash
export BRIDGE_URL=... BRIDGE_TOKEN=...
python agent/connectwin_client.py bench --n 40
```
Reports min/median/p95/max for: HTTP ping/status/ui_state, fast capture frames at
several widths, the accessibility screenshot baseline, and WS text/binary ping + shot.
Use it to compare Wi-Fi vs cellular and `http2` vs `quic` tunnel protocols.

## Network tips
- Keep **one persistent WebSocket** for the whole session (already the design).
- Prefer **binary** frames + touch over JSON/base64.
- Downscale (`max_width` 360–540) and lower JPEG quality (50–60) for speed.
- Let **reflexes** handle the fast loop; only escalate to the AI when rules can't decide.
- Tunnel protocol is configurable (`http2` / `quic`); benchmark both on your network.

## Desktop agent executable
GitHub Actions (`.github/workflows/build-agent-exe.yml`) packages
`agent/connectwin_client.py` into `Connect-win-agent-windows.exe` (and a Linux binary)
with PyInstaller and attaches them to the `latest` release. Run:
```
Connect-win-agent-windows.exe bench --n 40
Connect-win-agent-windows.exe stream --fps 20 --seconds 10
```
