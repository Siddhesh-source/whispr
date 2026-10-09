# Whispr architecture

Status: draft covering identity, authentication, the app shell, 1:1
messaging, QR contacts and verification, end-to-end encryption, and encrypted groups and media. It describes what exists today and marks what is planned.

## Goals and non-negotiables

- End-to-end encrypted 1:1 and group chat, QR-based contact adding, encrypted media.
- No ads, tracking, feeds, channels, or phone numbers.
- **All protocol cryptography comes from [libsignal](https://github.com/signalapp/libsignal).**
  No custom crypto. The only non-libsignal crypto is platform storage
  encryption (AndroidKeyStore AES-GCM, SQLCipher), hashing of opaque tokens,
  and call media: WebRTC's DTLS-SRTP, whose certificate fingerprints travel
  only inside libsignal-encrypted signaling, so the media key exchange is
  bound to the pinned identities (the approach Signal's calls take).
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
  internal/messaging/    WebSocket gateway, opaque envelope store and relay (1:1 and group fan-out)
  internal/attachments/  Encrypted attachment blobs in S3-compatible storage, retention janitor
  internal/calls/        Short-lived TURN credentials for the call relay (coturn)
  internal/contacts/     User lookup by ID (used after scanning a QR)
  internal/profile/      Display name, optional usernames (name.42) and lookup
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

The server closes a WebSocket with status 4001 when the token it was opened
with expires or is revoked (`POST /v1/auth/logout`, account deletion). The
engine treats 4001 as "sign in again": it drops the token and reconnects at
once, without backoff. Envelopes are durable and the outbox resends
unacknowledged sends, so nothing is lost.

### Deleting the account

`DELETE /v1/me` needs the bearer token and a fresh challenge signed over
`"whispr-delete-v1" 0x00 || user_id || nonce`. Postgres cascades the delete
to keys, tokens, envelopes, the push token and the username. On the phone,
`SessionAuthRepository.deleteAccount` runs the server delete first and only
then `DeviceWipe`: close and delete the database, delete
`noBackupFilesDir/{secrets,media,avatar}` and the cache, and delete both
Keystore aliases. The app then restarts into onboarding.

### Transport policy

`TlsPolicy` configures the one OkHttp client used for HTTP and the
WebSocket: TLS 1.2+ with modern suites, plain HTTP only for a loopback debug
server, and a `CertificatePinner` from `BuildConfig.CERT_PINS`. Release
builds take `whispr.releaseServerUrl` and `whispr.certPins`; the
`checkReleaseConfig` task fails a release build that lacks an HTTPS URL, two
pins (or `whispr.allowUnpinned=true`) or a signing key.

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
`{"t":"read","ids":[…]}`, `{"t":"typing"}`. These bytes are encrypted end to
end with libsignal (see End-to-end encryption); the server never sees them.

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

## Contacts and verification (Phase 3)

### Contact QR code

`whispr:` + base64url(no padding) of
`version(1)=1 ‖ flags(1)=0 ‖ user_id(16) ‖ identity_key(33) ‖ server_len(1) ‖ server`
where `server` is an origin such as `https://chat.example.org`. About 100
characters, so it fits a small QR code.

Every scanned code is hostile input. `ContactQr.parse`:
- checks the text length (≤ 512) before decoding;
- requires the prefix, strict base64url, version 1 and zero flags;
- checks every length field exactly and rejects trailing bytes;
- validates the key with libsignal;
- accepts only an HTTPS origin (HTTP for loopback in debug builds only), with
  no user info, path or query;
- rejects a server other than ours: the app never switches servers because of a QR.

It never throws; errors become typed results with a calm message.

### Adding a contact

| Way | Key source | Starting trust |
|---|---|---|
| Scan their QR (camera or a picture of it) | The code; the server must report the same key, otherwise nothing is added (`KeyMismatch`) | Unverified |
| Username lookup (`name.42`, exact handle only) | The server (trust on first use) | Unverified |
| Their contact request (they scanned us) | The request; cross-checked against the server and flagged on any difference | Unverified, shown under Requests |

The adder sends a `contact_request` envelope (name and identity key) through
the outbox. The recipient sees it under **Requests** and accepts or declines;
messages from strangers also land there. Opening a request never sends a read
receipt. Both sides then have each other without scanning again.

### Trust states

`Unverified → Verified` only by comparing safety numbers (scanning the peer's
code, or confirming the 60 digits match). `KeyChanged` whenever the server
reports a key different from the pinned one (checked every time a chat opens),
or a request or rescan carries one:
- the pinned key is never replaced silently; the new one is held aside;
- Verified is cleared and sending is blocked;
- the chat shows a warning until the user accepts the new number, which pins
  the *latest* server-reported key and returns to Unverified;
- if the server keeps changing its answer, the held key follows the latest one,
  so accepting can never pin a stale key.

### Safety numbers

Computed and compared only by libsignal: `NumericFingerprintGenerator(5200)`,
version 2, with the 16-byte account IDs as stable identifiers. The screen
shows 12 groups of 5 digits and a QR (`whispr-sn:` + base64url of the scannable
fingerprint); scanning uses `ScannableFingerprint.compareTo`.

### Scanner

zxing-cpp (open source, on-device) on a CameraX preview, plus "Scan from image"
through the system photo picker. Camera frames are analysed in memory and never
stored. Decoded text goes straight to the parser above.

### Testing (Phase 3)

| Where | What |
|---|---|
| QR parser (JVM) | round trip; foreign, malformed, truncated and padded input; bad lengths, flags and keys; hostile server origins; other servers; 15,000 random inputs never throw |
| Safety numbers (real libsignal) | both sides see the same digits; cross-scan matches; another key mismatches; hostile scans |
| Contact trust (scripted server) | scan pins the key and sends a request; lying server adds nothing; key change flagged, sending blocked, verified cleared, acknowledgement; stale pending key; incoming requests; forged request keys; message requests and decline; usernames |
| Server | username claim, lookup, release; nickname validation; distinct numbers; display-name updates; lookup rate limit |
| Device | zxing-cpp decodes what we render, including inverted codes |
| Two emulators (2026-10-04) | database upgrade keeps chats; B scans A's code; A accepts; chat both ways and after restarts; both verify each other; simulated key change on the server shows the warning and blocks sending; acknowledgement restores the original number |

## End-to-end encryption

Design: `docs/designs/end-to-end-encryption.md`. All cryptography is libsignal.

### Server

- `PUT /v1/keys` uploads the registration ID, signed prekey, last-resort Kyber
  key and batches of one-time keys (signatures checked with libsignal).
- The bundle endpoint pops, in one transaction, one EC one-time key (if any)
  and one Kyber one-time key, falling back to the last-resort key, so no
  one-time key is handed out twice. Rate limited per requester and per
  requester/target pair.
- Envelopes are unchanged: the payload is now ciphertext.

### Android (`data`)

- **`SignalStore`** implements libsignal's stores over Room (SQLCipher).
- **`PreKeyMaintainer`** registers keys, tops up one-time keys, and rotates the
  signed and last-resort keys every 7 days. Settings warns while keys could
  not be uploaded.
- **`SessionCrypto`** builds sessions from bundles and encrypts/decrypts on one
  `whispr-crypto` thread. Decryption and the resulting database writes commit
  in one transaction. Wire format: a type byte plus the libsignal message;
  plaintext padded to a multiple of 160 bytes.
- **Outbox lanes:** each recipient has its own lane. A lane parks (no keys yet,
  key changed) without blocking other chats. A message is encrypted once at
  the head of its lane and the ciphertext kept for retries.
- **`IncomingPipeline`:** sender-scoped dedup; replays dropped; envelopes under
  a changed key held encrypted until the user acknowledges; undecryptable ones
  become a placeholder (`Message.notice`) plus a durable reset request.
- **`ResetCoordinator`:** at most one `SessionReset` per peer every 5 minutes,
  coalescing failures; waits while the lane is parked; the 24 h answer window
  starts when the reset is delivered; 3 attempts, 30-day expiry. The peer
  resends the listed messages, which replace their placeholders in place.

### Testing

| Where | What |
|---|---|
| Server | bundle pops are atomic under concurrent fetches; last-resort fallback; signature checks; rate limits |
| Data (JVM, real libsignal) | PQXDH then ratchet; wire format and padding; replays; tampering and in-place recovery; held messages; parked lanes; reset scoping, cooldown, delivery clock, restarts; poison guard |
| Data (device) | the SQLCipher file holds no private key bytes or message text |
| CI `e2e` workflow | live tests against the composed server; a marker message is absent from `pg_dump` and captured traffic |

## Groups and media

Design: `docs/designs/groups-and-media.md`. Threat model: `docs/THREAT_MODEL.md` §Groups and media.

### Server

- **`send_multi`** (WebSocket) stores one copy of a ciphertext per recipient
  in one transaction, under a single dedup tombstone.
  - It locks recipients in sorted order.
  - The fan-out is capped at 100 recipients.
  - An unknown, duplicate or self recipient rejects the whole send.
  - Each recipient's ack produces its own "delivered" receipt.
  - The server keeps no group tables.
- **`internal/attachments`**:
  - `POST /v1/attachments` streams a client-encrypted blob of at most
    25 MiB + 28 bytes into S3-compatible storage under a random ID.
  - `GET /v1/attachments/{id}` streams it back.
  - Uploads are limited to 30 a minute per user, and these handlers set their
    own 5-minute deadlines.
  - A janitor deletes blobs after `ATTACHMENT_RETENTION` (30 days).
  - The `attachments` table (migration 0005) holds the ID, uploader, size and
    upload time.
  - Configure it with `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`,
    `S3_BUCKET` and `S3_USE_SSL`. Without them, media routes answer 503.

### Android (`data`)

- **`GroupManager`** applies the group rules inside crypto transactions:
  - updates are accepted only from an admin, and only if newer
    (revision, then author ID);
  - removals are tombstones that every valid update merges;
  - a new sender-key distribution starts whenever anyone leaves or is removed;
  - members who aren't our contacts become hidden contacts pinned to the
    admin's key (an existing pin always wins).
- **Sender keys:**
  - `SenderKeys` is libsignal's `SenderKeyStore` on SQLCipher.
  - `SessionCrypto` gains `distributionMessage`, `processDistribution`,
    `encryptGroup` and `decryptGroup`.
  - Wire type `0x03` marks a SenderKeyMessage.
- **Group lane:** an outbox entry with `groupId` and fixed `recipients`.
  - Before a message is queued, its sender key goes to anyone who lacks it,
    over their pairwise lane.
  - At the head of the lane, the recipients are intersected with the current
    members, and the message is encrypted once and sent with `send_multi`.
  - It shows Delivered once every recipient has acknowledged it.
- **Incoming group messages:**
  - A message is held encrypted (`held_group_envelopes`) until its sender's
    key or the group state arrives; held messages expire after 30 days.
  - It is shown only if the payload names the group the distribution belongs
    to and the sender is a current member.
- **Media:**
  - `MediaPreparer` re-encodes images (≤ 2048 px JPEG, EXIF dropped, a
    ≤ 16 KiB inline thumbnail) and reads other files byte for byte.
  - `MediaCrypto` uses libsignal AES-256-GCM plus a SHA-256 digest.
  - `MediaService` encrypts, uploads, and then queues the message. On
    receipt it downloads, checks size and digest, and stores the blob still
    encrypted. Plaintext lives only in memory, or in `cache/open` for
    "Open with", which is cleared on start.
- **Reactions:** one per reactor per message (newest wins), in 1:1 chats and
  groups.
- **Room v5** (auto-migration) adds:
  - tables: groups, members, sender keys, key shares, distributions, held
    group envelopes, group sends, attachments, reactions;
  - columns: `contacts.hidden`, `messages.system`, `outbox.groupId` and
    `outbox.recipients`.

### Android (`app`)

- New group and Group info screens: rename, picture, add, invite, make or
  dismiss admin, remove, leave.
- Group invites appear under Requests.
- In chat:
  - attach a photo (system photo picker) or a file;
  - record voice (`RECORD_AUDIO`, asked on first use);
  - long-press to react;
  - group events are shown as centred notices.
- Decrypted files are shared through a FileProvider limited to `cache/open/`.

### Testing

| Where | What |
|---|---|
| Server integration | `send_multi` fan-out, receipts, dedup, atomic rejection, ordering with 1:1 sends, push wake-ups; attachments: round trip, limits, rate limit, retention purge, real MinIO (`WHISPR_TEST_S3_*`) |
| Data (JVM, three real engines on `FakeRelay`) | three users share a group; a removed member gets nothing and can't decrypt captured ciphertext; leave rotates; non-admin and forged updates ignored; rename and picture; invite accept and decline; admin hand-over; key arriving after the message; reactions 1:1 and group; media round trips with only ciphertext stored; tampered and expired blobs |
| Data (JVM, `GroupStateTest`) | concurrent-admin removal tie in both orders; re-add above the tombstone; malformed states; pins win |
| Live (real server + MinIO) | `LiveGroupsTest`: group, picture, removal; the raw stored blob holds no marker |
| Device (emulator) | photos lose GPS, camera make and capture time; the SQLCipher file holds no group name or attachment key |
| CI `e2e` | bucket copy scanned for the marker with the DB dump and traffic |

## Message actions (beta)

Design: `docs/designs/security-polish-release.md`.

### Payloads

All new actions are ordinary encrypted payloads, so the server cannot tell
them apart:

| Payload | Fields | Receiver rule |
|---|---|---|
| `text`, `media` | `q`, `qa` (quoted message and its author), `fwd`, `exp` (timer, s) | Quotes are resolved locally; `exp` fixes the message's timer |
| `delete` | `target`, `ts`, `g`? | Only from the target's author, for that author's message, within 24 h (+1 h clock slack). The row becomes a tombstone; attachment file, reactions and notification go |
| `timer` | `seconds`, `ts`, `g`? | 0–28 days. Newest `ts` wins. Groups: only via the group path from a member. Each change adds a notice |

### Storage (Room v6, auto-migrated)

`messages` gains `quoteId`, `quoteAuthor`, `forwarded`, `deleted`,
`expiresIn` and `expireAt`; `conversation_settings` holds each
conversation's timer and the time of its last change.

- **Disappearing messages.** Outgoing messages start their clock when sent;
  incoming ones when read (`markRead` sets `expireAt`). The engine sweeps
  expired rows on start and every minute, deleting attachments, reactions
  and blob files, and emits `removed` so the notifier cancels the
  conversation's notification.
- **Forward.** Text is re-sent as a new message with `fwd`. Media is
  decrypted in memory and sealed again under a fresh key and uploaded as a
  new blob (`MediaService.forward`).
- **Delete for me** removes the row (and its outbox entry if unsent).
- **Search** is a `LIKE` query over `messages.body` inside the SQLCipher
  database with `%`, `_` and `\` escaped, newest first, at most 100 hits;
  placeholders, deleted rows and system notices are excluded. SQLite folds
  ASCII case only.

### UI

A long press opens an action sheet: react, reply, copy (clip marked
sensitive), forward, delete for me, delete for everyone (own messages within
the window). The composer shows the reply being written; bubbles show the
quote, a forwarded label and a timer icon. The chat bar has the timer menu;
the chat list has search. Settings has Screen security (`FLAG_SECURE`, on by
default) and Delete account.

### Testing

| Layer | What |
|---|---|
| Data (JVM, real libsignal, `FakeRelay`) | `MessageActionsTest`: replies, delete for everyone and forged deletes, delete for me, timers on both sides and from non-members, forwarded media, search escaping |
| Data | `MessagingEngineTest.tokenExpiryCloseDropsTheTokenAndReconnects`; `TlsPolicyTest`; `SessionAuthRepositoryTest` delete flow; `AuthMessagesTest.deleteVector` |
| App (Robolectric) | `MessageActionsUiTest`: action sheet, rendering, reply, timers, search, settings, delete confirmation |
| Server | `security_test.go`, `revoke_test.go`, `server_test.go` route walk; `load_test.go` (opt-in) |

## Camera, GIFs, status and calls (beta.2)

Design and review: `docs/designs/camera-gifs-status-calls.md`.

### Camera and GIFs

- **Camera:** the system camera writes to `cache/camera/` through the
  FileProvider; the file goes through the normal image path (re-encoded,
  EXIF gone) and is deleted after encryption. CAMERA must be granted first
  because the app declares it.
- **GIFs:** from the keyboard (`Modifier.contentReceiver` on a
  `TextFieldState` field), the picker or a paste. `AnimatedImages` keeps the
  bytes but rebuilds GIF and animated-WebP files without comments, XMP or
  EXIF; malformed files are refused; at most 10 MiB. They travel as
  `kind = image` with an animated content type and play with
  `AnimatedImageDrawable` (API 28+, and only while system animations are on).

### Outbox priority (Room v7)

`outbox.priority`: 0 call signaling, 1 messages and controls, 2 status
fan-out; the pump sends `ORDER BY priority, seq`. Status entries older than
24 h are dropped instead of sent.

### Status

```
 post ─▶ [photo: seal + upload once] ─▶ tx{ statuses row + one outbox row per contact (prio 2) }
 receive ─▶ decrypt tx{ accepted contact? ts within 24 h ± 1 h? new (author, sid)? ─▶ statuses row }
 sweep (every minute) ─▶ delete expired rows and their blob files
```

`RoomStatusRepository` owns posting, retry, delete (`status_delete` to the
same audience), viewing (local only) and photo download (verified against
the digest, stored encrypted). The audience is every contact that is
accepted, visible and not key-changed.

### Calls

```
 CallManager (state machine, one call)          WebRtcCallMedia (libwebrtc)
   start/accept/decline/hangup ──────────────▶   offer/answer/ICE, DTLS-SRTP
   signals ◀─▶ CallSignalingRepository ◀─▶ outbox (prio 0) / engine.calls
   CallLogRepository (Room `calls`)
 CallSystem: ring notification (CallStyle, full-screen when allowed),
   CallService (foreground: microphone|camera), CallAudio (mode, speaker,
   ringback, proximity), missed-call notification
```

- Offers ring only if the server accepted them within 45 s; otherwise they
  are logged as missed. Calls and statuses are accepted only from trusted
  contacts. Busy and glare (lower call ID wins) are handled.
- The engine stays connected while a call exists (`setCallActive`).
- ICE servers come from `GET /v1/calls/turn`; "Relay calls through the
  server" sets `iceTransportsType = RELAY`.
- Without FCM, a phone rings only while the app is connected.

### Testing

| Where | What |
|---|---|
| Data (JVM, real libsignal, `FakeRelay`) | `StatusAndCallsTest`: text and photo statuses, one blob for all, retry after a failed upload, author-only delete, strangers and stale statuses dropped, expiry sweep, priority ordering and stale drop, call signals in order with server time, strangers can't ring |
| Data (JVM) | `AnimatedImagesTest`: metadata stripped, loop kept, every truncation refused |
| App (JVM) | `CallManagerTest`: outgoing, incoming, timeouts, decline, busy, missed, stale offers, glare, failures, relay setting; `StatusCallsUiTest`; Roborazzi screenshots |
| Server | `calls_test.go`: credential scheme, TTL, 503, per-user limit; config validation |

## Updates

Releases reach installed apps without a visit to GitHub:

```
tag vX.Y.Z[-beta.N] ─▶ release.yml: signed APK + update.json {versionCode, versionName, apk, sha256, size}
                         published as "latest"
app (release builds): every 12 h, or Settings → Check for updates
  GET github.com/.../releases/latest/download/update.json
  newer versionCode? ─▶ banner "Version X is available" ─▶ Update
  download APK (size-capped) ─▶ SHA-256 must match ─▶ PackageInstaller session ─▶ user confirms
  Android refuses an APK not signed with the installed app's key
```

`versionCode` is derived from `versionName` (MAJOR·1e6 + MINOR·1e4 +
PATCH·100 + beta number, 99 for a final release), in Gradle and in the
workflow. Development builds have no update URL and never check. The check
contacts GitHub (it sees the IP address); Settings can turn automatic checks
off. Tests: `UpdaterTest`.

## Planned next (not built)

- Encrypted profiles (replaces the plaintext display name on the server).
- MLS for groups, if the trade-offs in `docs/MLS.md` change.
