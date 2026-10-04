# Whispr threat model

Status: first draft, covering Phase 1 (identity, registration, sign-in, local
storage). Messaging, contacts and media get their own sections as they are
built. "Gap" marks a known weakness we have accepted for now, each with a plan.

## Assets

| Asset | Where it lives | Why it matters |
|-------|----------------|----------------|
| Identity private key | Device only: wrapped by AndroidKeyStore, in app memory while running | Whoever holds it *is* the user: can sign in and, later, impersonate them to contacts |
| Message plaintext (future) | Devices only | Confidentiality of conversations |
| Local database | Device: SQLCipher, key wrapped by Keystore | Profile now; messages and contacts later |
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

## Accepted Phase-1 limitations (summary)

1. Display names are stored in plaintext on the server.
2. Losing the device means losing the identity. There is no backup or recovery,
   by design for now; a recovery design must not hand the server key material.
3. No app lock, no screen-security flag, no incognito keyboard flag yet.
4. Spam resistance is weak (IP rate limit only).
5. No production TLS endpoint or certificate pinning yet.

## Review triggers

Revisit this document whenever we add or change: anything stored on the
server, anything logged, any new key or secret, push notifications, contact
discovery, backups or multi-device, or the auth message formats.
