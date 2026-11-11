#!/usr/bin/env python3
"""Connect-win realtime agent client.

One tool for low-latency control + speed benchmarking of a Connect-win bridge.
Set BRIDGE_URL and BRIDGE_TOKEN in the environment (or pass --url / --token).

Subcommands:
  status                 device + bridge status
  shot                   grab one JPEG (prefers fast MediaProjection capture)
  pixel X Y              read a pixel color (normalized 0..1 by default)
  find-color COLOR       locate a color on the current frame
  touch ...              continuous touch: down/move/up/press/cancel/state
  reflex ...             on-device rules: list/add/enable/clear
  stream                 receive a binary frame stream (save or count frames)
  bench                  measure RTT / throughput across HTTP + WebSocket paths

Examples:
  python connectwin_client.py status
  python connectwin_client.py shot --out frame.jpg --max-width 540
  python connectwin_client.py bench --n 40
  python connectwin_client.py stream --fps 20 --seconds 8 --outdir frames
  python connectwin_client.py touch press --x 540 --y 1200 --hold-ms 90
"""
import argparse
import json
import os
import struct
import sys
import time
from urllib.parse import urlsplit

try:
    import requests
except ImportError:  # pragma: no cover
    requests = None
try:
    from websocket import create_connection
except ImportError:  # pragma: no cover
    create_connection = None


def _die(msg):
    print(json.dumps({"ok": False, "error": msg}), file=sys.stderr)
    sys.exit(1)


class BridgeClient:
    def __init__(self, url, token, timeout=30):
        if not url or not token:
            _die("set BRIDGE_URL and BRIDGE_TOKEN (or --url/--token)")
        parts = urlsplit(url)
        if parts.scheme not in ("http", "https") or not parts.netloc:
            _die("BRIDGE_URL must be an http(s) URL")
        self.base = url.rstrip("/")
        self.ws_url = ("wss://" if parts.scheme == "https" else "ws://") + parts.netloc + "/ws"
        self.token = token
        self.timeout = timeout
        self.session = requests.Session() if requests else None

    # ---------------- HTTP ----------------
    def http(self, method, path, body=None, params=None, timeout=None, raw=False):
        if self.session is None:
            _die("the 'requests' package is required for HTTP (pip install requests)")
        headers = {"X-Bridge-Token": self.token}
        url = self.base + path
        t = timeout or self.timeout
        if method == "GET":
            r = self.session.get(url, headers=headers, params=params, timeout=t)
        else:
            r = self.session.post(url, headers=headers, json=body if body is not None else {},
                                  params=params, timeout=t)
        if raw:
            r.raise_for_status()
            return r.content
        try:
            return r.json()
        except ValueError:
            _die(f"non-JSON response ({r.status_code}): {r.text[:200]}")

    # ---------------- WebSocket ----------------
    def ws(self):
        if create_connection is None:
            _die("the 'websocket-client' package is required for WS (pip install websocket-client)")
        return create_connection(self.ws_url, header={"X-Bridge-Token": self.token},
                                 suppress_origin=True, timeout=self.timeout)

    def ws_json(self, ws, obj):
        ws.send(json.dumps(obj, separators=(",", ":")))
        return json.loads(ws.recv())

    def ws_binary(self, ws, payload):
        ws.send_binary(payload)
        return ws.recv()

    # ---------------- binary helpers ----------------
    @staticmethod
    def pack_shot(max_width, quality):
        return struct.pack("<BBB", 0x01, max(0, min(255, max_width // 8)), max(0, min(255, quality)))

    @staticmethod
    def pack_ping(seq):
        return struct.pack("<BI", 0x05, seq)

    @staticmethod
    def pack_touch(kind, pointer, x=0, y=0):
        if kind == "down":
            return struct.pack("<BBHH", 0x02, pointer, x, y)
        if kind == "move":
            return struct.pack("<BBHH", 0x03, pointer, x, y)
        if kind == "up":
            return struct.pack("<BB", 0x04, pointer)
        raise ValueError(kind)

    @staticmethod
    def pack_touch_seq(kind, pointer, seq, x=0, y=0):
        if kind == "down":
            return struct.pack("<BBHHI", 0x10, pointer, x, y, seq)
        if kind == "move":
            return struct.pack("<BBHHI", 0x11, pointer, x, y, seq)
        if kind == "up":
            return struct.pack("<BBI", 0x12, pointer, seq)
        raise ValueError(kind)

    @staticmethod
    def unpack_ack_seq(data):
        if isinstance(data, str):
            data = data.encode("latin-1")
        if len(data) < 7 or data[0] != 0x90:
            return None
        pointer, seq, status = struct.unpack("<BIB", data[1:7])
        return {"pointer": pointer, "seq": seq, "status": status}

    @staticmethod
    def unpack_frame(data):
        if isinstance(data, str):
            data = data.encode("latin-1")
        if len(data) < 13 or data[0] != 0x81:
            return None
        seq, w, h, ow, oh = struct.unpack("<IHHHH", data[1:13])
        return {"seq": seq, "w": w, "h": h, "orig_w": ow, "orig_h": oh, "jpeg": data[13:]}


# ---------------- statistics ----------------
def pct(values, p):
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * p
    f = int(k)
    c = min(f + 1, len(s) - 1)
    return s[f] + (s[c] - s[f]) * (k - f)


def summarize(name, samples, nbytes=None):
    if not samples:
        return {"test": name, "ok": False}
    row = {
        "test": name,
        "n": len(samples),
        "min_ms": round(min(samples), 2),
        "median_ms": round(pct(samples, 0.5), 2),
        "p95_ms": round(pct(samples, 0.95), 2),
        "max_ms": round(max(samples), 2),
    }
    if nbytes:
        row["median_bytes"] = int(pct(nbytes, 0.5))
    return row


# ---------------- subcommands ----------------
def cmd_status(c, args):
    print(json.dumps(c.http("GET", "/status"), indent=2))


def cmd_shot(c, args):
    data = c.http("GET", "/capture/frame", params={"max_width": args.max_width, "quality": args.quality}, raw=True)
    out = args.out or "frame.jpg"
    with open(out, "wb") as f:
        f.write(data)
    print(json.dumps({"ok": True, "saved": out, "bytes": len(data)}))


def cmd_pixel(c, args):
    print(json.dumps(c.http("GET", "/capture/pixel", params={"x": args.x, "y": args.y})))


def cmd_find_color(c, args):
    print(json.dumps(c.http("POST", "/capture/find_color", body={"color": args.color, "tolerance": args.tolerance})))


def cmd_touch(c, args):
    body = {"action": args.action, "pointer": args.pointer}
    if args.action in ("down", "move", "press"):
        body.update({"x": args.x, "y": args.y})
    if args.action == "press":
        body["hold_ms"] = args.hold_ms
    if args.action == "tick":
        body["tick_ms"] = args.tick_ms
    print(json.dumps(c.http("POST", "/gui/touch", body=body)))


def cmd_ws_touch(c, args):
    """Persistent-WS sequenced touch: every action is confirmed by an ordered ack."""
    ws = c.ws()
    seq = 0
    results = []

    def send(kind, pointer, x=0, y=0):
        nonlocal seq
        seq += 1
        t0 = time.perf_counter()
        ws.send_binary(c.pack_touch_seq(kind, pointer, seq, x, y))
        # Read until we get the matching sequenced ack (skip frames/other packets).
        while True:
            ack = c.unpack_ack_seq(ws.recv())
            if ack and ack["seq"] == seq:
                results.append({"kind": kind, "pointer": pointer, "seq": seq,
                                "status": ack["status"],
                                "rtt_ms": round((time.perf_counter() - t0) * 1000, 2)})
                return

    try:
        if args.action == "press":
            send("down", args.pointer, args.x, args.y)
            time.sleep(args.hold_ms / 1000.0)
            send("up", args.pointer)
        elif args.action == "drag":
            send("down", args.pointer, args.x, args.y)
            steps = max(1, args.steps)
            for i in range(1, steps + 1):
                x = int(args.x + (args.x2 - args.x) * i / steps)
                y = int(args.y + (args.y2 - args.y) * i / steps)
                send("move", args.pointer, x, y)
                if args.step_delay_ms > 0:
                    time.sleep(args.step_delay_ms / 1000.0)
            send("up", args.pointer)
        else:
            send(args.action, args.pointer, args.x, args.y)
    finally:
        ws.close()
    print(json.dumps({"ok": True, "acks": results}, indent=2))


def cmd_reflex(c, args):
    if args.action == "list":
        print(json.dumps(c.http("GET", "/reflex/rules"), indent=2))
    elif args.action == "enable":
        print(json.dumps(c.http("POST", "/reflex/enable", body={"enabled": not args.off})))
    elif args.action == "clear":
        print(json.dumps(c.http("POST", "/reflex/clear")))
    elif args.action == "add":
        rule = json.loads(args.rule)
        print(json.dumps(c.http("POST", "/reflex/rules", body=rule)))


def cmd_stream(c, args):
    ws = c.ws()
    os.makedirs(args.outdir, exist_ok=True) if args.outdir else None
    resp = c.ws_json(ws, {"op": "stream_start", "fps": args.fps,
                          "max_width": args.max_width, "quality": args.quality})
    print(json.dumps({"stream_start": resp}), flush=True)
    count = 0
    sizes = []
    gaps = []
    last = None
    t_end = time.time() + args.seconds if args.seconds > 0 else float("inf")
    try:
        while time.time() < t_end:
            data = ws.recv()  # passive: server pushes binary frames
            frame = c.unpack_frame(data)
            if not frame:
                continue
            now = time.time()
            if last is not None:
                gaps.append((now - last) * 1000)
            last = now
            count += 1
            sizes.append(len(frame["jpeg"]))
            if args.outdir:
                with open(os.path.join(args.outdir, f"f_{count:05d}.jpg"), "wb") as f:
                    f.write(frame["jpeg"])
    except KeyboardInterrupt:
        pass
    finally:
        try:
            c.ws_json(ws, {"op": "stream_stop"})
        except Exception:
            pass
        ws.close()
    fps = (count / args.seconds) if args.seconds > 0 else 0
    print(json.dumps({
        "ok": True, "frames": count, "approxfps": round(fps, 2),
        "median_gap_ms": round(pct(gaps, 0.5), 2) if gaps else None,
        "median_bytes": int(pct(sizes, 0.5)) if sizes else None,
    }, indent=2))


def cmd_bench(c, args):
    n = args.n
    results = []

    # HTTP ping
    s = []
    for _ in range(n):
        t0 = time.perf_counter(); c.http("GET", "/bench/ping"); s.append((time.perf_counter() - t0) * 1000)
    results.append(summarize("http /bench/ping", s))

    # HTTP status
    s = []
    for _ in range(max(5, n // 4)):
        t0 = time.perf_counter(); c.http("GET", "/status"); s.append((time.perf_counter() - t0) * 1000)
    results.append(summarize("http /status", s))

    # HTTP ui_state
    s = []; b = []
    for _ in range(max(5, n // 4)):
        t0 = time.perf_counter(); r = c.http("GET", "/ui/state"); s.append((time.perf_counter() - t0) * 1000)
        b.append(len(json.dumps(r)))
    results.append(summarize("http /ui/state", s, b))

    # Fast capture frame (if running)
    cap = c.http("GET", "/capture/status")
    if cap.get("running"):
        for mw in (360, 540, 720):
            s = []; b = []
            for _ in range(max(5, n // 4)):
                t0 = time.perf_counter()
                data = c.http("GET", "/capture/frame", params={"max_width": mw, "quality": 60}, raw=True)
                s.append((time.perf_counter() - t0) * 1000); b.append(len(data))
            results.append(summarize(f"capture/frame w={mw}", s, b))
    else:
        results.append({"test": "capture/frame", "ok": False, "note": "fast capture not enabled"})

    # Accessibility screenshot (rate-limited baseline)
    s = []; b = []
    for _ in range(max(3, n // 8)):
        t0 = time.perf_counter()
        data = c.http("GET", "/screenshot", params={"max_width": 540, "format": "jpg", "quality": 60}, raw=True)
        s.append((time.perf_counter() - t0) * 1000); b.append(len(data))
    results.append(summarize("a11y /screenshot w=540", s, b))

    # WebSocket
    try:
        ws = c.ws()
        # text ping
        s = []
        for _ in range(n):
            t0 = time.perf_counter(); c.ws_json(ws, {"op": "ping"}); s.append((time.perf_counter() - t0) * 1000)
        results.append(summarize("ws text ping", s))
        # binary ping
        s = []
        for i in range(n):
            t0 = time.perf_counter(); c.ws_binary(ws, c.pack_ping(i)); s.append((time.perf_counter() - t0) * 1000)
        results.append(summarize("ws binary ping", s))
        # binary shot
        s = []; b = []
        for _ in range(max(5, n // 4)):
            t0 = time.perf_counter(); data = c.ws_binary(ws, c.pack_shot(480, 55))
            s.append((time.perf_counter() - t0) * 1000); b.append(len(data))
        results.append(summarize("ws binary shot w=480", s, b))
        ws.close()
    except Exception as e:
        results.append({"test": "websocket", "ok": False, "error": str(e)})

    print(json.dumps({"ok": True, "results": results}, indent=2))


def build_parser():
    p = argparse.ArgumentParser(description="Connect-win realtime agent client")
    p.add_argument("--url", default=os.environ.get("BRIDGE_URL"))
    p.add_argument("--token", default=os.environ.get("BRIDGE_TOKEN"))
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("status")

    sp = sub.add_parser("shot")
    sp.add_argument("--out"); sp.add_argument("--max-width", type=int, default=720); sp.add_argument("--quality", type=int, default=70)

    sp = sub.add_parser("pixel"); sp.add_argument("x", type=float); sp.add_argument("y", type=float)
    sp = sub.add_parser("find-color"); sp.add_argument("color"); sp.add_argument("--tolerance", type=int, default=24)

    sp = sub.add_parser("touch")
    sp.add_argument("action", choices=["down", "move", "up", "press", "cancel", "state", "tick"])
    sp.add_argument("--pointer", type=int, default=0); sp.add_argument("--x", type=int, default=0)
    sp.add_argument("--y", type=int, default=0); sp.add_argument("--hold-ms", type=int, default=80)
    sp.add_argument("--tick-ms", type=int, default=16)

    sp = sub.add_parser("ws-touch", help="sequenced touch over one persistent WS (ordered acks)")
    sp.add_argument("action", choices=["down", "move", "up", "press", "drag"])
    sp.add_argument("--pointer", type=int, default=0); sp.add_argument("--x", type=int, default=0)
    sp.add_argument("--y", type=int, default=0); sp.add_argument("--x2", type=int, default=0)
    sp.add_argument("--y2", type=int, default=0); sp.add_argument("--hold-ms", type=int, default=80)
    sp.add_argument("--steps", type=int, default=20); sp.add_argument("--step-delay-ms", type=int, default=16)

    sp = sub.add_parser("reflex")
    sp.add_argument("action", choices=["list", "add", "enable", "clear"])
    sp.add_argument("--rule", help="JSON rule for 'add'"); sp.add_argument("--off", action="store_true")

    sp = sub.add_parser("stream")
    sp.add_argument("--fps", type=int, default=15); sp.add_argument("--max-width", type=int, default=480)
    sp.add_argument("--quality", type=int, default=55); sp.add_argument("--seconds", type=int, default=8)
    sp.add_argument("--outdir")

    sp = sub.add_parser("bench"); sp.add_argument("--n", type=int, default=30)
    return p


def main():
    args = build_parser().parse_args()
    c = BridgeClient(args.url, args.token)
    fn = {
        "status": cmd_status, "shot": cmd_shot, "pixel": cmd_pixel, "find-color": cmd_find_color,
        "touch": cmd_touch, "ws-touch": cmd_ws_touch, "reflex": cmd_reflex,
        "stream": cmd_stream, "bench": cmd_bench,
    }[args.cmd]
    fn(c, args)


if __name__ == "__main__":
    main()
