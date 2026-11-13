# Making input fast: the actual bottleneck was me, not the phone

## The measurement that reframed everything

The on-device gesture engine ticks at **16 ms**. The Cloudflare quick tunnel round trip
measures **300-800 ms**.

So for every joystick step there are two controls, and they differ by a factor of ~25:

| Path | Cost per input event |
|---|---|
| `ContinuousTouchEngine` tick, on-device | **16 ms** |
| `/gui/touch` move over the tunnel | **300-800 ms** |
| Waiting for `/capture/status` to confirm it landed | another **300-800 ms** |

A four-second walk, driven the way this agent had been driving it, was:

```
touch down -> status -> move -> status -> move -> status -> move -> status -> up -> status
  ~8 round trips = 3-6 seconds of pure network for 4 seconds of walking
```

The phone was never the problem. **The agent was steering a joystick from a datacentre**, one
frame of intent per round trip, and then reading the result back over the same slow link.

Any "make the touch faster" work that stays inside the gesture engine is therefore wasted
effort: that code is already ~25x faster than the transport feeding it.

## The rule this implies

> **Anything that needs to happen faster than a tunnel round trip must be decided on the
> phone.** The tunnel is a control channel for intent, not a real-time bus.

Corollary: the right unit of remote work is not a gesture, it is **a closed-loop task**.

## What `POST /gui/batch` gained (v1.7.1)

`/gui/batch` already ran steps sequentially on-device, which was the right shape. What it
lacked was any way to *observe* during the sequence, so anything conditional collapsed back
into one round trip per attempt.

| Op | Purpose |
|---|---|
| `wait_until` | block on the phone until a condition holds |
| `settle` | wait until the screen stops changing (an animation finished) |
| `assert` | fail the batch if a condition does not hold |
| `repeat` | re-run a step N times server-side, no network between |

`wait_until` conditions:

```
{"type":"change"}            frame store seq advanced - a tap did something
{"type":"pixel", x,y,color, tolerance}
{"type":"stable", ms}        screen has been unchanged for ms
```

Plus `{"op":"hold","repeat":N}` and `repeat_gap_ms`, so a "walk forward" is one request with
one hold per leg rather than a stream of moves.

### Why `change` matters most

"Tap, then find out whether it worked" was the most common interaction in this project and the
most expensive: dispatch, sleep, screenshot, compare, decide. Locally that becomes a 40 ms poll
against a frame store in the same process.

## What is still open

- `published` is roughly **half** of `arrived` (4.4 vs 9 fps). Something between acquire and
  publish drops every second frame. Free performance for the taking, cause unknown.
- `capture_not_running` still reads like a working-but-slow pipeline; a stale sensor read as a
  live one caused a wrong "Genshin is unviable" conclusion. Fix pending.
- The remote loop is ~1500 ms measured tap-to-seen in Genshin. Batching removes *my* round
  trips from a multi-step action, but a single reflex still cannot cross the tunnel in time -
  which is why the reflex engine, not batching, is the long-term answer for real-time play.
