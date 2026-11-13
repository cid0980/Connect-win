# Making Connect-win seamless — and fast enough for Genshin

Design notes, 12 Nov 2026. Written after ~6 hours of real play through the bridge
(see `/home/user/FRICTION.md`) and a read of every file under `realtime/`.

This is an architecture argument, not a wishlist. The point is that **"seamless like a
human" and "can play Genshin" are the same requirement stated twice**: both need the
control loop to close in tens of milliseconds, and neither can be achieved by making the
remote agent faster. The only way is to move the loop onto the phone.

---

## 1. The verdict, in one number

| Path | Realistic round trip | What it can do |
|---|---|---|
| Agent → Cloudflare → phone → agent (today's default) | **350–800 ms** | turn-based, menus, puzzle, navigation |
| Agent → LAN → phone | ~20–40 ms | still not 60 Hz once you add model inference |
| **On-device reflex loop** (same process as capture) | **16–50 ms** | real-time action |

A human reacts to what they see in about 250 ms, but that number is not the target. The
target is that **the phone keeps responding while the agent is thinking**. Genshin combat
is a rhythm: you tap attack every ~300 ms, dodge on a telegraph that lasts ~400 ms. If a
dodge decision has to cross an ocean twice, you lose the trade every time.

So: an agent cannot *play* Genshin. An agent can *write a policy* that the phone plays.
Everything below follows from that.

---

## 2. What the signal path actually costs today

Measured this session unless noted.

| Stage | Cost | Source |
|---|---|---|
| JPEG encode @720×1600 q70 | ~25–40 ms | inferred from frame rate ceiling |
| HTTPS request+response via CF quick tunnel | 331 ms median WS ping (repo notes), 365 ms HTTP median | `bridge-observations.md` §6 |
| 540 px JPEG fetch round trip | **760 ms** | `bridge-observations.md` §6 |
| Frame publish rate | **1.3 fps** observed pre-`F1` fix | measured live |
| `dispatchGesture` cycle | ~16 ms + callback, best case | `ContinuousTouchEngine` |

Three conclusions:

1. **The vision loop is the bottleneck, not the touch loop.** Even at a perfect 30 fps
   capture, the agent sees a new frame every ~760 ms through the tunnel. Any design where
   the decision depends on an image crossing the network is dead on arrival.
2. **`F1` (ImageReader leak) is currently costing ~23× the achievable frame rate.** That
   single bug is the difference between 1.3 fps and something usable. It is not a
   polish item; it is the floor of the whole capability.
3. **`F2` (held fingers) is a hard gate.** Without a finger that stays down, there is no
   joystick, no charged attack, no aim. Genshin's entire movement model is "hold and drag".

---

## 3. The right split

```
   ┌─────────────────────────── PHONE (same process) ───────────────────────────┐
   │                                                                            │
   │  ImageReader ──► FrameStore ──► ReflexEngine ──► TouchExecutor ──► game      │
   │      30 Hz           │              │  16 ms budget     │                    │
   │                      │              │                   │                    │
   │                 ROI rings      rule groups          single writer            │
   └──────────────────────┼──────────────┼───────────────────┼────────────────────┘
                          │              │                   │
                     full frame     telemetry only       policy upload
                     on demand      (counters, state)     (rare, human-authored)
                          │              │                   │
   ┌──────────────────────▼──────────────▼───────────────────▼────────────────────┐
   │  AGENT (remote): authors policies, reads telemetry, handles novel situations │
   └─────────────────────────────────────────────────────────────────────────────┘
```

The agent's job shrinks from "press buttons 4 times a second" to "write a rule set, watch
counters, intervene when something unexpected happens". That is a job that survives a
760 ms round trip, and it is genuinely what makes the bridge feel seamless: *you give it
an intent and it executes at frame rate*, instead of you babysitting a coordinate stream.

---

## 4. The gaps, ranked by what they unblock

### G1 — No latency instrumentation anywhere (do this first)

**Missing.** There is `frame_age_ms` on `/status`, and nothing else. You cannot currently
answer "how long after my tap does the screen change" — which means every claim about
whether the bridge is fast is a guess. My own session contains a worked example of the
cost: I concluded "BACK does nothing" from a frame that was 1–6 s stale, and burned 25
minutes on a screen one keypress would have left.

**Why it blocks Genshin.** You cannot tune a 16 ms loop without being able to see a
histogram of where the 16 ms went.

**Fix.** Add a single `/bench/latency` route and four timestamps per frame:

```
capture_ts   (uptimeMillis at ImageReader.acquireLatestImage)
publish_ts   (frameStore.publish)
dispatch_ts  (dispatchGesture called)
seen_ts      (first frame whose region actually changed after dispatch)
```

Report `p50/p95/max` for `capture→publish`, `publish→dispatch`, and
`dispatch→seen`. That last one — **tap-to-visible-change** — is the number that decides
whether Genshin is playable, and right now nobody on this project knows its value.

**Acceptance:** `/bench/latency?n=50` returns percentiles; the values stay stable across
three consecutive runs; and `dispatch→seen` is reported honestly even when it is 400 ms.

---

### G2 — `onTickDone` throws away every finger on any cancellation

**Missing.** In `ContinuousTouchEngine`:

```kotlin
if (!completed) {
    // Gesture cancelled by the system/app: drop all fingers
    pointers.clear()
    running = false
    return
}
```

This is the correct defensive default for a phone-control bridge (never leave a stuck
touch on someone's device). It is exactly wrong for a game, where **cancellation is
routine and per-pointer**: Android cancels a gesture when the app consumes the touch, when
a window changes, when an overlay appears. One cancelled stroke currently drops *all*
pointers — so a two-finger aim-while-strafing setup collapses the moment one finger's
stroke ends.

**Why it blocks Genshin.** Every real combat input is at least two fingers and the game
constantly reclaims touches.

**Fix.** Distinguish the two cases:
- **Pointer release** (`willContinue == false`) → remove only that pointer from the map.
- **Genuine system cancel** of a stroke → mark *that pointer* stale and re-`moveTo` it on
  the next tick with a fresh `StrokeDescription`, rather than clearing the map.

Keep the safety valve, but scope it: if **every** pointer cancels within one tick, that is
a real interruption — then clear everything and set a `last_cancel_reason` the agent can
read. Also surface `cancels_total` / `cancels_per_pointer` in `stateJson()`; today
cancellation is invisible, and an agent cannot tell "my dodge didn't land" from "the game
ate my touch".

**Acceptance:** hold two fingers for 10 s while an overlay opens and closes; expect
`pointers` to fall to 1 for the affected pointer only, and the survivor to keep
`held_ms` climbing past 10 000.

---

### G3 — Reflex runtime is too thin for a game (biggest capability gap)

**Missing.** `ReflexEngine` supports exactly two condition types — `pixel_color` and
`region_color` — with 10×10 point sampling. Actions are `tap`, `key`, `swipe`, `touch`.
The unit of evaluation is a single pixel comparison, evaluated against the full bitmap via
`FrameStore.use { }`.

What this cannot express, and what Genshin needs:

| Need | Today | Required |
|---|---|---|
| "is the attack button glowing" | pixel_color at one point | colour **ratio over a box** (`region_ratio`) |
| "is that a health bar at 20%" | — | **template match** on a small bitmap (`template`) |
| "did I leave the menu" | — | **region_diff** vs a reference frame |
| "enemy visible → attack, else walk" | — | **rule groups / modes** with an active group |
| "attack 3× then dodge" | — | **sequence** with per-step timing |
| "HP low OR shield up" | — | **combinators** (`all_of`, `any_of`, `not`) |

**Also missing:** `onFrame()` allocates a filtered `List` per frame and calls
`store.use { }` once **per rule** — with 64 rules that is 64 bitmap accesses per frame on
the capture thread. The comment says "keep it cheap"; it isn't. Copy the frame once per
tick, then evaluate all rules against that copy.

**Fix — minimum viable reflex runtime:**

```kotlin
condition: { type: "region_ratio", region: [x,y,w,h], color: "#ff3020",
             tolerance: 30, min_ratio: 0.25 }
condition: { type: "template", region: [x,y,w,h], ref: "<ref_id>",
             threshold: 0.85 }                     // simple NCC on downscaled crop
condition: { type: "region_diff", region: [...],
             ref: "<ref_id>", max_changed_ratio: 0.05 }
condition: { type: "all_of"|"any_of", conditions: [...] }

rule: { id, group: "combat", condition, action, cooldown_ms, max_fires }
groups: { active: "combat" }                        // only one group evaluates
```

Plus `POST /reflex/frame` to capture a named reference crop on demand, so an agent can
teach a template by *looking at the screen once* instead of hand-specifying pixels.

**Acceptance:** a rule set can drive "tap attack when the red HP sliver appears, otherwise
hold forward" for 60 s with zero agent involvement, and `/reflex/rules` reports
`evaluations` climbing at ≥20/s while `fires` stays sane.

---

### G4 — No macro / sequence scheduling

**Missing.** There is no way to say "in 300 ms, do this". Every action is maximally eager,
gated only by `cooldown_ms`. Game inputs are *rhythmic* — a 3-hit combo, a charged shot, a
dodge cancelled into an attack.

`/jobs/start` exists but shells out to a process; it is not a timing primitive.

**Fix.** Add a macro layer to the reflex runtime:

```json
{ "id": "combo", "steps": [
    { "at_ms": 0,   "action": { "type": "tap",   "x": 0.86, "y": 0.84 } },
    { "at_ms": 280, "action": { "type": "tap",   "x": 0.86, "y": 0.84 } },
    { "at_ms": 600, "action": { "type": "touch", "op": "down", "pointer": 1, "x": 0.8, "y": 0.9 } },
    { "at_ms": 900, "action": { "type": "touch", "op": "up",   "pointer": 1 } }
] }
```
Fired by a rule (`"action": { "type": "macro", "id": "combo" }`), executed on the same
handler thread with absolute deadlines so drift doesn't accumulate.

**Acceptance:** a 4-step macro with declared timings executes within ±15 ms of each
`at_ms` as reported by a `macro_log` of actual dispatch timestamps.

---

### G5 — Touch and sight are separately addressable, so nothing waits

**Missing.** `/gui/touch` returns `{"ok": true}` meaning "Android accepted the intent".
`/gui/click` same. Nothing in the API tells you whether the *screen changed*. I spent much
of one session fighting this: `ok:true` from a tap that landed on a loading overlay is
indistinguishable from a successful tap.

This is already item (1) in `bridge-observations.md`, and it is the single most common
cause of automation that "works" but wanders off.

**Fix.** Three related primitives, all cheap once G1 lands:
- `verify: true` on `/gui/click` and `/gui/touch` → after the gesture, wait ≤250 ms for a
  frame whose changed-pixel ratio exceeds a threshold, return
  `{"effect": "changed"|"unchanged", "changed_ratio": 0.12}`.
- `/ui/wait?effect=change&timeout_ms=2000` → block until the screen settles. Replaces
  fixed `sleep()` in every script, including mine.
- `/gui/safeclick` already hints at this in the route table — make it the *default*.

**Acceptance:** tapping a known-dead region returns `unchanged`; tapping "claim now"
returns `changed`; no false `unchanged` on a genuine transition.

---

### G6 — Streaming is all-or-nothing

**Missing.** The hot path is "full frame, every frame" — `S_FRAME` carries a whole
`w×h` JPEG. At 720×1600 q70 that is ~40–80 KB, so 30 fps is 1.2–2.4 MB/s. On the tunnel
that is not happening, and the agent doesn't need it: 95% of the screen is irrelevant
between decisions.

**Fix — three-tier streaming:**
1. **Telemetry tier (always on, ~200 B/s):** `seq`, `frame_age_ms`, reflex `fires`,
   `pointers`, `cancels`, foreground package. Enough for an agent to know what is
   happening without an image.
2. **ROI ring:** agent declares up to 4 named boxes. Each frame, ship downscaled crops of
   just those boxes (e.g. HP bar, attack button, minimap). ~2–6 KB/frame, and it makes a
   30 fps stream viable over the tunnel.
3. **Full frame:** on demand, or on a change trigger (`changed_ratio > 0.3`).

New frame message type:
```
0x82 ROI_FRAME [type][seq][slot:u8][x:u16][y:u16][w:u16][h:u16][jpeg...]
```

**Acceptance:** with two ROI slots registered, a 30 s stream delivers ≥25 fps to the
client while total bytes stay under 250 KB/s.

---

### G7 — The tunnel is a single point of failure (blocks everything above)

**Missing.** Quick tunnels mint a new hostname on every app restart. This session it died
**four times**, twice mid-task, and the last one vanished from DNS entirely. Every death
cost a full stop-and-wait on you pasting a new URL. `bridge-observations.md` §7 already
flags this; it is now the top practical blocker, because a real-time reflex session is
worthless if the control plane evaporates underneath it.

Also note: **`capture` was `running:false`** on this session's fresh bridge — fast capture
is not restored automatically after a restart, so an agent that assumes it is on will
silently run at the slow path.

**Fix.** Named tunnel (stable hostname, `cloudflared tunnel run`) + persist the token;
auto-start capture on bridge start; expose `capture.wanted` vs `capture.running` so a
mismatch is visible rather than silent.

**Acceptance:** kill and restart the app; the agent reconnects to the **same** URL with no
human input, and `/capture/status` reports `running:true` without a `/capture/start`.

---

### G8 — No supervision, no game-aware recovery

**Missing.** If the game crashes, gets backgrounded by a notification, or lands on an
unexpected screen, nothing notices. (My furniture-editor detour was recoverable by one
BACK — but only because *I* was watching. An unattended session would have sat there.)

**Fix.** A supervisor in the reflex loop: every 2 s check `foreground_package`; if it left
the expected package, `POST /apps/launch` it and log `recovered_at`. Add a
`home_screen_ref` template rule so an unexpected screen can be detected and handled with a
declared escape (`key: back` ×N, then relaunch).

**Acceptance:** force-stop the game mid-session; within 5 s the bridge relaunches it and
telemetry shows `recoveries: 1`.

---

## 5. What *not* to build

- **Do not build a remote 60 Hz vision loop.** It is arithmetically impossible over this
  tunnel and it will always feel laggy no matter how much the code is optimised. This is
  the trap the current design is closest to falling into.
- **Do not build an LLM-in-the-loop controller.** Model inference is 300 ms+ before you
  add the network. Useful for *authorship* (write the rule set, debug the odd screen),
  useless as a per-frame driver.
- **Do not add more one-off HTTP routes for gestures.** `/gui/click2`, `/gui/safeclick`,
  `/gui/longpress`, `/gui/pinch`, `/gui/scroll` is already six ways to press a point. Fold
  them into `tap`/`gesture` with flags, or the surface becomes unusable.
- **Do not "fix" the screenshot cache.** I already wrongly proposed this once; the
  identical-bytes behaviour was correct.

---

## 6. Honest limits — Genshin specifically

These are policy and platform facts, not engineering gaps:

1. **Anti-cheat.** Genshin runs kernel-level anti-cheat on PC and integrity checks on
   mobile. Injecting touches via AccessibilityService is visible to the app; automated
   play risks a ban. This should be a deliberate, informed decision, and it is the one
   part of the "can it play Genshin" question that code cannot answer.
2. **`dispatchGesture` is not a mouse.** It is sampled by the app; it cannot exceed the
   app's own input cadence, and Android may coalesce or cancel. Even a perfect reflex loop
   will feel slightly "soft" compared to a real finger. Expect competent, not competitive.
3. **Accessibility overlays and calls interrupt.** A real incoming call killed my session
   twice. A reflex session should declare how it handles interruptions rather than
   assuming uninterrupted play.
4. **Battery and thermals.** 30 fps MediaProjection + continuous gesture dispatch will heat
   the phone; sustained play will throttle, which changes the frame rate the reflex rules
   were tuned against. Rules should read `frame_age_ms` and degrade rather than misfire.

Realistic ceiling after G1–G8: **turn-based and menu games are genuinely seamless;
Genshin is playable at roughly "competent novice" level for scripted, predictable
encounters** — farming routes, repeated domains, resource collection — and not viable for
reaction-critical PvP.

---

## 7. Sequence

| Milestone | Contents | Unblocks |
|---|---|---|
| **M0** (hours) | `F1` + `F2` fixes, `G1` bench, `G7` capture auto-start | knowing whether any of this is true |
| **M1** (1–2 days) | `G5` verify/wait, `G2` per-pointer cancel | trustworthy automation; the "wandered off" class of bug dies |
| **M2** (3–5 days) | `G3` reflex runtime + `G4` macros | real-time games at all |
| **M3** (3–5 days) | `G6` ROI streaming + telemetry tier | remote agent that doesn't need frames to stay in the loop |
| **M4** | `G8` supervision, named tunnel | unattended sessions |

M0 is small and decides everything. My strong recommendation: **do M0, re-run the latency
bench, and only then decide how far to go** — because if `dispatch→seen` comes back at
300 ms even on-device, the honest answer for Genshin changes and the effort should go into
turn-based seamlessness instead.
