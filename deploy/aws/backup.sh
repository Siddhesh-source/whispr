#!/bin/bash
# Daily Postgres dump on the host, kept 7 days (the disk is 10 GB).
# Installed as a systemd timer by deploy.sh.
set -euo pipefail
cd /srv/whispr
out="backups/whispr-$(date +%F).dump"
docker compose exec -T db pg_dump -U whispr -Fc whispr > "$out.tmp"
mv "$out.tmp" "$out"
chmod 600 "$out"
find backups -name 'whispr-*.dump' -mtime +7 -delete
