# Connect-win — problems observed while using Joy Aquarium

Recorded 11 November 2026. These are observations for future testing, not confirmed diagnoses.

1. Accessibility clicks can report success without the web app navigating. Joy Aquarium's Sales and Reports menu entries returned accessibility_action success but appeared unchanged; a coordinate gesture worked. Need verify screen change and optionally retry using safe fallback, not blindly repeat mutations.
2. Stale accessibility tree after scrolling in the side menu: reported positions and labels differed from the screenshot, and coordinates targeted based on stale data opened unintended read-only screens. Potential causes include cached/stale WebView nodes or incorrect window selection; root cause unconfirmed. Refresh nodes, check active window/scroll completion, validate bounds, and use screenshots when uncertain.
3. ui/state truncates labels/text to 90 characters and caps lists. ui/tree?all=true exposes fuller text, but is larger. Provide an efficient non-truncated, filtered alternative.
4. Coordinate mismatch: /ui/state reports screen [1080,2153] while actual screenshot is 1080x2400 including system bars; node centers include full-screen positions above reported height. Navigation controls had y around 2171. Clearly return window bounds, screenshot dimensions, insets, rotation, and coordinate mapping.
5. Accessibility state can be transient immediately after navigation; pages sometimes needed another request after loading to see contents. Add screen-ready/wait and state-change primitives rather than fixed sleeps.
6. WebSocket 1.1.0 through the tunnel works after removing Content-Length from the 101 response. Small sample: persistent ping median 331ms; HTTP status median 365ms; 540px JPEG request 760ms. Network path latency remains significant. On-demand screenshot, not continuous streaming.
7. Quick Tunnel URL changes on app update/restart. The old endpoint cannot discover the new one, requiring the phone user to provide the URL. Named Tunnel with stable hostname may help; requires configuration and does not remove token requirements. Automatic restart succeeded during a recent update but does not guarantee reconnect.
8. Timed overlapping gesture support was built, but not yet tested on-device. Android gestures may be cancelled and real-time game performance is unverified.
9. APK updates kill the bridge while replacing the package; Android installer confirmation and a newly created tunnel can interrupt automation. Preserve signing key and data; do not claim successful update until version is verified.
10. Security: bridge token provides broad phone access; rotate exposed token and assess authentication, log privacy, connection revocation and emergency stop before expanding streaming/control.

Joy Aquarium itself displayed a recurring update prompt, but that is an application UI observation, not evidence of a bridge defect. The 170 waiting badge was confirmed to be action alerts, not a bridge or sync issue. No business records were created or edited during the inspection.
