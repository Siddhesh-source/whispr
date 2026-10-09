# Whispr

**A private messenger with everything you use WhatsApp for, and nothing
that ties it to your phone number.**

Your phone number is in hundreds of address books: old classmates, landlords,
delivery drivers, that one group you were added to in 2019. On WhatsApp, every
one of them can find you, message you, add you to groups and watch your
status. The app fills up with people you never chose, and it stops feeling
private.

Whispr starts from the other end. There is no phone number, no e-mail and no
contact upload. Your account is a key pair made on your phone, and people
reach you only through a code you choose to share. Even then they can only
*ask*: nothing gets through until you accept.

End-to-end encrypted with [libsignal](https://github.com/signalapp/libsignal),
the same cryptography as Signal. Licensed AGPL-3.0.

**Status: public beta (0.1.0-beta.3).** It has had an internal security
review (`docs/SECURITY_REVIEW.md`) but no independent audit yet. Do not rely
on it where a failure could hurt someone.

## Private by default

- **No number to leak.** Share your QR code in person, or send it (or a
  `whispr://add` link) to anyone over the internet. The code carries your
  account ID and public key, never a phone number.
- **Requests, not intrusions.** Scanning your code or finding your username
  sends a request. Until you accept, they can't message you, call you, see
  your status or your photo; anything else they send is dropped on your phone.
- **The server sees ciphertext.** It relays and briefly stores encrypted
  envelopes. It never sees message text, photos, your contacts, group names
  or members, or who viewed your status.

## Everything you'd expect from a messenger

- **Chats and groups.** Replies, reactions, forwarding, voice messages,
  photos (camera or gallery), GIFs, files, disappearing messages, search.
  Any member can rename a group or change its picture; admins manage members.
  Leave a group and its history stays, marked read-only.
- **Status.** 24-hour text and photo updates for your contacts, with a view
  count and likes. Views are shared only while read receipts are on, the
  same rule as message ticks.
- **Calls.** End-to-end encrypted voice and video calls (WebRTC), with an
  option to route them through the relay to hide your IP address.
- **Profiles.** Your photo goes encrypted to the people you accepted, and only
  to them. Tap a contact to see their profile, safety number, and every photo
  and file you exchanged, each one saveable.
- **Your chats survive reinstalling.** Turn on chat backup: once a day an
  encrypted copy goes to Downloads/Whispr (or a folder you pick). Only your
  64-character recovery key opens it, and restoring brings back your account,
  chats and media.
- **Read receipts** on by default, typing indicators off, both switchable.
- **Updates inside the app.** New releases are downloaded, checked against
  their SHA-256 and installed from Settings; no trip to GitHub needed.

## Trust you can check

Compare safety numbers in person to verify a contact, and get a warning (with
sending paused) if someone's key changes. The phone itself is protected too:
an encrypted local database, screen security against screenshots and recents
thumbnails, private lock-screen notifications, and a delete-account button that
erases everything.

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
