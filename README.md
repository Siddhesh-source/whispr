# Whispr

A privacy-first, end-to-end encrypted Android messenger. No ads, trackers,
feeds, phone numbers, e-mail addresses or passwords. All protocol
cryptography comes from [libsignal](https://github.com/signalapp/libsignal).
Licensed AGPL-3.0, the same licence as libsignal.

**Status: public beta (0.1.0-beta.1).** It has had an internal security
review (`docs/SECURITY_REVIEW.md`) but no independent audit yet. Do not rely
on it where a failure could hurt someone.

## What it does

- **Private by design.** Your account is a key pair made on your phone. The
  server relays and briefly stores ciphertext; it never sees message text,
  photos, contact lists, group names or members.
- **Signal protocol.** One-to-one chats use PQXDH and the Double Ratchet;
  groups use sender keys, rotated when anyone leaves. Photos, files and
  voice messages are encrypted on the phone with a fresh key each.
- **Messaging.** Replies, reactions, forwarding, copy, delete for me or for
  everyone, disappearing messages, and search over the messages on your
  phone.
- **Trust you can check.** Add people by QR code or username, compare safety
  numbers, and get a warning (with sending paused) if someone's key changes.
- **Protects the phone too.** Encrypted local database, screen security
  against screenshots and recents thumbnails, private lock-screen
  notifications, and a delete-account button that erases everything.

What the server still learns, and what we have not solved yet, is written
down in `docs/THREAT_MODEL.md`.

## Install

Download the APK from the latest
[GitHub release](../../releases) and check it first:

```sh
sha256sum -c SHA256SUMS
apksigner verify --print-certs whispr-<version>.apk   # compare with SIGNING-CERT.txt
```

The release build talks to the server it was built for. To use your own
server, deploy it (`docs/DEPLOYMENT.md`) and build the app with your URL and
certificate pins (`docs/SETUP.md`).

## Repository

```
android/   Kotlin + Jetpack Compose client (app, data, domain, design system)
server/    Go backend: REST and one WebSocket, PostgreSQL, S3-compatible storage
docs/      Architecture, threat model, security review, API, guides
```

Quick start for development:

```sh
docker compose up --build                       # local backend on 127.0.0.1:8080
cd android && ./gradlew installDebug            # after: adb reverse tcp:8080 tcp:8080
```

## Documentation

| | |
|---|---|
| [Development setup](docs/SETUP.md) | Build, test and run locally; release builds |
| [Deployment](docs/DEPLOYMENT.md) | Run a server with Docker Compose and Caddy |
| [API](docs/API.md) | REST and WebSocket reference |
| [Architecture](docs/ARCHITECTURE.md) | Layers, protocols, storage, testing |
| [Threat model](docs/THREAT_MODEL.md) | Assets, adversaries, mitigations, known gaps |
| [Security review](docs/SECURITY_REVIEW.md) | Findings for this release, ranked |
| [Data retention](docs/DATA_RETENTION.md) | What is stored, where, and for how long |
| [Roadmap](ROADMAP.md) | What comes next |
| [Contributing](CONTRIBUTING.md) | How to help, and the rules every change follows |
| [Security policy](SECURITY.md) | Reporting vulnerabilities privately |
| [Designs](docs/designs/) | Design notes for each major feature |
| [Failure log](docs/failures/) | Every build, test or tool failure hit during development |

## Licence

[AGPL-3.0](LICENSE). If you run a modified server for others, the AGPL
requires you to offer them its source.
