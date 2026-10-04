# Whispr threat model

Status: draft covering Phase 1 (identity, registration, sign-in, local
storage), Phase 2 (1:1 messaging, push) and Phase 3 (QR contacts,
verification, usernames). Media gets its
own section when built. "Gap" marks a known weakness we have accepted for now, each with a plan.

## Assets

| Asset | Where it lives | Why it matters |
|-------|----------------|----------------|
| Identity private key | Device only: wrapped by AndroidKeyStore, in app memory while running | Whoever holds it *is* the user: can sign in and, later, impersonate them to contacts |
| Message content | Devices; **server too during Phase 2** (plaintext payloads) | Confidentiality of conversations |
| Local database | Device: SQLCipher, key wrapped by Keystore | Profile, contacts, messages, outbox |
| Session token | App memory and server (hash only) | Short-lived API access |
| Metadata | Server | Who uses the service, when, and (later) who talks to whom |
| Avatar | Device only | Personal image; may contain location in EXIF |

## Adversaries

1. **Network attacker** (Wi-Fi operator, ISP, state): observes and modifies traffic.
2. **Malicious or compromised server operator**: full access to the server, database and logs.
3. **Database or backup thief**: obtains a copy of the server database.
4. **Thief with a locked device**, or forensic access to device storage.
5. **Malicious app on the same device** without root.
6. **Spammer**: creates many accounts.

Out of scope: an attacker with root or kernel access on the unlocked device, a
compromised OS or keyboard, and physical coercion. No app-level control
defeats those, and we do not claim otherwise.

## Trust boundaries

```
[ Device: app sandbox ── Keystore/StrongBox ] ══ TLS (prod) ══ [ Server ── Postgres ── S3 ]
                                               ▲ untrusted network
```

The server is **not trusted with content or keys**. It is trusted only to
relay data and keep the minimal metadata it needs.

## What the server learns (Phase 1)

| Data | Stored? | Notes |
|------|---------|-------|
| Identity public key | Yes | Required to verify sign-ins |
| Display name | Yes, **plaintext** | **Gap**: interim exception agreed for Phase 1; replaced by encrypted profiles |
| Account creation time | Yes | Column default; could be coarsened |
| Sign-in times | Transiently (challenge and token rows, deleted at expiry) | Not logged |
| Client IP address | Seen per request, **not logged or stored** | Held in memory by the rate limiter only (never persisted); idle entries are purged under load |
| Avatar | No | Never uploaded |
| Phone number, email, contacts | No | Never collected |

## Threats and mitigations

### Identity and authentication

| Threat | Mitigation | Status |
|--------|------------|--------|
| Password or SMS-code theft | There are no passwords and no phone numbers; sign-in proves possession of the identity key | Done |
| Replay of a captured sign-in | 32-byte random nonce, single use, 60 s expiry; the challenge is consumed *before* the signature is checked, so each nonce gets one attempt | Done, tested (incl. concurrency) |
| Cross-protocol signature reuse | Domain-separated messages (`whispr-register-v1`, `whispr-auth-v1`) with fixed-length fields; shared test vectors | Done |
| Registering someone else's key | Registration must be signed by the key's private half | Done |
| Stolen database yields sessions | Only SHA-256 of random 256-bit tokens is stored | Done |
| Token theft from device storage or backups | Token is never written to disk | Done |
| Stolen token reused | 15 min lifetime; bearer over TLS | Done (TLS: see Transport) |
| Signature-check bugs | Verification is libsignal (via FFI), not our code; malformed keys are rejected; a server built without libsignal refuses to start | Done |
| Error oracle | All sign-in failures return the same `401 auth_failed` | Done |
| User enumeration via challenge | `404 unknown_user` reveals whether a random UUID exists | **Gap (low)**: UUIDv4 IDs are unguessable and are shared by QR anyway; revisit if IDs become discoverable |

### Device storage

| Threat | Mitigation | Status |
|--------|------------|--------|
| Reading key material from disk (forensics, other apps) | Identity key and DB key wrapped with AES-256-GCM under non-exportable Keystore keys (StrongBox when present), stored in the app sandbox's no-backup dir; AAD binds each blob to its purpose | Done, tested on device |
| Cloud backup or device transfer leaking data | `allowBackup=false`; extraction rules exclude every domain | Done |
| Tampered key blob silently replaced | GCM authentication fails; the app does not generate a new identity over a corrupt one | Done, tested |
| Database read from disk | SQLCipher; test asserts there is no SQLite header and no plaintext in the file | Done, tested on device |
| Use of the app on an unlocked, stolen phone | Keystore keys are **not** bound to user authentication, so the app works without a separate unlock | **Gap**: optional app lock (biometric/PIN) planned; binding keys to auth would break background message delivery |
| Private key in process memory | Unavoidable while signing; cached for the process lifetime | Accepted (root attacker is out of scope) |
| Screenshots or recents thumbnails | Not yet protected | **Gap**: offer a FLAG_SECURE "screen security" setting before messaging ships |
| Keyboard learning typed messages | Compose has no API for `IME_FLAG_NO_PERSONALIZED_LEARNING` | **Gap**: add via a platform text-field interop when messaging ships |
| Avatar leaking location | Re-encoded on import, which strips EXIF | Done |

### Transport

| Threat | Mitigation | Status |
|--------|------------|--------|
| Eavesdropping or modification | Release builds allow HTTPS only, system CAs only (user-installed CAs not trusted) | Config done; **no production endpoint yet** |
| Rogue CA / TLS interception | Certificate pinning | **Planned** with the production deployment |
| Dev cleartext leaking to release | Cleartext allowed only in the *debug* network config, and only for loopback | Done |

The confidentiality of *messages* will not depend on TLS: libsignal end-to-end
encryption protects content even from a server or network that defeats TLS.

### Malicious server

| Threat | Mitigation | Status |
|--------|------------|--------|
| Server impersonates a user | Server never holds private keys; it cannot produce users' signatures | Done |
| Server substitutes identity keys when a contact is added (MITM) | Contacts are added by scanning a QR code that carries the identity key, verified out of band; key changes will be surfaced to users | **Planned** (Phase 2) |
| Server reads messages | E2EE via libsignal; the server stores opaque envelopes | **Planned** (messaging phase) |
| Server builds a social graph | Minimize: delete envelopes on delivery; evaluate sealed sender | **Planned** |
| Denial of service | Out of scope for confidentiality; the client degrades gracefully (offline banner, retries with backoff) | Partial |

### Logging and observability

| Threat | Mitigation | Status |
|--------|------------|--------|
| Secrets in server logs | Request logs record method, route pattern, status and latency only; a deny-list redactor masks `token`, `signature`, `nonce`, `identity_key`, `display_name`, `body`, … as a safety net (tested) | Done |
| Secrets in client logs | No HTTP logging interceptor; no logging of keys, tokens or profile data | Done |
| Analytics or crash reporters leaking data | None included | Done |
| Push payloads | No push yet. Future pushes will be content-free wake-ups | **Planned** |

### Abuse

| Threat | Mitigation | Status |
|--------|------------|--------|
| Mass account creation (no phone number to limit it) | Per-IP rate limit on auth endpoints (30/min) | **Gap**: in-memory and per-instance; IPs are cheap. Evaluate proof-of-work or anonymous rate-limit tokens before public launch |
| Challenge-table flooding | Rate limit, 60 s TTL, periodic janitor | Done |

### Supply chain

| Threat | Mitigation | Status |
|--------|------------|--------|
| Tampered Gradle distribution | Wrapper pins the distribution SHA-256; CI validates the wrapper jar | Done |
| Tampered libsignal | Built from a pinned tag (`v0.104.0`) from source on the server; Android uses Signal's official Maven artifacts at a pinned version | Partial: pin the tag's commit hash and enable Gradle dependency verification |
| Mutable container tags | Postgres pinned by major version; Chainguard MinIO images only offer `:latest` | **Gap**: pin by digest |

## Phase 2: messaging, contacts, push

### What the server learns

| Data | Stored? | Notes |
|---|---|---|
| **Message content** | **Yes, plaintext, until delivered** | **Phase 2 gap, by design of the brief.** Payloads are opaque to server *code* (never parsed or logged), but the operator or a DB thief can read undelivered messages. The app shows a "not end-to-end encrypted yet" notice in every chat. Phase 4 encrypts payloads with libsignal with no server change. |
| Who messages whom, and when | Yes, until delivery (envelope rows) | Sender, recipient, conversation ID, timestamps, size. Deleted on acknowledgement or after 30 days. **Gap**: sealed sender to be evaluated in Phase 4. |
| Dedup tombstones `(sender, message_id, time)` | Yes, 30 days | Needed for exactly-once; reveals sending activity, not recipients. |
| Delivery receipts | Yes, until delivered | The server knows delivery happened anyway. |
| Read receipts | As opaque envelopes | Off by default. Plaintext in Phase 2; encrypted in Phase 4. |
| Typing indicators | Never stored | Off by default; relayed to live connections only. |
| Online presence | In memory only | Which users are connected right now; never persisted or logged. |
| Push token | Yes (one per account) | FCM token; deleted when FCM reports it unregistered. |

### Threats and mitigations

| Threat | Mitigation | Status |
|---|---|---|
| Sender spoofing | Sender ID always comes from the authenticated connection, never from the frame | Done, tested |
| Message injected into another conversation | 1:1 conversation ID derived on the device from the two user IDs; client-supplied ID ignored for incoming | Done, tested |
| Replay or duplicate delivery | Server dedup tombstones; device unique message IDs; ack only after the database commit | Done, tested (server and device) |
| Message loss on crash or restart | "Sent" means committed in Postgres; outbox persisted on device; envelope deleted only after ack | Done, tested (incl. server restart, killed recipient) |
| Reordering | Per-recipient advisory lock and sequence-ordered streaming; one-in-flight outbox | Done, tested (concurrent senders) |
| Flooding a recipient or the server | 20 sends/s per connection, 64 KiB payload cap, 30-day retention | Partial: per-connection only; no per-recipient quota yet |
| Ghost connections hiding offline users | Heartbeat; offline marked before the close handshake | Done, tested |
| Push revealing content | Data-only `{"t":"wake"}`; no content, sender, or conversation | Done, tested |
| Push metadata to Google | FCM learns wake-up timing per device; Firebase Installations issues an ID. Delivery-metrics telemetry (datatransport) excluded from the build | Accepted; UnifiedPush can be added behind the same interface |
| Notification content on the lock screen | VISIBILITY_PRIVATE with a public "New message" version | Done |
| Activity metadata (read, typing) | Off by default and reciprocal; typing never stored | Done |
| Contact key substitution by the server | QR codes carry the key out of band; keys are pinned; any change is flagged and must be acknowledged; safety numbers verify in person | Done (see Phase 3) |
| Unknown sender spam | Strangers' messages land in Requests (accept or decline); no read receipts until accepted | Partial: no blocking yet |

## Phase 3: QR contacts, verification, usernames

### What the server learns

| Data | Stored? | Notes |
|---|---|---|
| Username (`name.42`) | Yes, if claimed | Optional public handle. Lookups need the exact handle and are rate-limited (10/min per IP). Numbers are random, so they don't reveal how many people share a nickname. |
| Who looked up whom | No | Lookups are not logged. |
| Contact requests | As envelopes until delivered | Contain the requester's name and identity key, in plaintext until Phase 4. |
| Safety numbers, verified state | No | Device only. |

### Threats and mitigations

| Threat | Mitigation | Status |
|---|---|---|
| Malicious QR (crash, injection, oversized data) | Length check before decoding, strict format, exact field lengths, libsignal key validation; never throws; fuzzed in tests | Done, tested |
| QR redirecting the app to an attacker's server | Codes naming another server are rejected; the app never changes servers from a QR | Done, tested |
| Server substituting the key when you scan | The server must report the same key as the code; on mismatch nothing is added | Done, tested |
| Server substituting the key later | Key re-checked on every chat open; never replaced silently; Verified cleared; sending blocked until acknowledged | Done, tested on devices |
| Server flip-flopping keys to trick acknowledgement | The held key follows the latest server answer and the warning stays | Done, tested |
| Forged key inside a contact request | Cross-checked against the server; difference flagged | Done, tested |
| Server swapping keys on both sides consistently | Only detectable by comparing safety numbers in person, which the Verify screen supports | Accepted; verification is the defence |
| Username enumeration | Exact handles only; random 2–3 digit numbers; per-IP rate limit | Partial: a determined attacker can still probe numbers slowly |
| Screenshots of QR codes shared remotely | A QR is not a secret (ID and public key); scanning alone never marks Verified | By design |
| Camera frames leaking | Analysed in memory only; never stored or sent | Done |

## Accepted Phase-1 limitations (summary)

1. Display names are stored in plaintext on the server.
2. Losing the device means losing the identity. There is no backup or recovery,
   by design for now; a recovery design must not hand the server key material.
3. No app lock, no screen-security flag, no incognito keyboard flag yet.
4. Spam resistance is weak (IP rate limit only).
5. No production TLS endpoint or certificate pinning yet.
6. Message payloads (including contact requests) are plaintext on the server
   until delivered, until Phase 4.
7. Usernames and server-looked-up contacts start on trust-on-first-use; only
   safety-number verification proves the key.

## Review triggers

Revisit this document whenever we add or change: anything stored on the
server, anything logged, any new key or secret, push notifications, contact
discovery, backups or multi-device, or the auth message formats.
