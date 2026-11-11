#!/usr/bin/env bash
# ==============================================================================
#  mobile_bridge.sh — Agent helper functions for Connect-win (Android)
#
#    export BRIDGE_URL="https://xxxx.trycloudflare.com"
#    export BRIDGE_TOKEN="your-token"
#    source agent/mobile_bridge.sh
# ==============================================================================

mget()  { curl -sS --max-time 45 -H "X-Bridge-Token: $BRIDGE_TOKEN" "$BRIDGE_URL$1"; }
mpost() { curl -sS --max-time 90 -X POST -H "X-Bridge-Token: $BRIDGE_TOKEN" \
               -H "Content-Type: application/json" -d "${2:-{}}" "$BRIDGE_URL$1"; }

mstatus() { mget /status; }
mui()     { mget /ui/state; }
mtree()   { mget "/ui/tree?all=${1:-false}"; }

# Click any visible button/element by text label (or pass JSON to /ui/click)
mclick()  { mpost /ui/click "{\"text\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$1")}"; }

# Fill text into an input field instantly (optional 2nd arg: target hint/label, 3rd arg: true/false submit)
mtype()   { mpost /ui/set-text "{\"text\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$1"),\"target\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "${2:-}"),\"submit\":${3:-false}}"; }

# Multi-step sequence in ONE round trip
mbatch()  { mpost /gui/batch "$1"; }

# Fast downscaled JPEG screenshot saved to /tmp/phone_shot.jpg
mshot()   { curl -sS --max-time 45 -H "X-Bridge-Token: $BRIDGE_TOKEN" \
                 "$BRIDGE_URL/screenshot?max_width=${1:-720}&format=jpg&quality=75" \
                 -o /tmp/phone_shot.jpg && echo "saved /tmp/phone_shot.jpg"; }

# Launch app by name ("whatsapp", "settings", "youtube") or open URL/deep-link
mopen()   { mpost /apps/launch "{\"target\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$1")}"; }
mkey()    { mpost /gui/keys "{\"action\":\"${1:-back}\"}"; }

# Notifications, SMS, GPS, Camera, Files
mnotifs() { mget /notifications; }
mreply()  { mpost /notifications/act "{\"query\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$1"),\"reply\":$(python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$2")}"; }
msms()    { mget "/phone/sms?limit=${1:-15}"; }
mgps()    { mget /phone/location; }
mcam()    { mpost /phone/camera "{\"facing\":\"${1:-back}\"}"; }
mls()     { mget "/list?path=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "${1:-/sdcard}")"; }
mdown()   { local f; f="$(basename "$1")"; curl -sS -H "X-Bridge-Token: $BRIDGE_TOKEN" \
                 "$BRIDGE_URL/files/download?path=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$1")" \
                 -o "/tmp/$f" && echo "saved /tmp/$f"; }
mup()     { curl -sS -H "X-Bridge-Token: $BRIDGE_TOKEN" \
                 -F "file=@$1" "$BRIDGE_URL/files/upload?path=$(python3 -c 'import urllib.parse,sys;print(urllib.parse.quote(sys.argv[1]))' "$2")"; }
