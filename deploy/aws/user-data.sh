#!/bin/bash
# First-boot setup for the Whispr host (Ubuntu 24.04 minimal, arm64, 1 GB RAM).
# Kept small on purpose: Docker, a swap file and log caps; nothing else runs.
set -euxo pipefail

# 1 GB of swap so Postgres, MinIO, the server and Caddy fit in 1 GB of RAM.
if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
printf 'vm.swappiness=10\n' > /etc/sysctl.d/99-whispr.conf
sysctl --system

# Bounded logs: the disk is 10 GB.
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=100M\n' > /etc/systemd/journald.conf.d/whispr.conf
systemctl restart systemd-journald

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends docker.io docker-compose-v2 zstd ca-certificates
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{"log-driver": "json-file", "log-opts": {"max-size": "10m", "max-file": "3"}}
EOF
systemctl enable docker
systemctl restart docker
usermod -aG docker ubuntu

install -d -o ubuntu -g ubuntu /srv/whispr /srv/whispr/backups
touch /var/lib/cloud/whispr-ready
