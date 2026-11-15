Villa 21 Room Control - web app for iPhone / iPad (and any browser)

Easiest: on the Pi run ONE of these:
  Without Node-RED (nginx, port 8021):  curl -fsSL https://raw.githubusercontent.com/anwar3000/petals-control/main/install-pi-nginx.sh | bash
  Inside Node-RED (port 1880 /room/):   curl -fsSL https://raw.githubusercontent.com/anwar3000/petals-control/main/install-pi.sh | bash

Or put this folder on a web server on your network (plain http), e.g. Node-RED on 192.168.10.5:
  1. Copy the folder to   ~/.node-red/public/room/
  2. In ~/.node-red/settings.js set   httpStatic: '/home/<user>/.node-red/public/',
  3. Restart Node-RED:   node-red-restart   (or sudo systemctl restart nodered)
  4. On the iPhone/iPad open Safari:   http://192.168.10.5:1880/room/
  5. Share button -> "Add to Home Screen" -> Add.

Connection: MQTT over WebSocket ws://192.168.10.5:1884, automatic fallback to the Tasmota directly (192.168.10.72).
