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

---

## Appendix — tunnel reliability, measured

Between 11 and 13 Nov 2026 the Quick Tunnel was used continuously for roughly six hours
of play and development. It went down **five times**, and the failure modes are worth
recording because they cost real time each occurrence:

| # | Symptom | Cause | Recovery |
|---|---|---|---|
| 1 | HTTP 530, error 1033 | `cloudflared` process gone | new URL from phone |
| 2 | HTTP 530, error 1033 | app restarted | new URL from phone |
| 3 | HTTP 530, error 1033 | app restarted | new URL from phone |
| 4 | name resolution failure | tunnel record removed | new URL from phone |
| 5 | HTTP 530, error 1033 | app restarted | new URL from phone |

Each recovery required a human to open the app and copy a new URL, because a Quick
Tunnel mints a fresh hostname every time. Two of the five happened mid-task, which means
any long-running operation through this bridge must assume the control plane can vanish
without warning.

This is not a defect in the bridge code — it is the documented behaviour of Quick
Tunnels, and `bridge-observations.md` §7 already predicted it. It is listed here because
it dominates the practical experience: **a named tunnel with a fixed hostname is the
single highest-value operational change available**, ahead of any latency work. With a
stable hostname only the token would need rotating, and an agent could reconnect
unattended.

Mitigation implemented: `capture.wanted` persists the operator's intent, so after a
restart an agent can see that fast capture *should* be running and that consent was lost,
instead of silently falling back to slow screenshots.

## 8. What the reflex engine could not express (fixed in 1.6.5)

An audit of the shipping code against Genshin-class input found the reflex engine had one
action shape — tap — and no notion of a press ending. Every real-time input in the game is
hold-and-release: glider deployment, charged attacks, hold-to-sprint, Sumeru puzzles. Three
concrete consequences:

**A. No release.** A rule that pressed `down` had no path to `up`. The character runs into a
wall and nothing in the system knows why. The screen keeps moving, so it does not look like
a failure — the worst kind of bug.

**B. No watchdog.** Nothing bounded hold duration. A rule gated on "boss is charging" whose
pixel scrolls off-screen leaves the screen held down indefinitely.

**C. Fixed reflex rate.** The publish rate was set once at service start, and reflexes are
evaluated once per *published* frame — so the published rate is the reflex rate. Measured 8
fps in practice means a worst-case 125 ms of detection latency before any input is even
attempted, on top of the game's own frame time.

Fixed:

- `on_release` on a rule — a second condition/action pair evaluated once the press condition
  stops holding. This turns one rule into a hold-and-release behavioural primitive.
- `max_hold_ms` — a watchdog that force-releases after the ceiling regardless of conditions.
  Deliberate: no rule should be able to hold the screen forever.
- `releases`, `watchdog_releases` and `armed` in `/reflex/rules`, so a stuck press is
  visible rather than mysterious.
- `POST /reflex/release_all` — panic release, independent of capture and rules being healthy.
  This is the "return the phone to a safe state" call.
- `POST /capture/fps {fps}` — runtime reflex-rate control, clamped 1..60, no restart.
- `release`, `release_all` action types.

The design principle worth keeping: **any capability that can leave hardware in a state the
agent cannot observe needs a blind, unconditional way out.** Screenshots show what the phone
is displaying, not what it thinks is being touched.

## 9. Detection latency budget (partially measured)

| Stage | Known | Status |
|---|---|---|
| remote round trip | 350–800 ms | measured |
| published frame rate | 8 fps after restart, 0.6 fps when stalled | measured |
| detect at 8 fps | up to 125 ms | derived |
| on-device evaluate + dispatch | not yet measured | **open** |
| game's own frame time | unknown for Genshin | **open** |

Genshin target is roughly a 200 ms reaction for a dodge to feel intentional, so the open
rows decide feasibility. The 8 fps figure was measured on a menu-based 2D game idling; it is
not evidence of what a 3D game at load will do, in either direction.

## 10. Tunnel failure during transfer, not just over time

The reliability appendix counted tunnel deaths as a time-based phenomenon: quick tunnels
expire between sessions, restart, repeat. The v1.6.5 install showed a second, sharper mode.

```
POST /files/upload   19,393,142 B   -> 502 Bad gateway mid-body
10 s later           /health         -> 530
five retries over 40 s               -> 530, 530, 530, 530, 530
```

The bridge did not come back on its own. It needed a manual restart on the phone. So a
single large upload did not merely fail — it **took the bridge offline** and needed a human.

That inverts the priority of the whole install sequence. Installing is already the step that
must happen last (it kills the bridge by design), and now the transfer that feeds it is the
most fragile call the bridge makes. Combined:

- largest payload in the system (19 MB)
- most likely to fail
- cannot be cheaply retried
- failure mode is losing the only channel you have

The fix is to stop pushing bytes through the tunnel. `POST /files/fetch {url, path}` pulls a
file server-side from any https URL, so the tunnel carries a few hundred bytes each way and
the phone talks to a public host directly. It also verifies `Content-Length` and treats a
short read as a failure and deletes it — a truncated APK still looks like a successful
download and later fails to install for a reason that looks like signing.

`/files/upload` is kept and hardened (`.part` file + rename, no half-written destination,
optional `size` check), but for anything large, fetch beats push.

Generalisation: **any bulk transfer should be pull, not push.** The tunnel is a control
channel, and it should only ever be asked to carry control-sized messages.

## 11. MEASURED ON GENSHIN IMPACT — the target capability, end to end

First contact with the actual target game (`com.miHoYo.GenshinImpact`), on the reference
device, with v1.6.9.

### It works

The bridge controlled a real-time 3D game. Character movement via **continuous touch** — a
held pointer on the left thumbstick at physical (280,800) with periodic `move` events —
walked the character from the "Clear Pool and Mountain Cavern" doorway into open terrain. The
joystick was visibly displaced and the camera/character state changed completely. This is the
first evidence that the control primitives suit a real-time action game rather than menus.

Genshin renders **landscape** (2400x1080) while the device is portrait, and reports it
correctly in `/ui/state` `screen`. Any coordinate reasoning has to come from the live screen
size, never from a portrait assumption.

### The perception numbers are bad, and worse than predicted

Prediction before testing was that a constantly-repainting 3D game would deliver *more*
frames than a menu game that repaints only on state changes. **The opposite happened.**

| Condition | frames arrived | published | source_fps |
|---|---|---|---|
| Genshin idle, static scene | 1.00 /s | 0.50 /s | 1.7 |
| Genshin, joystick held (moving) | 1.38 /s | 0.50 /s | 1.7 |
| Rent game idle (earlier) | ~1.4 /s | — | 1.6–2.2 |

Moving the character — a full-screen, constantly changing 3D render — raised arrival from
1.00 to 1.38 frames per second. That is the whole result. `image_available` (frames the
VirtualDisplay handed to ImageReader) grows by 1–3 per second, `convert_failed` is 0, and
`throttle_skipped` barely moves, so **the bottleneck is upstream of this app's code**: the
VirtualDisplay is not delivering a real gameplay frame rate.

`POST /capture/restart` did not fix it (published 0.25 -> 0.75/s), unlike the 1.6.1 stall
where restart restored 0.6 -> 8 fps. So this is a different phenomenon: not a stalled
pipeline that a restart unblocks, but a hard cap on what the capture path receives.

### Frame fetch latency, and where the time actually goes

Full round trip from here to the phone and back, live Genshin scene:

| Payload | p50 |
|---|---|
| 720px q70 (42,623 B) | 319 ms |
| 540px q70 (25,214 B) | 425 ms |
| 360px q50 (9,715 B) | 325 ms |
| 240px q40 (4,968 B) | 315 ms |
| 180px q30 (2,867 B) | 297 ms |

A 15x reduction in bytes buys ~30 ms. **Latency is fixed overhead, not bandwidth** — the same
conclusion as the earlier Rent-game measurement, now confirmed on the target game. There is no
payload size at which this becomes real-time.

### End-to-end reaction time in Genshin

Tap, then poll until the returned frame differs:

```
trial 1: change seen at 2944 ms
trial 2: change seen at 2684 ms
trial 3: change seen at 1247 ms
```

Even accounting for polling granularity, **~1.2–2.9 s from tap to observable change.** At
~1.4 frames/s arriving, the next frame after any action is on average ~350 ms away and can be
~700 ms; the tap itself must survive the tunnel (~300 ms each way), which is ~600 ms before
the gesture is even dispatched.

### Verdict against the stated goal

The goal was "seamless like a human using the mobile", with Genshin as the capability ceiling.
The honest assessment, per layer:

| Layer | Verdict |
|---|---|
| Perception of menus/static UI | **fine** — 300 ms round trip is ample |
| Control primitives (tap, hold, joystick, multi-touch) | **fine** — proven working in a 3D game |
| Perception of real-time 3D | **not usable** — 1.4 fps, ~1.2–2.9 s to see one's own action |
| Remote reactive play | **impossible** — a dodge needs ~200 ms, the loop is ~1500 ms |
| On-device reflex rules | **the only viable path**, and currently starved of input |

The reflex engine was the right architecture. It is being fed 1.4 frames per second, so a
rule that must react within 200 ms is working from data up to 700 ms stale before it even
evaluates. **No amount of rule sophistication fixes a starved sensor.**

### CORRECTION (same day): the 1.4 fps figure was wrong

An earlier version of this document concluded Genshin delivers ~1.4 frames/s even while
moving, and that restart does not help. **That was a measurement error, and it mattered.**

Re-measured with the device awake, Genshin in the foreground and reconnected, and capture
started by the operator:

| Test | Result |
|---|---|
| joystick held, 12 s | **7.42 fps arriving** |
| idle, no input, 12 s | **8.42 fps arriving** |
| A/B native 2400 vs 720, joystick held, 4x15 s | 7.60 / 7.47 / 6.40 / 6.40 fps |
| sustained 1080, 3 minutes | 8.35, 9.20, 9.70, 9.55, 9.45, 9.60, 8.30, 9.20 fps |
| sustained 1080, frame_age | **p50 66 ms, p95 601 ms** |
| sustained 2400, frame_age | p50 197 ms, p95 379 ms |

So Genshin runs around **9 fps**, essentially the same as the 2D Rent game's 8 fps. The
"3D games are much worse" hypothesis is dead, and so is "capture width is the bottleneck" —
native 2400 and 720 deliver the same rate, so the VirtualDisplay is not scaling-bound.

The 1.4 fps readings came from a device that had been sitting with a dead capture session
(`running:false`) and a backgrounded game; `/capture/status` kept reporting the *last* counter
values it held, which looked like a live but starved pipeline. This is the third instance of
the same class of error in this project: **a stale sensor read as a live one.** Hence the
fix list below.

### What is actually true

1. **~9 fps arrives at any capture width.** fps=30 is never reached, but 9 is enough for
   on-device reflexes at a 110 ms detection cadence.
2. **`published` is roughly half of `arrived` (~4.4/s).** The app drops every second frame
   somewhere between acquire and publish. Unknown cause, and it is free performance.
3. **frame_age p50 is 66 ms**, so the frame store is fresh and usable for reflexes. The p95 of
   601 ms reflects static periods where the display sends nothing — which is normal and is
   already what `slow_source` describes.
4. **Bitrate at 720 is ~20-25 KB/frame**, so 9 fps is ~200 KB/s. The tunnel cannot carry that
   continuously, but it does not need to: reflexes run on-device and only decisions cross.

### Verdict, revised

| Layer | Verdict |
|---|---|
| Perception of menus/static UI | fine |
| Control primitives in a 3D game | **proven working** |
| Perception of real-time 3D | **~9 fps, p50 frame_age 66 ms — workable for on-device reflexes** |
| Remote reactive play | still impossible: ~1500 ms round trip measured |
| On-device reflex rules | **viable, and now fed properly** |

The architecture was right. My measurement was wrong, and I nearly wrote off the target
because of it.

### The open engineering question

Why does the VirtualDisplay deliver ~1.4 fps in Genshin? Candidates, in order of likelihood:

1. The virtual display is a *second* display compositing a landscape game while the phone is
   physically portrait, and is being throttled.
2. MediaProjection is de-prioritised while a GPU-saturated 3D game has the foreground.
3. `max_width` 720 on a 2400x1080 landscape surface forces an expensive scale on the
   producer side, so the *producer* drops frames before ImageReader ever sees them.

Candidate 3 is testable and cheap: re-measure arrival rate at `max_width` 1600/1080/native
instead of 720. If arrival climbs with a larger requested width, the scaling is the cause and
the fix is to capture at native and downscale on the consumer side. That is the next
measurement to run, and it decides whether on-device reflexes have any future at all.

## 12. Screen-capture consent cannot be granted remotely (permanent constraint)

After every app update, `POST /capture/status` reports `running: false, wanted: true` — the
MediaProjection grant is gone, because updating kills the process and Android never persists
that permission across processes.

Attempted to restore it without a human:

```
POST /apps/launch com.cid0980.connectwin            -> app foregrounded
POST /ui/click {"text":"Enable fast capture"}       -> ok:true, accepted:true
    matched: Button, clickable:true, bounds [96,1244,984,1388], center [540,1316]
POST /ui/state                                      -> no system dialog appeared
POST /capture/status                                -> running:false, wanted:true
```

The click landed on the right control and was delivered through a real accessibility action.
**No consent dialog was raised.** Android protects `createScreenCaptureIntent()` from synthetic
input — accepting screen capture requires a genuine human gesture on the device. This is not a
bridge limitation and cannot be worked around by the bridge, by a different input modality, or
by root-free trickery.

Consequences worth planning around:

1. **Every update costs a human tap.** Not just the install confirmation — the capture consent
   too. So each version bump is a two-step manual tax, and shipping many small versions is
   actively worse than batching changes.
2. Degraded vision still works while consent is missing: `/screenshot` uses the accessibility
   screenshot path at roughly 1 frame every 3 s, so an agent can still see menus. What is lost
   is the frame store that reflexes read — i.e. exactly the fast path this project exists for.
3. Any long-lived deployment needs to treat capture consent as an **operator action on
   startup**, listed in the setup checklist next to the accessibility toggle.

Same family as the accessibility rebind in `docs/MEASUREMENTS.md` section 6: the two things
that actually require a human are **binding input** and **granting sight**. Everything else is
automatable.
