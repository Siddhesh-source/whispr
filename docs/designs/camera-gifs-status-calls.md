# Camera, GIFs, Status and Calls

Status: plan (2026-10-09). Scope: four user-facing features for 0.1.0-beta.2.
Every decision below is made; alternatives are listed only where the choice
changes privacy, cost or complexity.

Shared constraints, unchanged from `ARCHITECTURE.md`:

- The server sees only ciphertext and the delivery metadata it already sees.
- No new third-party requests from the app (no GIF search API, no analytics).
- Everything runs on the existing AWS t4g.micro (1 GB) inside the free tier.
- Every failure is shown to the user or written to `docs/failures/`; nothing
  fails silently.

## 0. Summary of choices

| Feature | Choice | Rejected |
|---|---|---|
| Camera | System camera through `ActivityResultContracts.TakePicture` into an app-private FileProvider file, then the existing image path (re-encode, EXIF stripped). | CameraX in-app viewfinder: more code and a CAMERA permission prompt for no privacy gain, since the photo never leaves our cache file. |
| GIFs | A new attachment kind `gif`. Sources: the keyboard (Gboard's GIF search through `commitContent`, received with `Modifier.contentReceiver`) and the photo picker filtered to `image/gif`. Bytes are kept (no re-encode) after stripping metadata blocks. | A Giphy/Tenor search inside Whispr: every search term and the user's IP would go to Google or Giphy, and it needs an API key and their terms. The keyboard already offers GIF search under its own privacy terms, which the user chose. |
| Status | 24-hour posts (text on a color, or a photo) sent to every accepted contact. The photo is encrypted and uploaded **once**; each contact gets a small pairwise `status` payload with its key. | Sender keys for a status audience (Signal stories): fewer ciphertexts per post, but a second group-key system to maintain. With the pairwise approach a post costs one small envelope per contact, fine at the contact counts a beta sees. |
| Calls | 1:1 voice and video over WebRTC (`io.getstream:stream-webrtc-android` 1.3.10, Google's libwebrtc). Signaling (offer, answer, ICE, hang-up) travels as ordinary end-to-end encrypted payloads. A TURN relay (coturn) on the same EC2 host with short-lived credentials from `GET /v1/calls/turn`. | Group calls (need an SFU; not on a 1 GB box). A hosted TURN/STUN provider (third party sees every call's IPs). |
| Navigation | A bottom bar with three tabs: Chats, Status, Calls. Settings stays in the top bar. | |

## 1. Camera

- Attach menu gains **Camera** (first item). Status composer gains **Camera** too.
- `TakePicture` writes to `cache/camera/<uuid>.jpg` through the existing
  FileProvider (new `<cache-path name="camera" path="camera/"/>`).
- The app declares `CAMERA` (needed for video calls). Android then requires
  the permission to be **granted** before an app with it declared may launch
  the camera intent, so the app asks for it first. Denied: an error dialog
  (`ChatError.CameraDenied`), never a silent no-op.
- On success the file goes through `sendMedia(..., AttachmentKind.Image)`;
  `AndroidMediaPreparer.image` re-encodes to ≤ 2048 px JPEG, which drops
  EXIF (GPS, device, time). The temp file is deleted after `sendMedia`
  returns, success or failure. Cancelled: the empty file is deleted.
- No camera app on the device (`ActivityNotFoundException`): error dialog
  `ChatError.NoCamera`.

## 2. GIFs

### Wire and storage

- `AttachmentKind.Gif`; pointer `kind = "gif"`, `type` = `image/gif` or
  `image/webp` (animated WebP is what some keyboards commit).
- Old clients map an unknown `kind` to `File` (fix in `Attachments`: today
  `AttachmentKind.valueOf` would throw). A beta.1 receiver therefore shows a
  downloadable file instead of crashing.
- Limit: 10 MiB plaintext (`MAX_GIF_BYTES`), below the 25 MiB attachment cap,
  so a runaway GIF can't eat memory when decoded.

### Metadata stripping (`GifSanitizer`, `WebpSanitizer`, pure Kotlin)

- GIF: walk the block structure; keep the header, logical screen descriptor,
  color tables, graphics control extensions, image descriptors and data, and
  the `NETSCAPE2.0`/`ANIMEXTS1.0` loop extension. Drop comment extensions
  (`0x21 0xFE`), plain-text extensions (`0x21 0x01`) and every other
  application extension (XMP lives in `XMP DataXMP`). Malformed input is
  rejected (`Prepared.Unreadable`), never passed through.
- WebP: walk RIFF chunks; drop `EXIF` and `XMP `, clear the matching flags in
  `VP8X`, fix the RIFF size. Malformed: rejected.
- Thumbnail: first frame via `ImageDecoder`/`BitmapFactory`, ≤ 16 KiB JPEG,
  like photos. Width and height are sent.

### UI

- Composer: `Modifier.contentReceiver` on the message field accepts
  `image/gif` and `image/webp` from the keyboard (and paste). Other MIME
  types fall through to the field (text paste keeps working).
- Attach menu gains **GIF**: photo picker with `SingleMimeType("image/gif")`.
- Bubble: animated on API 28+ (`ImageDecoder` → `AnimatedImageDrawable` in an
  `ImageView`), first frame below 28. Decrypted bytes stay in memory. A
  "GIF" label sits in the corner. Plays while visible; respects the system
  "remove animations" setting (shows the first frame).

## 3. Status

### Product rules

- A status is text on one of six palette backgrounds, or a photo with an
  optional caption (≤ 200 chars). It disappears after 24 hours everywhere.
- Audience: every contact who is accepted (not a request), not hidden, and
  whose key has not changed unacknowledged. No per-contact lists in this
  version (documented limitation).
- Viewing is private: **no view receipts** are sent. The author does not
  learn who viewed (consistent with read receipts being off by default).
- The author can delete a status early; contacts get `status_delete`.
- No replies, reactions or notifications for statuses in this version.

### Wire

```
Payload.Status(sid, ts, kind = "text" | "image", text?, bg?, a?: AttachmentPointer)
Payload.StatusDelete(sid, ts)
```

Pairwise only (never `g`). Receiver rules, in the decrypt transaction:

1. The sender is an accepted, non-hidden contact; otherwise dropped.
2. `now - 24 h - 1 h slack < ts < now + 1 h`; otherwise dropped.
3. `(author, sid)` is unique: a duplicate is a no-op.
4. Images: pointer kind must be `image`, size ≤ 25 MiB; downloaded when
   the status is opened (or prefetched on Wi-Fi when the Status tab is shown).
5. `StatusDelete` only removes the sender's own status.

Status payloads are control-kind for session resets (not resent after a
reset; a lost status is acceptable and expires anyway).

### Sending

`StatusRepository.post` in one transaction: insert our `statuses` row, then
one outbox entry per audience contact with the same payload bytes. Photo:
encrypt and upload once (`MediaService.uploadDetached`), then the
transaction above. Upload failure: the status shows "Not sent, tap to
retry" in My status.

### Storage (Room v7, auto-migration)

`statuses(author, sid, kind, text, bg, attachment columns as in
attachments, createdAt, expireAt, viewed)`, primary key `(author, sid)`.
Our own statuses use our user ID. The engine's minute sweep deletes expired
rows and their blob files.

### UI

- **Status tab:** "My status" row (avatar with an add badge; subtitle "Tap
  to add" or "N updates, latest 2h ago"), then "Recent" (unviewed) and
  "Viewed". Each row has a ring: moss for unviewed, hairline for viewed
  (DESIGN.md: amber stays for seal and unread).
- **Composer:** a full-screen text editor on a palette color (tap to cycle),
  plus Camera and Gallery buttons for a photo with caption.
- **Viewer:** full screen, segmented progress bar, 5 s per item, tap right/left
  to move, hold to pause, swipe down to close. Own statuses show a delete
  action. Screen security applies.

## 4. Calls

### Media and encryption

- WebRTC peer connection, Opus audio, VP8 video (the library's defaults),
  DTLS-SRTP. This is the first protocol crypto that is not libsignal; it is
  bound to Signal identities like this: the SDP offer and answer, which hold
  the DTLS certificate fingerprints, travel only inside libsignal-encrypted
  payloads between pinned identity keys. A server or network attacker who
  answers with its own DTLS certificate fails the fingerprint check. This is
  the approach Signal's RingRTC takes. `ARCHITECTURE.md` non-negotiables are
  updated to say so.
- `iceTransportPolicy = relay` is **not** the default (it would hide IPs from
  the peer at the cost of all traffic through our 1 GB box). A Settings
  switch "Relay calls through the server" (off) hides your IP address from
  the people you call; documented in the threat model.

### Signaling payloads (pairwise, through the outbox)

```
Payload.CallOffer(cid, sdp, video, ts)
Payload.CallAnswer(cid, sdp)
Payload.CallIce(cid, candidates: List<IceCandidate>)   // batched per 100 ms
Payload.CallHangup(cid, reason: "hangup" | "decline" | "busy" | "timeout" | "error")
```

- Through the outbox, not transient frames: reliable and ordered per peer,
  and an offer that reaches a phone after the caller gave up becomes a
  **missed call** in the log. The outbox keeps one envelope in flight per
  lane; ICE candidates are batched so a call needs a handful of envelopes.
- Ringing only if the offer is fresh: `now - ts < 45 s`. Older offers are
  logged as missed and never ring.
- Only from accepted contacts with a trusted (not changed) key. Requests and
  strangers cannot ring you.
- Busy: an offer while another call is active gets `CallHangup(busy)`.
- Glare (both call at once): the lower `cid` wins; the other side drops its
  own offer and answers.

### Server

- `GET /v1/calls/turn` (authenticated, 10 a minute per user) returns
  `{urls, username, credential, ttl}` with coturn's REST-API scheme:
  `username = "<expiry-unix>:<user-id>"`,
  `credential = base64(HMAC-SHA1(TURN_SECRET, username))`, `ttl` 10 minutes.
  Without `TURN_SECRET`/`TURN_URLS` it answers 503 `calls_unavailable` and
  the app falls back to STUN only, saying so if the call fails to connect.
- No other server change: offers are ordinary envelopes. Push wake-ups (when
  FCM is configured) already fire for them.

### Deployment (free tier)

- coturn (`coturn/coturn:4.6-alpine`, 32 MB limit) on the host network:
  UDP/TCP 3478, relay ports UDP 49160–49200 (41 ports: enough concurrent
  relayed calls for a beta, small enough to reason about).
- `use-auth-secret`, `static-auth-secret=$TURN_SECRET`, `realm=$WHISPR_HOST`,
  `no-cli`, `no-tls` (media is already DTLS-SRTP), `denied-peer-ip` for
  private ranges (no relaying into the VPC or Docker networks),
  `max-bps=600000` per allocation, `user-quota=4`, `total-quota=40`.
- Cost: EC2 data out is free up to 100 GB a month. A relayed voice call is
  ~50 KB/s, video ~300 KB/s at the cap. Most calls connect peer to peer
  through STUN and use no server bandwidth. The cap keeps a worst case
  inside the free tier; `DEPLOYMENT.md` explains how to check usage.
- Security group: open UDP 3478, TCP 3478, UDP 49160–49200.

### Android

- `CallManager` (app module, singleton): state machine
  `Idle → Outgoing(ringing) → Connecting → Active → Ended`,
  `Idle → Incoming → Connecting → Active → Ended`. One call at a time.
- `CallService`: a foreground service of type `phoneCall|microphone|camera`
  (as needed) while a call exists, with an ongoing notification (hang up).
  Incoming ring: a high-priority notification with full-screen intent to
  `CallActivity`-equivalent route (accept / decline), ringtone and vibration,
  60 s ring timeout → missed.
- While a call exists the engine stays connected (`MessagingEngine.setCallActive`).
- Audio: `AudioManager` mode `IN_COMMUNICATION`, earpiece for voice,
  speaker for video, proximity screen-off for voice, Bluetooth headset if
  connected. Mute, speaker and camera switches.
- Permissions: `RECORD_AUDIO` (all calls), `CAMERA` (video), asked at call
  start; denied → the call does not start and an error says why.
- **Limitation (documented):** without FCM configured, a phone only rings
  while Whispr is connected (in use, or for 30 s after a wake). Calls placed
  to a phone that isn't connected show as missed when it next connects.

### Call log (Calls tab)

- Room v7 table `calls(cid, peer, outgoing, video, startedAt, connectedAt?,
  endedAt?, outcome)`; outcome is `Completed`, `Missed`, `Declined`,
  `Busy`, `Failed`, `Cancelled`.
- Calls tab: rows with avatar, name, a direction icon (missed in `danger`),
  "Voice call · 4 min" or "Missed video call", time; tap calls back with
  the same kind. Empty state. Clear log in the overflow menu.
- Disappearing-message timers do not apply to the call log (it is local).
- Chat header (1:1 only) gains Voice and Video call actions.

## 5. Failure modes

| Failure | What the user sees | Test |
|---|---|---|
| Camera permission denied / no camera app | Error dialog | Robolectric UI |
| GIF malformed or over 10 MiB | "Couldn't read this file" / "Too large" dialog | `GifSanitizerTest`, `WebpSanitizerTest` |
| GIF from an old client | Shown as a file | `AttachmentsTest` |
| Status photo upload fails | My status row "Not sent, tap to retry" | `StatusRepositoryTest` |
| Status from a stranger or too old | Dropped, nothing shown | `StatusRulesTest` |
| TURN unavailable (503) | Call tries STUN only; on ICE failure "Couldn't connect the call" | `CallManagerTest` |
| ICE fails / peer never answers (45 s) | "No answer" / "Couldn't connect"; log entry Failed/Cancelled | `CallManagerTest` |
| Mic or camera denied | Call not started, error dialog | UI test |
| Offer while busy | Caller sees "Busy" | `CallManagerTest` |
| Stale offer | Missed call in the log, no ring | `CallRulesTest` |

## 6. Tests

- Data (JVM, real libsignal, `FakeRelay`): status post/receive/delete/expiry,
  audience rules; call signaling payloads round trip; stale and stranger
  offers rejected; GIF/WebP sanitizers with crafted inputs (comment, XMP,
  truncated blocks).
- Server: TURN credential format and HMAC, expiry, rate limit, 503 when
  unconfigured.
- App (Robolectric): tabs, status list/viewer/composer, call screen states,
  call log rows, attach menu items; Roborazzi screenshots of every new screen.
- Device: two emulators against the live server: camera photo (emulator
  virtual scene), GIF from picker, status post and view on the other phone,
  voice and video call connecting both ways, missed call, decline.

## 7. Order of work

1. Camera (app only).
2. GIFs (domain, data sanitizers, app).
3. Room v7 (statuses, calls) and the bottom bar.
4. Status (data, app).
5. Server TURN endpoint, coturn deployment.
6. Calls (signaling payloads, CallManager, service, UI, call log).
7. Docs: ARCHITECTURE, THREAT_MODEL, DEPLOYMENT, ROADMAP (status moves from
   "Not planned" to shipped, calls from "Later"), DESIGN.md components.

## 8. Engineering review (2026-10-09, single pass)

Target: this document. Mode: FULL_REVIEW (scope accepted as-is; the user asked
for all four features). The user said "make choices and follow them", so every
finding below was decided on its recommended option; no question was left open.
The findings **override** the sections above where they differ.
Outside voice: skipped (single-pass review requested).

### Scope challenge

- Reuse found: `MediaService` seal/upload, `MediaPreparer.image`, the outbox
  and pairwise sessions, `PayloadCodec` forward compatibility, the engine's
  minute sweep, `FileProvider`, the notification channels. Nothing new is
  built where one of these fits.
- Complexity: about 30 changed files and 8 new classes (CallManager,
  CallService, the CallMedia port, StatusRepository, two sanitizers, two
  ViewModels). The user fixed the feature list; the smaller arrangement
  adopted below (no new attachment kind on the wire, no separate call
  transport) removes moving parts without cutting a feature.

### Findings and decisions

1. **[P1] (confidence 9/10) `CryptoTables.kt:321-323`: head-of-line blocking.**
   `outboxHead` is `ORDER BY seq LIMIT 1` over all lanes and the pump keeps one
   envelope in flight. A status to 60 contacts queues 60 round trips; a call
   offer, answer or ICE batch queued after it waits behind all of them, so
   call setup could take tens of seconds and offers could go stale.
   **Decision:** add `outbox.priority` (0 = call signaling, 1 = messages and
   controls, 2 = status fan-out) and order by `priority, seq`. Room v7 adds
   the column with default 1.
2. **[P1] (9/10) `Attachments.kt:35-40`: beta.1 rejects unknown kinds.**
   `else -> return null` drops a pointer whose kind isn't image/file/voice, so
   "old clients show a file" (section 2) is wrong for installed apps.
   **Decision:** no new wire kind. GIFs travel as `kind = "image"` with
   `type = image/gif | image/webp`; beta.1 shows the first frame, new clients
   animate. The domain gets `Attachment.animated` (from the content type),
   not a new `AttachmentKind`.
3. **[P1] (8/10) Offer freshness used the sender's `ts`.** Phone clocks drift;
   a skewed caller would never ring or always ring. **Decision:** freshness
   uses the envelope's server timestamp (`IncomingEnvelope.serverTs`).
4. **[P1] (8/10) coturn behind EC2 NAT.** An EC2 instance sees only its
   private address; without `external-ip=<public>/<private>` relay candidates
   carry the private IP and relaying never works. **Decision:** deployment
   sets `external-ip` from instance metadata; `denied-peer-ip` covers 10/8,
   172.16/12, 192.168/16, 127/8 and 169.254/16 so the relay can't reach the
   VPC, the metadata service or Docker networks.
5. **[P1] (8/10) Foreground service type.** `phoneCall` needs
   `MANAGE_OWN_CALLS` (a self-managed ConnectionService) and a microphone
   service cannot start from the background. **Decision:** ringing is a
   notification only; the service (type `microphone`, plus `camera` for
   video) starts when the user places or accepts a call, always from a
   visible activity or a notification action. No ConnectionService yet.
6. **[P2] (8/10) Full-screen intents can be revoked (Android 14+).**
   **Decision:** check `NotificationManager.canUseFullScreenIntent()`; without
   it the ring is a heads-up notification with Accept and Decline actions.
7. **[P2] (8/10) Stale status entries in parked lanes.** A status queued for a
   contact whose lane is parked would go out days later. **Decision:** the
   pump drops priority-2 entries older than 24 h instead of sending them.
8. **[P2] (7/10) `IncomingPipeline.kt:661`: reset classification.**
   `Payload.kind()` is an exhaustive `when`. **Decision:** status and call
   payloads are `KIND_CONTROL`, never resent after a session reset (a resent
   offer would ring late; a status is ephemeral).
9. **[P2] (7/10) APK size.** libwebrtc adds about 10 MB per ABI.
   **Decision:** `abiFilters` arm64-v8a, armeabi-v7a, x86_64 (x86_64 keeps
   emulators working).
10. **[P2] (6/10, medium confidence, verify) `contentReceiver` with the
    value-based `BasicTextField`.** If keyboard GIFs don't reach the field,
    `MessageInputBar` moves to `TextFieldState`; the change stays inside the
    component.
11. **[P3] (7/10) A status blob is fetched by every viewer.** The server can
    group the viewers of one upload. It already sees who messages whom (no
    sealed sender), so this adds little; documented in the threat model.

### Architecture

```
 caller                          server                         callee
  │ CallOffer (prio 0) ───────▶  envelope  ───────────────────▶ │ fresh (serverTs)? contact? idle?
  │                               (push wake if FCM)            │   ├─ no: log Missed / reply busy
  │                                                             │   └─ yes: ring (notification)
  │ ◀───────────────────────────────────────── CallAnswer ──── │ accept → service → answer
  │ ◀────── CallIce batches (both ways, prio 0) ─────────────▶ │
  │ ═══════════ DTLS-SRTP media (P2P via STUN, else TURN) ═════ │
  │ CallHangup ─────────────────────────────────────────────▶  │ log Completed
```

```
 post status ─▶ [photo? seal + upload once] ─▶ tx{ statuses row + N outbox rows (prio 2) }
 receive ─▶ decrypt tx{ accepted contact? ts fresh? (author, sid) new? ─▶ statuses row }
 sweep (1/min) ─▶ delete expired statuses and their blob files
```

### Code quality

- Calls are split so the logic is testable: `CallManager` (pure state machine
  over a `CallMedia` interface and the messaging layer), `WebRtcCallMedia`
  (the only class touching libwebrtc), `CallService` and the ring notification.
- One new shared helper, `MediaService.uploadDetached` (seal and upload with
  no message row) for status photos, built from `sealAndQueue`'s pieces.

### Tests (required)

```
CODE PATHS                                         USER FLOWS
[+] outbox priority                                [+] Camera photo → bubble        [→E2E device]
  ├── [GAP] call entry jumps a status fan-out      [+] GIF from picker / keyboard   [→E2E device]
  └── [GAP] prio-2 entry older than 24 h dropped   [+] Post status → seen on B      [→E2E device]
[+] GifSanitizer / WebpSanitizer                   [+] Voice call, decline, missed  [→E2E device]
  ├── [GAP] comment/XMP/app ext stripped, loop kept[+] Video call both ways         [→E2E device]
  └── [GAP] truncated / bad header rejected        [+] Mic or camera denied → error
[+] Status rules (data, real libsignal)            [+] TURN 503 → STUN only
  ├── [GAP] accepted contact + fresh → stored
  ├── [GAP] stranger / request / stale → dropped
  └── [GAP] delete only own; sweep removes blob
[+] Call rules (data)
  └── [GAP] fresh rings; stale → Missed; stranger dropped; busy → hangup(busy)
[+] CallManager (JVM, fake CallMedia)
  └── [GAP] out → answer → active → hangup; no answer 45 s; ICE failure; glare
[+] Server GET /v1/calls/turn
  └── [GAP] HMAC format, ttl, 503 when unconfigured, rate limit
COVERAGE: all paths are new; every one above is required.
```

### Performance

- Status fan-out: N envelopes of about 1–2 KB at priority 2; 100 contacts is
  about 100 sequential sends in the background, never ahead of chat or calls.
- GIF decode: at most 10 MiB per visible GIF; `AnimatedImageDrawable` decodes
  frames lazily and off-screen bubbles release it.
- coturn: `max-bps=600000` and `total-quota=40` bound the host's worst case.

Failure modes: 0 critical gaps (every new failure path has a user-visible
error and a planned test).

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Outside Review | codex (plan-review) | Independent 2nd opinion | 0 | skipped | single pass requested |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | ISSUES OPEN (all decided) | 11 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

- **OUTSIDE COVERAGE:** codex, plan-review, skipped (the user asked for a single pass).
- **VERDICT:** ENG reviewed; all 11 findings decided and folded into the plan. Ready to implement.

NO UNRESOLVED DECISIONS
