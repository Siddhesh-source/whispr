# Whispr threat model

Status: covers identity, registration, sign-in and local storage; 1:1
messaging and push; QR contacts, verification and usernames; end-to-end
encryption with libsignal; groups and media; and the beta hardening (message
actions, session revocation, account deletion, certificate pinning). The
ranked findings for the beta are in `docs/SECURITY_REVIEW.md`; retention
periods in `docs/DATA_RETENTION.md`. "Gap" marks a known weakness we have accepted for now, each with a plan.

## Assets

| Asset | Where it lives | Why it matters |
|-------|----------------|----------------|
| Identity private key | Device only: wrapped by AndroidKeyStore, in app memory while running | Whoever holds it *is* the user: can sign in and, later, impersonate them to contacts |
| Message content | Devices only; the server relays and stores ciphertext | Confidentiality of conversations |
| Session state and private prekeys | Device only, in the SQLCipher database | Whoever holds them can decrypt messages in flight to this device |
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
| Stolen token reused | 15 min lifetime; bearer over TLS; `POST /v1/auth/logout` revokes it; open WebSockets close (4001) when their token expires or is revoked | Done, tested |
| Account deleted with a stolen token | `DELETE /v1/me` also needs a fresh challenge signed with the delete-only label `whispr-delete-v1` | Done, tested |
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
| Screenshots or recents thumbnails | `FLAG_SECURE` from the first frame, following the Screen security setting (on by default) | Done |
| Data left behind after leaving | Account deletion erases the database, wrapped keys, their Keystore entries, media and caches, after the server confirms | Done, tested |
| Keyboard learning typed messages | Compose has no API for `IME_FLAG_NO_PERSONALIZED_LEARNING` | **Gap**: add via a platform text-field interop when messaging ships |
| Avatar leaking location | Re-encoded on import, which strips EXIF | Done |

### Transport

| Threat | Mitigation | Status |
|--------|------------|--------|
| Eavesdropping or modification | HTTPS only in release (system CAs, no user CAs); TLS 1.2+ with modern suites; the release build refuses a non-HTTPS server URL | Done, tested |
| Rogue CA / TLS interception | SPKI pinning, at least two pins (live and backup); a release build without pins fails unless `whispr.allowUnpinned=true` is passed deliberately | Done, tested (`TlsPolicyTest`) |
| Dev cleartext leaking to release | Cleartext allowed only in the *debug* network config, and only for loopback | Done |

The confidentiality of *messages* will not depend on TLS: libsignal end-to-end
encryption protects content even from a server or network that defeats TLS.

### Malicious server

| Threat | Mitigation | Status |
|--------|------------|--------|
| Server impersonates a user | Server never holds private keys; it cannot produce users' signatures | Done |
| Server substitutes identity keys when a contact is added (MITM) | Contacts are added by scanning a QR code that carries the identity key, verified out of band; key changes will be surfaced to users | **Planned** (Phase 2) |
| Server reads messages | E2EE via libsignal (PQXDH, then the Double Ratchet); the server stores ciphertext only | Done, tested (see End-to-end encryption) |
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
| Mass account creation (no phone number to limit it) | Per-IP limits: 30/min on auth endpoints, 10/hour on registration; client IPs resolved through `TRUSTED_PROXIES` so the limits work behind the TLS proxy | **Gap**: in-memory and per-instance; IPs are cheap. Proof-of-work or anonymous rate-limit tokens are on the roadmap |
| Authenticated API abuse | 120 requests/min per user on every authenticated route, plus per-route limits | Done, tested |
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
| Message content | Ciphertext only, until delivered | Encrypted end to end; see End-to-end encryption below. |
| Who messages whom, and when | Yes, until delivery (envelope rows) | Sender, recipient, conversation ID, timestamps, size. Deleted on acknowledgement or after 30 days. **Gap**: no sealed sender. |
| Dedup tombstones `(sender, message_id, time)` | Yes, 30 days | Needed for exactly-once; reveals sending activity, not recipients. |
| Delivery receipts | Yes, until delivered | The server knows delivery happened anyway. |
| Read receipts | As encrypted envelopes | Off by default. |
| Typing indicators | Never stored | Off by default; encrypted; relayed to live connections only. The server still sees *when* someone types. |
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
| Flooding a recipient or the server | 20 sends/s per connection, 64 KiB payload cap, 30-day retention, at most 1,000 undelivered envelopes per sender and recipient (`recipient_full`) | Done, tested |
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
| Contact requests | As encrypted envelopes until delivered | Name and identity key are inside the ciphertext. |
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
3. No app lock and no incognito keyboard flag yet (screen security is done).
4. Spam resistance is weak (IP rate limit only).
5. Certificate pinning is done; pins must be rotated together with an app
   release (`docs/DEPLOYMENT.md`).
6. Usernames and server-looked-up contacts start on trust-on-first-use; only
   safety-number verification proves the key.

## End-to-end encryption

Every envelope payload (text, receipts, contact requests, typing, session
resets) is encrypted with libsignal: PQXDH for the first message, then the
Double Ratchet. Plaintext is padded to a multiple of 160 bytes before
encryption. Private keys and session state never leave the device's SQLCipher
database. Design: `docs/designs/end-to-end-encryption.md`.

### What the server still sees

| Data | Notes |
|---|---|
| Routing metadata | Sender, recipient, conversation ID, timestamps, and the **padded** size of each envelope |
| Prekey consumption | Public prekeys, and how fast each user's one-time keys are fetched |
| Registration ID | Uploaded with the keys; stable per install |
| Delivery events | When each envelope is acknowledged (the server sends the "delivered" receipt) |
| Push timing | When a device is woken |
| Typing timing | That one user sends a transient frame to another, and when; the frame itself is encrypted |

It no longer sees message text, read receipts, contact requests (names and
keys), or session-reset requests.

### What a malicious server can still do

| Action | Effect | Bound |
|---|---|---|
| Inject garbage envelopes | The recipient shows a "couldn't decrypt" placeholder and asks the claimed sender to resend | Placeholders are scoped to the authenticated sender; nothing is shown as a real message |
| Force session resets | Repeated failures make devices rebuild sessions | At most one reset per peer every 5 minutes; each failed message gets at most 3 attempts and is given up after 30 days |
| Drain one-time prekeys | New sessions fall back to the last-resort Kyber key and the signed prekey (still post-quantum, less forward secrecy for the first message) | Bundle fetches are rate limited per requester and per requester/target pair; devices top up their keys |
| Drop, delay or reorder messages | Messages arrive late or not at all | Not preventable; the sender sees "Sent" without "Delivered" |
| Substitute an identity key | Messages under the new key are **held, unread**, and sending pauses until the user acknowledges the change | Identity keys are immutable on the server, so a key change can only come from the server itself; safety-number verification detects it |

### Threats and mitigations

| Threat | Mitigation | Status |
|---|---|---|
| Server or DB thief reads messages | Payloads are libsignal ciphertext; CI dumps the server database and captures traffic and checks a marker message never appears | Done, tested (CI `e2e` workflow) |
| Replayed envelope under a new transport ID | libsignal rejects reused message keys and base keys; dropped silently, no placeholder | Done, tested |
| Tampered ciphertext | MAC failure: placeholder plus a resend request; the resend replaces the placeholder in place | Done, tested |
| Reset asking a peer for messages sent to someone else | A reset answer only covers messages sent to the requesting peer | Done, tested |
| Message from a contact whose key changed | Held encrypted, never shown, until acknowledged | Done, tested |
| Envelope that crashes processing (poison) | Not acknowledged, retried; after 3 attempts (counted across restarts) it becomes a placeholder | Done, tested |
| Key material read from the device's disk | Sessions and prekeys live in the SQLCipher database; a device test checks no private key bytes appear in the file | Done, tested on device |
| Harvest now, decrypt later with a quantum computer | PQXDH (ML-KEM) protects session establishment | Done |

## Groups and media

Design: `docs/designs/groups-and-media.md`. A group message is encrypted once
with the sender's libsignal sender key and fanned out by the server
(`send_multi`). Sender keys travel only over pairwise sessions. Group state
(name, picture, members, roles, invitees) is sent by admins over pairwise
sessions; the server has no group tables. Attachments are encrypted on the
device with a fresh AES-256-GCM key (libsignal); the key and SHA-256 digest
travel inside the encrypted message, and only the ciphertext is uploaded.

### What the server still sees

| Data | Notes |
|---|---|
| Recipient set of each group message | Needed for fan-out. Together with the stable group conversation ID, it reveals membership and group size over time |
| Group activity | Who sends to the group, when, and the padded size |
| Attachment metadata | Uploader, ciphertext size (**not padded**, so roughly the file size), upload time, and which accounts download it and when |
| Group control traffic | That an admin sends pairwise envelopes to every member at once (the content, including names and pictures, is encrypted) |

It does not see group names, pictures, member lists, roles, invitations,
reactions, attachment keys or attachment contents.

### What a malicious server can still do

| Action | Effect | Bound |
|---|---|---|
| Drop or delay group updates | Members briefly disagree about the group (who is in it, its name) | Updates carry revisions; any later update converges everyone. Removals are tombstones that no concurrent update can undo |
| Drop a sender-key distribution | The member can't read that sender's group messages; they are held encrypted | Distribution messages are resent through the session-reset protocol; held messages expire after 30 days |
| Keep sending to a removed member | Nothing readable: the remaining members rotated their sender keys, and fan-out is computed by each sender from its own member list | Tested: the removed member's device cannot decrypt messages sent after the rotation, even given the ciphertext |
| Serve a different blob | The digest check fails before decryption; the attachment is shown as unverifiable | Tested |
| Delete blobs early | The attachment shows as no longer available | Not preventable |
| Collude with a malicious admin | The admin can list a fake identity key for a member who is not our contact | An existing contact pin always wins; a mismatch with the server flags a key change. Verify safety numbers with members you care about |

### Threats and mitigations

| Threat | Mitigation | Status |
|---|---|---|
| Removed member reads later messages | Every remaining member starts a new sender-key distribution on removal or leave; queued messages are re-addressed to current members only | Done, tested (JVM and live) |
| Non-admin changes the group | Clients accept state only from someone who is an admin in their current view | Done, tested |
| Concurrent admin updates resurrect a removed member | Removal tombstones merge from every valid update | Done, tested |
| Group message injected into another group | The decrypted payload must name the group its sender's distribution belongs to, and the sender must be a current member | Done |
| Object storage or a DB thief reads media | Blobs are AES-256-GCM ciphertext; CI scans the bucket and DB for a marker | Done, tested (JVM, live, CI `e2e`) |
| Photo reveals location or camera | Images are decoded and re-encoded (EXIF dropped); camera file names are not sent | Done, tested on device |
| Document reveals author metadata | Files are sent byte for byte | Accepted; the UI does not strip documents |
| Decrypted media left on disk | Blobs stay encrypted at rest; images and voice are decrypted in memory; "Open" copies go to `cache/open` and are deleted on the next start | Done |
| Upload abuse | 25 MiB limit, 30 uploads a minute per user, 30-day retention | Done, tested |

### Accepted limitations

- Admin roles are enforced by clients only, since the server knows no groups.
- A member who hasn't yet processed a removal can still send to the removed
  member under the old key (as in Signal). The window closes when they
  process it.
- Sender keys have forward secrecy along the chain but no post-compromise
  security until the next rotation. We rotate on removal and leave only. See
  `docs/MLS.md`.
- Groups have no resend protocol for undecryptable content messages; they
  become "couldn't be recovered" placeholders.
- No read receipts or typing indicators in groups.
- Attachment sizes are not padded.

## Message actions, sessions and accounts (beta)

Design: `docs/designs/security-polish-release.md`.

### What the server learns

Nothing new about content. Replies, forwards, deletes, timer changes and
reactions are ordinary encrypted payloads; the server cannot tell them from
text messages except by size. New server-side state: none beyond the
logout and deletion requests themselves.

### Threats and mitigations

| Threat | Mitigation | Status |
|---|---|---|
| Someone deletes another person's message "for everyone" | Receivers apply a delete only from the target's author (the authenticated sender), only for messages from that author, within 24 hours (+1 h clock slack) | Done, tested (forged delete ignored) |
| A resend resurrects a deleted message | Deleted rows are tombstones; placeholder recovery skips them | Done |
| Disappearing-message timer changed by an outsider | A group timer is applied only from the group's sender-key path by a member; the newest change wins; every change shows as a notice | Done, tested |
| Disappearing messages kept by the recipient | Cooperating apps delete them (from send for ours, from read for theirs) along with attachments, reactions and notifications; a recipient can always photograph the screen | By design; documented |
| Forwarded media links two conversations | Forwarding decrypts and re-encrypts under a fresh key and uploads a new blob | Done, tested |
| Quoted text leaks after deletion | Quotes are references resolved locally; a deleted original shows as "not found" | Done |
| Search leaks queries | Search runs on the phone over the SQLCipher database; nothing is sent | Done |
| Copied text in clipboard history or keyboard suggestions | The clip is marked sensitive | Done |
| Tapjacking a security confirmation | Verify, key-change acknowledgement and delete account ignore touches while obscured | Done |
| Rate limits shared by everyone behind the proxy | `TRUSTED_PROXIES`; `X-Forwarded-For` read only from them, rightmost untrusted hop | Done, tested |

## Camera, GIFs, status and calls (beta.2)

Design: `docs/designs/camera-gifs-status-calls.md`.

### What the server learns

- **Status:** one encrypted upload per photo status, then one small
  envelope per contact (it learns your contact count and when you post, as
  it already learns who you message). Every viewer downloads the same blob
  ID, so the server can group the people who viewed one photo.
- **Calls:** call signaling is ordinary encrypted envelopes. When a call
  goes through the relay, coturn sees both phones' IP addresses, the time
  and the amount of (encrypted) media, and the TURN username carries your
  account ID. A direct call reveals each side's IP address to the other;
  "Relay calls through the server" hides it, at the cost of the server
  seeing those addresses instead.
- **GIFs and camera:** nothing new: they are image attachments.

### Threats and mitigations

| Threat | Mitigation | Status |
|---|---|---|
| A server or network attacker joins or listens to a call | Media is DTLS-SRTP; the DTLS fingerprints are in the SDP, which travels only inside libsignal-encrypted payloads between pinned identities. A swapped certificate fails the handshake | Done (WebRTC is the first protocol crypto outside libsignal; documented in ARCHITECTURE) |
| Strangers ring you or post to your Status | Calls and statuses are accepted only from accepted, visible contacts whose key hasn't changed | Done, tested |
| A late offer rings long after the caller gave up | Rings only if the server accepted it in the last 45 s (server clock, not the sender's); older offers become missed calls | Done, tested |
| Status kept past 24 hours | Receivers clamp the start time to their own clock and delete at 24 h, with the photo blob; queued fan-out older than 24 h is dropped | Done, tested |
| Someone deletes another person's status | `status_delete` only removes the sender's own status | Done, tested |
| Viewing a status reveals you read it | Superseded in beta.3: a `status_seen` goes to the author only while your read receipts are on | See below |
| GIF or WebP metadata (XMP, EXIF, comments) leaks location or software | Sent byte for byte but rebuilt block by block, keeping only what draws the frames; malformed files are refused | Done, tested |
| Camera photo keeps EXIF | Re-encoded like any picked photo; the temp file is deleted after encryption | Done |
| The relay used to reach internal services | coturn denies private, loopback, link-local and metadata peers | Done |
| Relay bandwidth exhausts the free tier | Per-session and total caps | Done |
| TURN credentials reused | Valid 10 minutes, bound to the account | Done |
| A tampered in-app update | The APK must match the SHA-256 in the release manifest, and Android installs it only if it is signed with the installed app's key; the manifest and APK come over HTTPS from GitHub | Done, tested (`UpdaterTest`) |
| Update checks reveal who uses Whispr | Twice a day to GitHub, which sees the IP address; off switch in Settings; development builds never check | By design; documented |

### Accepted limitations

- Without push configured, a phone rings only while Whispr is connected.
- Group calls are not supported.
- Status goes to all accepted contacts; there are no per-contact lists yet.

## Private accounts, profiles, backups (beta.3)

Design: `docs/designs/whatsapp-parity.md`.

### What the server learns

- **Requests:** a contact request and its acceptance are ordinary encrypted
  envelopes, indistinguishable from messages. The server already saw who
  writes to whom.
- **Profile photos, status views and likes, group names:** encrypted
  envelopes to people you're connected with. Nothing new in the clear.
- **Backups:** nothing. They never go to the server; the file sits in the
  phone's Downloads (or a folder you picked), where other apps with storage
  access, a cloud sync app, or anyone with the file can copy it.

### Threats and mitigations

| Threat | Mitigation | Status |
|---|---|---|
| Strangers message, call or watch someone who never accepted them | Only `contact_request` and `contact_accept` pass before both sides accepted; text, media, reactions, timers, calls, statuses, profiles and status views from anyone else are dropped on the receiving phone, leaving no trace, and the sending side refuses too | Done, tested (`PrivateContactsTest`) |
| A forged acceptance | `contact_accept` counts only from someone we sent a request to | Done |
| Request spam | Each request is one row the user can decline; the sender learns nothing either way. No rate limit beyond the server's per-account send limits yet | Accepted for beta |
| A shared code used to add you | A code only lets someone ask; you still accept. It carries no phone number | By design |
| A stolen backup file | AES-256-GCM under a 256-bit random recovery key that only the user holds (shown in Settings, never sent anywhere); per-file key from HMAC-SHA256 over a random salt; chunk AAD and counter nonces reject truncated, reordered or altered files | Done, tested (`BackupCipherTest`) |
| A stolen phone reveals the recovery key | Settings can show it again (behind the unlocked phone and screen security); wrapped by a Keystore key at rest | Accepted |
| Restoring an old backup | It brings back older ratchet state; messages that then fail to decrypt go through the existing session reset. One-time prekeys used since the backup are gone, so the first message from some contacts may need a reset | Accepted |
| A restored backup of a deleted account | Sign-in fails; the user starts over | By design |
| Status views reveal who reads what | Views are sent only while read receipts are on (reciprocal, like message ticks); likes are explicit | By design |

## Review triggers

Revisit this document whenever we add or change: anything stored on the
server, anything logged, any new key or secret, push notifications, contact
discovery, backups or multi-device, the auth message formats, group state rules, or attachment handling.
