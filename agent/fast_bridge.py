#!/usr/bin/env python3
"""Interactive persistent bridge client. pip install websocket-client
Set BRIDGE_URL and BRIDGE_TOKEN in the environment. One JSON command per line.
Example: {"op":"batch","steps":[{"op":"tap","x":100,"y":200},{"op":"shot"}]}
"""
import json
import os
import sys
from urllib.parse import urlsplit
from websocket import create_connection

url = urlsplit(os.environ['BRIDGE_URL'])
if url.scheme != 'https' or not url.netloc:
    raise SystemExit('BRIDGE_URL must be an HTTPS tunnel URL')
token = os.environ['BRIDGE_TOKEN']
ws = create_connection('wss://' + url.netloc + '/ws',
                       header={'X-Bridge-Token': token}, suppress_origin=True, timeout=30)
try:
    for line in sys.stdin:
        try:
            msg = json.loads(line)
            ws.send(json.dumps(msg, separators=(',', ':')))
            print(ws.recv(), flush=True)
        except (ValueError, TypeError) as e:
            print(json.dumps({'ok': False, 'error': str(e)}), flush=True)
finally:
    ws.close()
