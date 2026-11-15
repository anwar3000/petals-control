#!/bin/bash
# Villa 21 Room Control — serve the web app from the Pi with nginx (no Node-RED needed).
# Run on the Pi:
#   curl -fsSL https://raw.githubusercontent.com/anwar3000/petals-control/main/install-pi-nginx.sh | bash
# Opens at  http://<pi-ip>:8021/   (own port, so it never clashes with other web apps on the Pi)
set -e
PORT="${PORT:-8021}"
WEB=/var/www/villa21

echo "==> Downloading the latest web app from GitHub…"
TMP=$(mktemp -d)
curl -fsSL https://github.com/anwar3000/petals-control/archive/refs/heads/main.tar.gz | tar -xz -C "$TMP"
SRC="$TMP/petals-control-main/web"
[ -f "$SRC/index.html" ] || { echo "Download failed (web/index.html not found)"; exit 1; }

if ! command -v nginx >/dev/null 2>&1; then
  echo "==> Installing nginx…"
  sudo apt-get update -qq
  sudo apt-get install -y nginx || true          # may fail to start if port 80 is busy; fixed below
fi

# If something other than nginx already uses port 80 (e.g. another web app), switch off nginx's default site
if sudo ss -ltnp 2>/dev/null | grep -E ':80[[:space:]]' | grep -vq nginx; then
  echo "==> Port 80 is used by another app — leaving it alone (disabling nginx default site)"
  sudo rm -f /etc/nginx/sites-enabled/default
fi

if sudo ss -ltnp 2>/dev/null | grep -E ":$PORT[[:space:]]" | grep -vq nginx; then
  echo "!!  Port $PORT is already used by another program. Re-run with another port, e.g.:"
  echo "    curl -fsSL https://raw.githubusercontent.com/anwar3000/petals-control/main/install-pi-nginx.sh | PORT=8022 bash"
  exit 1
fi

echo "==> Copying web app to $WEB"
sudo mkdir -p "$WEB"
sudo rm -rf "${WEB:?}"/*
sudo cp -r "$SRC"/* "$WEB"/
sudo chmod -R a+rX "$WEB"
rm -rf "$TMP"

echo "==> Configuring nginx on port $PORT"
sudo tee /etc/nginx/sites-available/villa21 >/dev/null <<EOF
server {
    listen $PORT;
    listen [::]:$PORT;
    server_name _;
    root $WEB;
    index index.html;
    location / {
        try_files \$uri \$uri/ =404;
        add_header Cache-Control "no-cache";
    }
    location ~* \.(png|webmanifest)\$ {
        expires 7d;
    }
}
EOF
sudo ln -sf /etc/nginx/sites-available/villa21 /etc/nginx/sites-enabled/villa21
sudo nginx -t
sudo systemctl enable nginx >/dev/null 2>&1 || true
sudo systemctl restart nginx

IP=$(hostname -I 2>/dev/null | awk '{print $1}')
echo
echo "Done.  Open on iPhone / iPad (Safari):   http://${IP:-<pi-ip>}:$PORT/"
echo "Then: Share → Add to Home Screen.   (Run this command again any time to update.)"
