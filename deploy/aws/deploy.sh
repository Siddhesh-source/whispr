#!/usr/bin/env bash
# Deploys (or updates) the Whispr server on the AWS host.
#
#   [WHISPR_HOST=name] deploy/aws/deploy.sh <elastic-ip> <ssh-key> [server-image run id]
#
# Ships the arm64 image built by .github/workflows/server-image.yml (latest
# successful run by default), the compose file, Caddyfile and backup script.
# Secrets are generated on the host on first deploy and never leave it.
set -euo pipefail

ip=$1
key=$2
run=${3:-}
repo=Siddhesh-source/whispr
here=$(cd "$(dirname "$0")" && pwd)
# The public name: WHISPR_HOST, or an sslip.io name derived from the IP.
host=${WHISPR_HOST:-${ip//./-}.sslip.io}
ssh_opts=(-i "$key" -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
target=ubuntu@$ip

if [ -z "$run" ]; then
  run=$(gh run list -R "$repo" -w server-image -s success -L 1 --json databaseId -q '.[0].databaseId')
fi
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" -R "$repo" -n whispr-server-arm64 -D "$tmp"

ssh "${ssh_opts[@]}" "$target" 'test -f /var/lib/cloud/whispr-ready' ||
  { echo "host not ready (cloud-init still running?)"; exit 1; }
scp "${ssh_opts[@]}" "$here/compose.yml" "$here/Caddyfile" "$here/backup.sh" \
  "$tmp/whispr-server-arm64.tar.zst" "$target:/srv/whispr/"

ssh "${ssh_opts[@]}" "$target" WHISPR_HOST="$host" bash -s <<'REMOTE'
set -euo pipefail
cd /srv/whispr
loaded=$(zstd -dc whispr-server-arm64.tar.zst | docker load | sed -n 's/^Loaded image: //p' | tail -1)
docker tag "$loaded" whispr-server:deploy
rm -f whispr-server-arm64.tar.zst
chmod +x backup.sh

if [ ! -f .env ]; then
  umask 077
  {
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
    echo "MINIO_ROOT_PASSWORD=$(openssl rand -hex 24)"
    echo "WHISPR_HOST=$WHISPR_HOST"
  } > .env
fi

sudo tee /etc/systemd/system/whispr-backup.service > /dev/null <<'EOF'
[Unit]
Description=Whispr Postgres backup
[Service]
Type=oneshot
User=ubuntu
ExecStart=/srv/whispr/backup.sh
EOF
sudo tee /etc/systemd/system/whispr-backup.timer > /dev/null <<'EOF'
[Unit]
Description=Daily Whispr Postgres backup
[Timer]
OnCalendar=*-*-* 03:30:00
RandomizedDelaySec=30m
Persistent=true
[Install]
WantedBy=timers.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now whispr-backup.timer

docker compose up -d --remove-orphans
docker image prune -f > /dev/null
docker compose ps --format '{{.Service}} {{.State}} {{.Health}}'
REMOTE

echo "https://$host/"
