# Villa 21 · Room Control (Android)

Touch control panel for Villa 21 — room-plan view + button view, live status from Tasmota over MQTT.

| Relay | Device |
|---|---|
| POWER1 | Wall light |
| POWER2 | Fan |
| POWER3 | Floor light right |
| POWER4 | Projector |
| POWER5 | AC |
| POWER6 | Small light back |
| POWER7 | Floor light left |
| POWER8 | Right side socket |

Commands: `cmnd/o2/villa/21/POWERn` (ON / OFF / TOGGLE) · Status: `stat/o2/villa/21/POWERn`, `stat/…/RESULT`, `tele/…/LWT`

## 1. Broker: MQTT over WebSockets (one time)

The app connects to **ws://192.168.10.5:1880/mqtt** (changeable in ⚙ Settings → Broker WebSocket URL).

**Aedes broker in Node-RED (port 1880):** open the Aedes node → WebSocket → *Bind to: Path* → `/mqtt` → Deploy.
The broker is then reachable at `ws://192.168.10.5:1880/mqtt`. If you use a different path, enter it in the app.
(If you chose *Bind to: Port* instead, e.g. 8080, use `ws://192.168.10.5:8080`.)

**Mosquitto instead:** add `listener 9001` + `protocol websockets` to mosquitto.conf, restart, and use `ws://192.168.10.5:9001`.

## 2. Build the APK (no Android Studio needed)

1. Create a **private** GitHub repo and push this folder to it (branch `main`).
2. GitHub Actions builds automatically (~4 min) → **Releases** → download `Villa21-RoomControl-1.0.x.apk` on the phone.
3. Tap the APK → allow "Install unknown apps" for your browser/Files → Install.

Every push makes a new release; installing it updates the app in place (same signing key in `keystore/`).

**Android Studio alternative:** open this folder, let it sync, then *Build → Build APK(s)*. Before building, put `mqtt.min.js` (from https://unpkg.com/mqtt@5/dist/mqtt.min.js) in `app/src/main/assets/` — otherwise the app downloads it at runtime from the internet.

## Using the app

- **Tap** a device to switch it · **long-press** to rename / change icon, colour, relay channel
- **✎** → customize mode → **drag** devices around the room plan
- Scenes: All Off, Presentation, Lights On/Off, Cool Down (edit the `SCENES` list in `app/src/main/assets/index.html`)
- ⚙ Settings → **Keep screen on** for a wall-mounted tablet
- Phone must be on the same Wi-Fi as 192.168.10.5
