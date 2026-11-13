# Measured results — Connect-win v1.6.1 on OPPO CPH2219 (Android 13)

Taken 13 Nov 2026 on device, through the Cloudflare Quick Tunnel. These are the first
real numbers this project has had; everything before was estimation.

## Headline

| Metric | Value | Meaning |
|---|---|---|
| `capture→publish` | **19 ms p50**, 37 ms p95 | Bitmap conversion is cheap. Not the problem. |
| **`dispatch→seen`** | **258 ms**, then **768 ms** | The number that matters. See below. |
| Published rate after `/capture/restart` | **8 fps** | vs 0.6–1 fps when the pipeline had degraded |
| `frame_age_ms` after restart | **63 ms** (from 6348 ms) | |
| Round trip via Quick Tunnel | ~760 ms for a 540 px JPEG (repo's own figure) | network, not code |
| `verify=1` verdicts | correct in 5/5 cases | see "verify works" below |

## 1. The pipeline degrades, and a VirtualDisplay recreate fixes it

Sequence observed in one session, same app, same screen:

```
before restart   image_available/s = 0.60   acquired/s = 0.00   published/s = 0.00   age = 6348 ms
POST /capture/restart
after restart    seq 322 -> 362 over 5 s   = 8.00 fps          age = 63 ms
```

`throttle_skipped: 0` and `convert_failed: 0` throughout, so this is **not** the fps cap
and **not** conversion. The virtual display simply stops delivering frames after a while
and recreating it restores flow. The new `diag` counters are what made this visible —
previously `running: true` with a frozen `seq` looked identical to a static screen.

**This is now recoverable remotely**, which matters because MediaProjection consent is the
one step an agent cannot perform. A supervisor calling `/capture/restart` when
`frame_age_ms` exceeds a threshold would paper over it, but the underlying stall is
unexplained and worth chasing.

## 2. `dispatch→seen` = 258 ms and 768 ms

Both are real measurements of "gesture accepted → a frame shows a visible difference".
The 768 ms sample was a heavy transition (task panel opening); 258 ms was a game UI panel.

Interpretation, against the Genshin question:

- A human reacts to a visual change in ~250 ms. **The bridge is at or worse than human
  reaction time, on-device, before any network hop.**
- For a remote agent, add the ~760 ms tunnel round trip: a decision made from a fresh
  frame lands roughly **1–1.5 s after the stimulus**.
- Reflexes therefore **must** run on the phone (`ReflexEngine`), as
  `docs/REALTIME-GENSHIN.md` argues. A remote vision loop cannot play a real-time game.

The honest ceiling this implies for Genshin: predictable scripted content (farming
routes, resource runs, repeated domains) is realistic; reaction-critical play is not.

**Important caveat:** these two samples are few, and both were UI transitions, not
in-game combat. The 258/768 ms includes the app's own transition animation, which a
combat input would not incur. A proper sample set needs taps that produce immediate
frame changes. I am reporting what I measured, not a distribution.

## 3. `verify=1` works, and caught a real bug

Five verified clicks:

| Tap | Verdict | Correct? |
|---|---|---|
| tasks icon | `changed`, 258 ms | yes |
| dead space (sky) | `unchanged` | yes |
| shop cart | `unchanged` | **yes — a "Furniture Return" modal was covering it** |
| mailbox | `unchanged` | yes, same modal |
| city selector | `unchanged` | yes, same modal |
| "Confirm" on that modal | `unchanged` | no — coord was wrong (see below) |

That third row is the whole point of the feature. On the previous build those four taps
would all have returned `ok: true` and I would have concluded the UI was broken or that
my coordinates were wrong. Instead the API told me "nothing happened", I looked, and found
an unexpected modal. **The class of bug that wasted 25 minutes earlier in this project —
silently swallowed input — is now detectable in one call.**

The one `unchanged` that was arguably wrong: the Confirm tap used render-space y=922,
which maps to physical 2044 — the button was at ~1925 physical. My coordinate error, not a
verdict error; `unchanged` was truthful.

## 4. Static screens are not a fault

`/bench/latency` reports fps from `frameStore.seq`. On an unchanging screen MediaProjection
delivers nothing, so 0.5–2 fps there is correct behaviour. Measured `image_available` at
1.5/s on a nearly static screen versus 8 fps published in-game. The fps figure must not be
read as a health check — use `diag.verdict` instead.

## 5. Tunnel reliability

Seven failures in roughly seven hours of use. Each required a human to paste a new URL
because Quick Tunnels mint a new hostname on restart:

`change-europe-wood-voip` → `complete-answering-portsmouth-prepare` →
`midlands-denver-capable-entry` → `pacific-warner-farms-state` →
`drops-apart-florence-configurations` → `research-menus-dental-thought` → (dead)

Two of them died mid-measurement. This is the single largest practical cost in the
project and a named tunnel is a one-time fix.

## What to do next, in order

1. **Named tunnel.** Removes the dominant source of lost work. One-time setup.
2. **Find the stall.** Why does the virtual display stop delivering after minutes, and why
   does recreate fix it? A supervisor on `frame_age_ms` is a band-aid; the cause is a bug.
3. **Fix `diag.verdict` thresholds** — currently "throttled" fires when 13 of 98 frames
   are dropped, which is normal and not the bottleneck. It should only claim throttling
   when `throttle_skipped` is a large fraction of `acquired`, and should say
   `slow_source` when `image_available` itself is low. This is what misled me for a
   while today.
4. **Collect a proper `dispatch→seen` distribution** on combat-like inputs (immediate
   visual response, not UI transitions), at least 20 samples, in-game.
5. Only then decide on the reflex runtime (M2 in `docs/REALTIME-GENSHIN.md`).

## 6. False-positive capability report (found 13 Nov, fixed in 1.6.3)

Worst bug of the project so far, because it fails silently and in the direction of
over-confidence:

```
GET /status  ->  "permissions": { "accessibility": true, ... }
POST /gui/click  ->  503  AccessibilityService not enabled
POST /gui/keys   ->  503  AccessibilityService not enabled
POST /gui/touch  ->  503  AccessibilityService not enabled
```

`permissions.accessibility` was computed from `isAccessibilityEnabled(ctx)` — the
*Settings toggle*. After a package update Android kept the toggle ON while the service
instance was gone, so the bridge advertised full input capability while every input call
failed. An agent plans around capabilities; a capability report that can be true while
the capability is dead is worse than no report at all.

Same failure family as `capture.wanted` (fixed 1.6.0): the bridge reporting intent rather
than state.

Fixed: `/status` now reports `accessibility` (setting AND live), plus
`accessibility_setting`, `accessibility_live`, and an `accessibility_warning` naming the
remedy. New `POST /a11y/fix` opens the exact settings screen so recovery is one toggle
rather than manual navigation — an app cannot re-bind its own accessibility service, so
that tap is irreducibly human.

## 7. Input that is accepted but ignored, on a specific control

In Sleepless City's group chat, the "Go to Room Upgrade" button (physical 540,1672)
returned `effect: unchanged` on **11 consecutive attempts**: plain tap, ±25 ms around the
y band, long press, continuous-touch press, and a left-to-right slide along the bar. It
carries a ⏩ arrow, which suggests a slide-to-confirm affordance that did not respond to
any of those.

Controls in the same panel worked normally — opening Group Chat (698 ms), and closing with
the X (304 ms) — so this is specific to that one control, not broken input.

`verify=1` is what made this legible: on a build without it, all 11 taps would have
returned `ok: true` and I would have had no way to distinguish "button is broken" from
"my coordinates are wrong". This is the strongest evidence so far that verification is the
right default for agent input.
