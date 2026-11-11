#!/usr/bin/env python3
"""Speed benchmark for a Connect-win bridge. Requires: pip install requests websocket-client
Set BRIDGE_URL and BRIDGE_TOKEN, then: python bench_bridge.py --n 40
"""
import os
from connectwin_client import BridgeClient, cmd_bench


class Args:
    n = int(os.environ.get("BENCH_N", "30"))


if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument("--n", type=int, default=30)
    p.add_argument("--url", default=os.environ.get("BRIDGE_URL"))
    p.add_argument("--token", default=os.environ.get("BRIDGE_TOKEN"))
    a = p.parse_args()
    cmd_bench(BridgeClient(a.url, a.token), a)
