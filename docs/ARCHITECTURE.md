# Whispr architecture

Status: draft through Phase 2 (identity, authentication, app shell, 1:1
messaging). It describes what exists today and marks what is planned.

## Goals and non-negotiables

- End-to-end encrypted 1:1 and group chat, QR-based contact adding, encrypted media.
- No ads, tracking, feeds, channels, or phone numbers.
- **All protocol cryptography comes from [libsignal](https://github.com/signalapp/libsignal).**
  No custom crypto. The only non-libsignal crypto is platform storage
  encryption (AndroidKeyStore AES-GCM, SQLCipher) and hashing of opaque tokens.
- The server treats message bodies as opaque bytes and keeps only the
  metadata needed for delivery.
- No plaintext content or key material in logs, push payloads, or analytics.
  There is no analytics.

## Repository layout

```
android/                 Kotlin, Jetpack Compose
  app/                   UI layer: screens, ViewModels, navigation, DI entry point
  core/designsystem/     Theme tokens and reusable components (no business logic)
  domain/                Pure Kotlin: models, repository interfaces, use cases
  data/                  Repository implementations: libsignal, Keystore, Room+SQLCipher, OkHttp
server/                  Go service
  cmd/whisprd/           Entry point
  internal/auth/         Registration, challenge-response, tokens
  internal/messaging/    WebSocket gateway, opaque envelope store and relay
  internal/contacts/     User lookup for adding contacts (by ID now, QR in Phase 3)
  internal/push/         Push token registration, content-free FCM wake-ups
  internal/sigverify/    libsignal signature verification via cgo (libsignal-ffi)
  internal/platform/     db, httpx, logging
  migrations/            SQL migrations (embedded, applied at startup)
docs/                    This file, THREAT_MODEL.md
docker-compose.yml       Postgres, MinIO, server
```

## Android

### Layers

```
 app (UI)  ──▶  domain  ◀──  data
   │                          │
   └──▶ core:designsystem     ├─ libsignal (identity keys, signatures)
                              ├─ AndroidKeyStore (wraps secrets at rest)
                              ├─ Room + SQLCipher (local database)
                              └─ OkHttp (server API)
```

- **domain** has no Android or I/O dependencies. It defines `IdentityRepository`,
  `AccountRepository`, `AuthRepository`, `ConnectivityRepository`, and use cases
  (`CompleteOnboardingUseCase`, `ObserveStartDestinationUseCase`,
  `RestoreSessionUseCase`, `DisplayNameValidator`).
- **data** implements them. The identity private key never leaves this layer:
  callers get the public key and signatures only.
- **app** holds stateless screens (`OnboardingScreen`, `ChatsScreen`,
  `SettingsScreen`), Hilt ViewModels exposing a single `StateFlow` of UI state,
  and `SessionKeeper`, which keeps the user signed in.
- **core:designsystem** is the only place colors, type, spacing and shapes are
  defined. `:app:checkDesignTokens` fails the build if screens contain color or
  dp/sp literals.

### Every screen handles four states

| Screen      | Loading                 | Empty                     | Error                                   | Offline                    |
|-------------|-------------------------|---------------------------|-----------------------------------------|----------------------------|
| Onboarding  | button progress         | (form)                    | inline message, retry via Continue      | banner; submit still allowed |
| Chats       | `LoadingState`          | `EmptyState`              | `ErrorState` when identity is rejected; banner when server unreachable | banner |
| Settings    | `LoadingState`          | n/a                       | `ErrorState` if local profile unreadable | banner + connection row    |

### Identity and local secrets

On first launch (when the user taps Continue in onboarding):

1. `IdentityKeyPair.generate()` (libsignal) creates the long-term identity.
2. The serialized key pair is encrypted with AES-256-GCM under a
   non-exportable AndroidKeyStore key (`whispr.identity.wrap.v1`,
   StrongBox-backed when available). AAD `whispr-identity-v1` binds the blob
   to its purpose.
3. The blob is written atomically to `noBackupFilesDir/secrets/identity.v1.bin`.

The local database is SQLCipher. Its 256-bit passphrase is random, wrapped
the same way under a separate Keystore key (`whispr.database.wrap.v1`) and
stored in `database-key.v1.bin`. `LazyKeyOpenHelperFactory` defers the Keystore
unwrap and native library load until the database is first opened on a
background thread.

Backups and device transfer are disabled (`allowBackup=false`, data
extraction rules exclude everything).

### Avatars

Picked through the system photo picker (no storage permission). The image is
decoded, scaled to at most 512 px and re-encoded as JPEG, which drops EXIF
data (GPS location, camera details). It is stored in `noBackupFilesDir/avatar/`
and never uploaded in Phase 1.

## Server

Go, chi router, pgx, golang-migrate. Modules register routes on a shared
router (`internal/server`). Unauthenticated auth endpoints are rate-limited
per client IP. Request logs contain method, route pattern, status and latency
only: no headers, query strings, bodies, or IP addresses.

### Data model (Phase 1)

| Table             | Contents                                                        | Retention |
|-------------------|-----------------------------------------------------------------|-----------|
| `users`           | `id` (random UUIDv4), `identity_key` (33 B), `display_name`, `created_at` | account lifetime |
| `auth_challenges` | `id`, `user_id`, `nonce` (32 B), `expires_at`, `used_at`         | deleted after expiry (60 s) |
| `auth_tokens`     | `token_hash` (SHA-256), `user_id`, `expires_at`                 | deleted after expiry (15 min) |

### Signature verification

libsignal has no Go binding, so the server links libsignal's C FFI
(`libsignal_ffi.a`, built from the pinned `v0.104.0` tag in the Docker build)
through cgo, behind the `libsignal` build tag. A binary built without the tag
cannot construct a verifier and refuses to start. Unit tests use a clearly
labelled fake; `go test -tags libsignal` (run in the Dockerfile's `test`
stage and in CI) uses the real library.

## Auth protocol

No passwords and no phone numbers. Possession of the identity private key is
the credential. Signatures are libsignal's identity-key signatures
(`PrivateKey.calculateSignature` / `PublicKey.verifySignature`).

### Registration

```
client                                              server
  │ POST /v1/register                                 │
  │ { identity_key, display_name, signature }  ─────▶ │ validate name, key
  │                                                   │ verify signature over
  │                                                   │   "whispr-register-v1\0" ‖ identity_key ‖ display_name
  │ ◀───── 201 { user_id }   (200 if key already registered)
```

Registration is idempotent per identity key, so a client that crashed after
the server accepted it can safely retry.

### Sign-in (challenge-response)

```
  │ POST /v1/auth/challenge { user_id }  ───────────▶ │ store single-use 32-byte nonce, 60 s TTL
  │ ◀───── { challenge_id, nonce, expires_at }
  │ sign "whispr-auth-v1\0" ‖ user_id (16 B) ‖ nonce
  │ POST /v1/auth/verify { challenge_id, signature } ▶ │ consume challenge first (one attempt),
  │                                                   │ then verify signature
  │ ◀───── { token, expires_at }  (random 256-bit, 15 min; server stores SHA-256 only)
  │ GET /v1/me   Authorization: Bearer <token>  ────▶ │
```

- The two labels give domain separation: a registration signature can never be
  replayed as a login and vice versa.
- Wire formats are pinned by identical test vectors in
  `server/internal/auth/messages_test.go` and
  `android/data/src/test/.../AuthMessagesTest.kt`. Changing a format means
  bumping the version label.
- Failures (unknown, expired or used challenge, bad signature) all return the
  same `401 auth_failed`.

### Staying signed in

The token lives only in memory (`SessionAuthRepository`). Nothing session-related
is written to disk. "Signed in after restart" therefore means: the account row
says we are registered, so the app opens on Chats, and `SessionKeeper` silently
repeats challenge-response with the stored identity key.

`SessionKeeper` runs while the device is online and the account is
registered. It retries transient failures with exponential backoff (2 s up to
60 s). It does not automatically retry an identity the server rejected; the
user retries from the Chats screen. `TokenSource.bearerToken()` re-authenticates
on demand when the token is within 30 s of expiry.

## Local development

```sh
docker compose up --build                     # backend on 127.0.0.1:8080
adb reverse tcp:8080 tcp:8080                 # device/emulator loopback → host
cd android && ./gradlew installDebug
```

The debug build uses `http://127.0.0.1:8080/` through `adb reverse`, not
`10.0.2.2` or a LAN IP. Android 17 blocks local-network addresses for apps
targeting it unless they hold `ACCESS_LOCAL_NETWORK`, a runtime permission a
messenger with an internet-hosted server should never ask for. Cleartext HTTP
is allowed only in the debug network security config, and only for loopback.

## Testing strategy

| Layer            | What runs where |
|------------------|-----------------|
| Server unit      | `go test ./...`: service logic with an in-memory store and a fake verifier; HTTP handlers; config; logging redaction |
| Server + DB      | Store contract tests against real Postgres (`WHISPR_TEST_DATABASE_URL`) |
| Server + libsignal | `docker build --target test server` runs everything with `-tags libsignal` |
| Domain           | Use cases and name validation (JVM) |
| Data (JVM)       | Real libsignal (desktop natives) for identity and signing; MockWebServer fake that verifies signatures with libsignal; Room DAO via Robolectric |
| Data (device)    | Keystore wrap/unwrap and tamper detection, identity persistence, SQLCipher file is encrypted |
| Live end-to-end  | `LiveServerTest` against the real server with real libsignal on both sides (set `WHISPR_SERVER_URL`) |
| App              | ViewModels and `SessionKeeper` with fakes; every screen state with Compose tests |
| Design system    | Contrast (WCAG AA) for every pairing, accessibility semantics, Roborazzi screenshots |

## Messaging (Phase 2)

### Envelope

| Field | Set by | Server use |
|---|---|---|
| `id` (message ID, UUID) | sender | dedup key with the sender |
| `conversation_id` | sender | opaque; returned to the recipient |
| `sender_id` | **server**, from the authenticated connection | routing, receipts |
| `recipient_id` | sender | routing |
| `client_ts` / `server_ts` | sender / server | display / retention |
| `seq` | server | per-recipient delivery order |
| `payload` (≤ 64 KiB) | sender | **never parsed or logged** |

The payload is the only place message semantics live: `{"t":"text","body":…}`,
`{"t":"read","ids":[…]}`, `{"t":"typing"}`. Phase 4 encrypts these bytes with
libsignal; the server and schema do not change.

### Protocol (WebSocket `/v1/ws`, bearer token in the upgrade request)

```
sender                               server                                recipient
  │ send{id, conv, to, payload} ───▶ │ lock(recipient); dedup(sender,id);
  │                                  │ INSERT envelope (seq)  ── committed ──
  │ ◀── accepted{id, seq}  ("Sent")  │ wake recipient's connection, or push
  │                                  │ envelope{seq, …} ──────────────────────▶ │ store in DB (unique id)
  │                                  │ ◀────────────────────────────── ack{seq} │ only after commit
  │                                  │ DELETE envelope; queue "delivered" receipt
  │ ◀── envelope{kind:delivered, ref_id} ("Delivered")
```

- **Exactly once** = at-least-once delivery + idempotence on both sides. The server
  keeps a tombstone `(sender, message_id)` for 30 days, so retries are re-acked
  without storing twice. The device ignores message IDs it already has. Envelopes
  are deleted only after the recipient acknowledges them, and the app
  acknowledges only after the database write commits.
- **Order:** inserts for one recipient are serialized with a transaction-scoped
  advisory lock, so sequence order equals commit order. Each connection streams
  envelopes by `seq > cursor` from Postgres, so backlog and live delivery share
  one ordered path. The app sends its outbox one envelope at a time.
- **Server restart:** "Sent" means committed to Postgres. Only presence (who is
  connected) is in memory; clients reconnect and drain.
- **Heartbeat:** the server pings every 25 s (10 s timeout) and the client every
  20 s. A dead peer is marked offline *before* the close handshake, so new
  messages trigger a push instead of waiting on a ghost connection.
- **Transient frames** (typing) are delivered only to a live connection and
  never stored or queued.
- **Limits:** 20 sends/s per connection (burst 40); rejected with `rate_limited`
  and retried by the client.
- **Retention:** undelivered envelopes and dedup tombstones are purged after 30 days.

### Android

- **Single source of truth:** screens observe Room. Sending writes the message
  (status Sending) and an outbox row in one transaction; `MessagingEngine`
  reconciles with the server in the background.
- **When connected:** registered, online, and either in the foreground, holding
  unsent outbox rows, or within 30 s of a push wake-up. Reconnects use
  exponential backoff (1 s up to 30 s, ±20 % jitter). A 401 at the upgrade
  invalidates the token, forcing a fresh challenge-response.
- **Statuses:** Sending → Sent (accepted) → Delivered (server receipt) → Read
  (peer's read receipt, if both sides enabled them). Permanent rejections become
  Failed and can be retried from the bubble.
- **Conversation IDs** for 1:1 chats are derived on the device from the two user
  IDs. Incoming messages are filed under the derived ID, never the client-supplied
  one, so a sender cannot inject messages into another conversation.
- **Privacy settings:** read receipts and typing indicators are off by default and
  reciprocal (off means you neither send nor see them).
- **Push:** FCM data message `{"t":"wake"}` only. The app connects, fetches,
  stores, and posts a local notification (lock screen shows only "New message").
  Firebase is initialized from build-config values; without them, push is off.
  Firebase's delivery-metrics transport is excluded from the build.
- **Contacts (temporary):** add by pasting an account ID; the server returns the
  display name and identity key. Replaced by QR in Phase 3.

### Testing (Phase 2)

| Where | What |
|---|---|
| Server integration (real Postgres + WebSockets) | real-time delivery and receipts; offline queue exactly once in order; redelivery of unacked; duplicate sends (before and after delivery); server restart; concurrent senders never skip; transient not stored; rejections; replacing connections; heartbeat drops dead peers; sockets survive server timeouts; contact lookup |
| Android engine (scripted gateway) | ordered one-in-flight outbox; offline queue; resend after reconnect; store-before-ack and dedup; incoming order; conversation spoofing; receipts; reciprocal read; rejections; rate limits; unknown senders; typing; token invalidation |
| Live (real server, two clients) | offline burst exactly once in order; delivered statuses; real time both ways; restart without duplicates |
| Two emulators (manual run, 2026-10-04) | real-time chat; network loss and process kill on the recipient; queue counts verified in Postgres |

## Planned next (not built)

- Contacts by QR: the QR code carries user ID and identity key; scanning
  verifies the key out of band (protects against a malicious server
  substituting keys).
- Encryption (Phase 4): libsignal sessions (PQXDH and Double Ratchet via
  libsignal) and prekey upload. Envelope payloads become ciphertext; nothing on
  the server changes.
- Encrypted media in S3/MinIO: encrypted client-side, server stores opaque blobs.
- Encrypted profiles (replaces the plaintext display name on the server).
