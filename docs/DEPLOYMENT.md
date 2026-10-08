# Deploying a Whispr server

This guide runs the server on one Linux host with Docker Compose: Postgres,
S3-compatible object storage (MinIO here; any S3 works), the Whispr server,
and Caddy in front for TLS. The server holds only ciphertext and minimal
metadata (`docs/DATA_RETENTION.md`), but it is still the thing every client
trusts for availability and for not lying about keys, so treat the host as
production infrastructure.

The beta is a single instance. Rate limits and live connections are held in
memory, so do not run two server replicas behind one name yet.

## What you need

- A Linux host with Docker and Docker Compose, ports 80 and 443 reachable.
- A DNS name for the API, e.g. `api.example.org`, pointing at the host.
- Optional: a Firebase project for wake-up pushes (see the README).

## 1. Build the image

```sh
git clone https://github.com/<you>/whispr && cd whispr
docker build -t whispr-server:0.1.0-beta.1 ./server
```

The build compiles libsignal's C library from source (a few minutes the
first time) and runs the test suite against it. A server built without
libsignal refuses to start.

## 2. Secrets

Create `/srv/whispr/.env` (mode `600`, owned by root):

```sh
POSTGRES_PASSWORD=<long random>
MINIO_ROOT_USER=whispr
MINIO_ROOT_PASSWORD=<long random>
```

Generate values with `openssl rand -base64 32`. Never reuse the development
credentials from the repository's `docker-compose.yml`.

## 3. Compose file

`/srv/whispr/compose.yml`:

```yaml
name: whispr-prod

networks:
  edge:
    ipam:
      config: [{ subnet: 172.30.0.0/24 }]
  internal:
    internal: true # no route to the internet

services:
  db:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      POSTGRES_USER: whispr
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: whispr
    volumes: [db-data:/var/lib/postgresql/data]
    networks: [internal]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U whispr -d whispr"]
      interval: 5s
      retries: 10

  minio:
    image: cgr.dev/chainguard/minio:latest # pin by digest in production
    restart: unless-stopped
    command: ["server", "/data"]
    environment:
      MINIO_ROOT_USER: ${MINIO_ROOT_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_ROOT_PASSWORD}
    volumes: [minio-data:/data]
    networks: [internal]

  minio-init:
    image: cgr.dev/chainguard/minio-client:latest
    depends_on: [minio]
    environment:
      MC_HOST_local: http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000
    command: ["mb", "--ignore-existing", "local/whispr-media"]
    networks: [internal]
    restart: on-failure

  server:
    image: whispr-server:0.1.0-beta.1
    restart: unless-stopped
    environment:
      DATABASE_URL: postgres://whispr:${POSTGRES_PASSWORD}@db:5432/whispr?sslmode=disable
      LISTEN_ADDR: ":8080"
      LOG_LEVEL: info
      # Only Caddy may set X-Forwarded-For. Without this every client shares
      # Caddy's address and its rate limits.
      TRUSTED_PROXIES: 172.30.0.10
      S3_ENDPOINT: minio:9000
      S3_ACCESS_KEY: ${MINIO_ROOT_USER}
      S3_SECRET_KEY: ${MINIO_ROOT_PASSWORD}
      S3_BUCKET: whispr-media
      S3_USE_SSL: "false" # internal network only
      # FCM_CREDENTIALS_FILE: /run/secrets/fcm.json
    networks: [internal, edge]
    depends_on:
      db: { condition: service_healthy }
      minio-init: { condition: service_completed_successfully }
    logging:
      driver: json-file
      options: { max-size: "20m", max-file: "5" }

  caddy:
    image: caddy:2
    restart: unless-stopped
    ports: ["80:80", "443:443"]
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
    networks:
      edge: { ipv4_address: 172.30.0.10 }

volumes:
  db-data:
  minio-data:
  caddy-data:
```

Use a dedicated MinIO user with access to the one bucket, rather than the
root user, once the deployment is up.

## 4. TLS with Caddy

`/srv/whispr/Caddyfile`:

```
api.example.org {
    encode zstd gzip
    header {
        Strict-Transport-Security "max-age=63072000; includeSubDomains"
        -Server
    }
    # Uploads are up to 25 MiB of ciphertext.
    request_body {
        max_size 26MB
    }
    reverse_proxy server:8080
}
```

Caddy obtains and renews certificates automatically, upgrades WebSockets,
and sets `X-Forwarded-For`. Do not log request bodies or headers at the
proxy; Caddy's default access log is off.

Start everything:

```sh
cd /srv/whispr && docker compose --env-file .env up -d
curl https://api.example.org/healthz    # {"status":"ok","database":"up"}
```

The server applies database migrations at start.

## 5. Certificate pins for the app

Release builds of the app pin the server's certificate chain by SPKI hash
and need at least two pins, so one can fail without locking every user out.
Choose what to pin:

- **CA roots (simplest).** Pin the roots your CA chains to, for example both
  Let's Encrypt roots (ISRG Root X1 and X2). Certificates from any other CA
  are refused, and routine renewals keep working. Compute each pin from the
  root's PEM (published by the CA):

  ```sh
  openssl x509 -in root.pem -pubkey -noout | openssl pkey -pubin -outform der |
    openssl dgst -sha256 -binary | openssl base64
  ```

- **Your own keys (strictest).** Pin your server key and a backup key kept
  offline. Then the server must keep using the pinned key across renewals,
  and rotating means shipping an app update with the new pin first.

Build the release with:

```sh
./gradlew :app:assembleRelease \
  -Pwhispr.releaseServerUrl=https://api.example.org/ \
  -Pwhispr.certPins=sha256/<pin1>,sha256/<pin2>
```

or set the repository variables `WHISPR_RELEASE_SERVER_URL` and
`WHISPR_CERT_PINS` for the release workflow. Self-hosters who accept plain
system-CA trust can pass `-Pwhispr.allowUnpinned=true` instead; the threat
model explains what that gives up.

If the pins and the server ever disagree, every client fails to connect
until it is updated, so change pins only together with an app release, and
keep the backup pin's key or CA available.

## 6. Operating it

**Configuration** (environment variables):

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` | required | |
| `LISTEN_ADDR` | `:8080` | |
| `TRUSTED_PROXIES` | none | CIDRs or addresses allowed to set `X-Forwarded-For` |
| `RATE_LIMIT_PER_MINUTE` | 30 | Per IP on register, challenge, verify |
| `REGISTRATIONS_PER_HOUR` | 10 | Per IP, account creation |
| `USER_REQUESTS_PER_MINUTE` | 120 | Per user, every authenticated route |
| `TOKEN_TTL` | `15m` | |
| `CHALLENGE_TTL` | `60s` | |
| `ATTACHMENT_RETENTION` | `720h` | |
| `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET`, `S3_USE_SSL` | media off | Media needs all of them |
| `FCM_CREDENTIALS_FILE` | push off | Service-account key with the FCM Admin role |
| `LOG_LEVEL` | `info` | |

**Backups.** Back up Postgres daily and keep 30 days, matching the data
retention, for example:

```sh
docker compose exec -T db pg_dump -U whispr -Fc whispr > /backup/whispr-$(date +%F).dump
find /backup -name 'whispr-*.dump' -mtime +30 -delete
```

Encrypt backups at rest. Object storage holds only ciphertext that expires
after 30 days; backing it up is optional.

**Logs.** Request logs contain method, route, status and duration only.
Rotate them and keep at most 14 days.

**Upgrades.** Build the new image, then `docker compose up -d server`.
Migrations run on start and are forward-only within a release line; take a
backup first. Read the release notes for anything that needs a client
update first (for example new certificate pins).

**Monitoring.** Poll `/healthz`. The container also has a built-in health
check. The `load` workflow (`.github/workflows/load.yml`) measures gateway
throughput and latency against Postgres if you want a baseline.

**Firewall.** Expose only 80 and 443. Postgres and MinIO stay on the
internal network.

## 7. Account deletion and abuse

Users delete their own accounts from the app; nothing is needed from the
operator. The server has no content to moderate. Abuse controls are the rate
limits above and the per-recipient undelivered quota (1,000 envelopes from
one sender to one recipient).
