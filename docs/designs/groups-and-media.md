# Encrypted groups and encrypted media

Status: approved for implementation (2026-10-05). Builds on
`docs/designs/end-to-end-encryption.md`. All cryptography comes from libsignal
(`GroupCipher`, `GroupSessionBuilder`, `Aes256GcmEncryption`,
`CryptographicHash`). This design adds none of its own.

## Goals

- Group chats encrypted with libsignal sender keys. Each message is encrypted
  once and fanned out by the server. A group is not N 1:1 chats.
- Group operations: create, rename, set picture, add, remove, leave, admin
  role, invite.
- Sender keys rotate whenever a member is removed or leaves.
- The server learns only what fan-out needs. Group names, pictures and
  membership lists are end-to-end encrypted.
- Encrypted media: images (with thumbnail and metadata stripped), documents,
  small files and voice messages. Size limit and server-side retention.
- Reactions and media work in 1:1 chats and in groups.

Not in scope: multi-device, invite links, group read receipts, group typing
indicators, and MLS (see `docs/MLS.md`).

## Decisions

### D1. Server fan-out without server-side groups

The server gets a new WebSocket frame:

```
{"type":"send_multi","id","conversation_id","recipient_ids":[…≤ 100],"client_ts","payload"}
```

It is processed in one transaction:

1. Lock each recipient in sorted order, so concurrent sends can't deadlock.
2. Write one dedup tombstone for `(sender, id)`.
3. Insert one envelope per recipient. Every envelope carries the same ID and
   payload.

The reply is a single `accepted` or `rejected` frame. Delivery receipts still
come one per recipient: the `sender_id` of each receipt identifies the
recipient. An unknown recipient, the sender in the list, a duplicate, an empty
list or more than 100 recipients rejects the whole frame with `invalid` or
`unknown_recipient`. The rate limiter charges `1 + n/10` tokens.

The server stores no groups, members, roles or names. Per message it sees the
recipient set (it needs that for fan-out) and an opaque conversation ID. The
threat model records that it can infer membership from those.

### D2. Group state is replicated by clients over pairwise sessions

A group ID is 16 random bytes (a UUID v4). It is also the conversation ID on the wire.

Group state is a revision number plus:
- the name (≤ 64 characters);
- the picture: a JPEG of at most 256 px, inline and ≤ 24 KiB, so it never expires;
- the members, each with user ID, identity key and role (admin or member);
- the invitees, each with user ID and identity key.

Admins send the full state as `Payload.GroupUpdate` over existing pairwise
libsignal sessions to every current member, every invitee, and anyone just
removed.

A receiver accepts an update only if:
- the sender is an admin in the receiver's current state. For a group the
  receiver doesn't know, the receiver must appear in the new state.
- the revision is greater than the current one. If two updates carry the same
  revision, the higher sender ID wins, so every client picks the same one.

Authenticity comes from the pairwise session; the server can't forge updates.
It can drop or delay them, so clients may disagree for a while. The threat
model records this.

| Operation | Who | How |
|---|---|---|
| create | anyone | creator is admin; revision 1 sent to members |
| rename / picture | admin | new revision |
| add member | admin | added to members, new revision |
| remove member | admin | removed, new revision, also sent to the removed member |
| promote / demote admin | admin | new revision; the last admin can't demote themselves |
| invite | admin | added to invitees, new revision; the invitee gets the state with a pending flag |
| accept invite | invitee | `GroupJoin` to an admin; that admin moves them to members (new revision) |
| decline invite | invitee | `GroupDecline` to an admin, who drops them from invitees |
| leave | member | `GroupLeave` to all members; every receiver removes the leaver locally; an admin sends the next revision without them. A sole admin first promotes the longest-standing member. |

**Removals stick (review R3).** State carries `addedAt` (revision) per member
and a `removed` map of `userId → revision`. Applying any state drops members
whose `addedAt ≤ removed[userId]`. A concurrent update from another admin
therefore can't bring back a removed or departed member. Only an explicit
re-add at a later revision can.

Members who aren't my contacts are stored as hidden contacts. Their identity
key is pinned from the admin's state, so the usual key-change checks apply:
- the server's bundle must match the pin;
- if it doesn't, the member is flagged KeyChanged and their lane is parked.

### D3. Sender keys and rotation

Each group has my current `distributionId`, a random UUID. Before the first
send under a distribution ID, I create its SenderKeyDistributionMessage with
`GroupSessionBuilder.create(me, distributionId)` and queue it as
`Payload.SenderKey(groupId, skdm)` on the pairwise lane of each current member
that hasn't had it yet. The `group_key_shares` table records who has had it.

A group message is encrypted once with `GroupCipher.encrypt(distributionId, …)`
when it reaches the head of the group lane. It is sent as `send_multi`. Wire
type `0x03` is a SenderKeyMessage.

Rotation:
- When a member is removed or leaves, every remaining member starts a new
  `distributionId` and clears its shares. The rotation is applied in the same
  transaction as the state change.
- The next send distributes the new key to current members only.
- A removed member has none of the new chain keys. Even with the ciphertext
  (for example from a compromised server) it can't decrypt anything sent after
  the rotation.
- The recipient list of any queued group message is intersected with the
  current members when it is sent.

Receiving:
- A SenderKeyMessage is mapped to its group through
  `(sender, distributionId) → groupId`, recorded when that sender's key
  distribution message was processed.
- After decrypting, the payload's group ID must match, and the sender must be
  a current member. Otherwise the message is dropped.
- A message whose distribution message hasn't arrived yet (lanes are
  independent) is held encrypted in `held_group_envelopes` and retried when
  that sender's next key distribution message arrives. Held messages expire
  after 30 days.
- `SenderKey` payloads are resendable through the existing session-reset
  protocol (review R1). The sent log keeps their plaintext and they are not
  classed as control messages, so if a pairwise key distribution message fails
  to decrypt it is recovered instead of stranding held group messages.
- A message that can't be decrypted becomes an Unrecoverable placeholder.
  Groups have no reset protocol; the threat model records this.

### D4. Media

Server endpoints:
- `POST /v1/attachments` takes the raw ciphertext body (`Content-Length` ≤ 25 MiB + 28 bytes).
  - The server streams it to S3 as `attachments/<uuid>`, records
    `(id, uploader, size, created_at)` and returns `{id}`.
  - Rate limit: 30 uploads per minute per user.
  - The body is wrapped in `http.MaxBytesReader`. A missing or oversized
    `Content-Length` gets 411/413 before anything is stored.
  - The handler extends its own read and write deadlines to 5 minutes through
    `http.ResponseController` (review R2). The server-wide 15 s timeouts would
    otherwise cut off any upload slower than about 1.7 MB/s.
- `GET /v1/attachments/{id}` streams the object back to any authenticated user.
  The ID is a 122-bit random capability, and the content is ciphertext.
- A janitor deletes objects and rows older than `ATTACHMENT_RETENTION`
  (default 30 days).

The server proxies uploads instead of handing out presigned URLs, for three
reasons:
- the server enforces the size limit itself;
- devices only need to reach the API origin (`adb reverse` and production
  alike);
- S3 credentials and endpoints never reach clients.

Client:
- **Encryption.** Each file gets a random 32-byte key and a 12-byte nonce.
  The uploaded blob is `nonce ‖ AES-256-GCM(plaintext) ‖ tag`, using
  libsignal's `Aes256GcmEncryption` with AAD `whispr-attachment-v1`.
- **Digest.** `digest = SHA-256(blob)`, computed with libsignal's
  `CryptographicHash`.
- **Message.** `Payload.Media` carries `{id, key, digest, size, contentType,
  kind (image|file|voice), fileName?, width?, height?, durationMs?, thumbnail?}`
  inside the end-to-end encrypted message.
- **Receiving.** The recipient downloads the blob, checks its size, and
  compares SHA-256 with the digest in constant time before decrypting. The tag
  is verified before any plaintext is used.
- **Images.** Decoded, scaled to ≤ 2048 px and re-encoded as JPEG, which
  drops EXIF, GPS and other metadata. The thumbnail is ≤ 256 px, ≤ 16 KiB, and
  inline in the encrypted message.
- **Documents and small files.** Sent byte for byte; the UI says so (a PDF may
  carry author metadata).
- **Voice.** MediaRecorder AAC in MP4, mono, at most 10 minutes. The recorder
  writes no location or device metadata.
- **At rest.** The device keeps the encrypted blob, and its key lives in the
  SQLCipher database. Images and voice are decrypted in memory. "Open" writes
  a decrypted copy to `cache/open/` (shared through FileProvider) and deletes
  it on next start.
- **Expiry.** A blob the server already deleted shows as "Media expired".

### D5. Reactions

`Payload.Reaction(target, targetAuthor, emoji, remove, g?)` sets one reaction
per reactor per message; a newer one replaces the older. The `reactions` table
is keyed by `(conversationId, targetAuthor, targetMid, reactorId)`. In 1:1
chats reactions go over the pairwise lane, in groups over the sender key.

### D6. Local data (Room v5, auto-migration 4 → 5)

New tables:
- `groups`;
- `group_members`;
- `sender_keys` (libsignal `SenderKeyStore`);
- `group_key_shares`;
- `group_distributions` (sender, distributionId → group);
- `held_group_envelopes`;
- `group_deliveries`;
- `attachments`: pointer, local blob path, download state;
- `reactions`.

New columns:
- `contacts.hidden`;
- `outbox.groupId` and `outbox.recipients`;
- `messages.senderId` (author of an incoming group message);
- `messages.attachmentId`.

### D7. Domain and UI

Domain model:
- `ConversationSummary` gains a title and either a peer or a group.
- `Message` gains the sender name in groups, an attachment, and reactions.

UI:
- **Screens:** New group (pick contacts, name), Group info (rename, picture,
  members, roles, add, remove, invite, leave), and Group invites in the chat
  list.
- **Chat:** attach image or file, record voice, long-press to react.

## Security notes

These are design consequences, listed instead of chosen silently.

1. **Admin roles are enforced by clients only.** The server doesn't know
   groups. A dishonest member can't change state for honest clients. The
   server can drop or delay updates, leaving clients briefly inconsistent.
2. **The removal window.** A member who hasn't yet processed a removal can
   still send to the removed member under the old key. This is the same in
   Signal. Once a sender has processed the removal, its messages are
   unreadable to the removed member.
3. **Sender keys have forward secrecy along the chain but no post-compromise
   security until the next rotation.** We rotate on removal and leave only.
   MLS (`docs/MLS.md`) would fix this.
4. **What the server sees per message:** the recipient sets and a stable
   group conversation ID. It can infer membership and group size.
5. **What the server sees per attachment:** the uploader, ciphertext size
   (unpadded, so size reveals roughly the file size), upload time and download
   times.
6. **Documents keep their embedded metadata.**

## Tests

| Where | What |
|---|---|
| Server integration | `send_multi`: atomic fan-out, dedup on retry, per-recipient receipts, recipient cap, unknown or self recipient rejects all, order with 1:1 sends |
| Server | attachments: upload, download, size limit (413), unknown ID (404), auth, rate limit, retention janitor; MinIO integration with `WHISPR_TEST_S3_ENDPOINT` |
| Data (JVM, real libsignal, scripted server) | three users share a group; all read; a removed member can't read later messages (no envelope, and the captured ciphertext is undecryptable for them); leave rotates; non-admin updates rejected; rename and picture; invite, accept and decline; admin promote; distribution message after the message is held then released; reactions 1:1 and group; media round trip 1:1 and group; tampered blob or digest rejected; stored blob never contains the plaintext |
| Live (real server, MinIO) | the same three-user group and media round trip; the raw blob from storage contains no marker |
| CI `e2e` | MinIO bucket mirrored and scanned for the marker together with `pg_dump` |
| Device (emulator, D:) | image re-encode strips EXIF GPS; SQLCipher file holds no group name |
| App (Robolectric) | group screens, reactions, media bubble states |

## Engineering review (2026-10-05, one quick hard pass)

Per the user's rule, this was one quick review with no follow-up reviews or
outside voice. Findings that had a clear default were auto-decided to the
recommended fix and folded into the decisions above.

**Scope challenge:** scope accepted as-is.
- About 45 files change. New units: `GroupCrypto`, `GroupManager`,
  `MediaCrypto`, `MediaRepository`, `attachments` server module, `send_multi`.
- Nothing in the repo already solves groups or media. libsignal supplies every
  primitive: `GroupCipher`, `Aes256GcmEncryption`, `CryptographicHash`.
- Arrangement: the original one.

**1. Architecture**

| # | Sev | Conf | Finding | Disposition |
|---|---|---|---|---|
| R1 | P1 | 9/10 | `IncomingPipeline.answerReset` (`IncomingPipeline.kt:~250`) answers control messages with "nothing to resend". If `SenderKey` were control, a sender key distribution message that failed to decrypt would strand every later group message from that sender in the held table. | Accepted: `SenderKey` is resendable (D3) |
| R2 | P1 | 9/10 | `http.Server{ReadTimeout: 15s, WriteTimeout: 15s}` (`cmd/whisprd/main.go:~100`) kills 25 MiB uploads on slow links. | Accepted: per-handler deadlines (D4) |
| R3 | P1 | 8/10 | Equal-revision updates from two admins: highest sender ID wins, so a removal in the losing update is silently undone and the removed member gets new sender keys. | Accepted: removal tombstones (D2), plus a test |
| R4 | P2 | 8/10 | A malicious admin colluding with the server can assert a wrong identity key for a member who isn't my contact. An existing contact pin always wins; a mismatch flags KeyChanged. | Accepted: documented in the threat model |
| R5 | P2 | 9/10 | `send_multi` copies the payload into each of up to 100 envelope rows. A worst case of 64 KiB × 100 is 6.4 MB per send. | Deferred: TODO to store each payload once |

**2. Code quality**
- R6 (P2, 8/10): the outbox pump picks the lane head by global sequence. The
  group lane must reuse `outboxHead` with `groupId` lanes, not add a second
  pump. Accepted.

**3. Tests**
- The "Tests" table above covers every decision.
- Added: a concurrent-admin removal test (R3) and an SKDM-failure recovery test (R1).
- Critical paths: removed member, ciphertext only, and media round trip.
  Each is tested in JVM, live and CI.

**4. Performance**
- Media is held fully in memory, up to 25 MiB × 2.
- `send_multi` takes up to 100 advisory locks, in sorted order.
- Both are acceptable at this size. R5 is deferred as a TODO.

**Failure modes:** 0 critical gaps. Every new failure path either has a test
or a user-visible state: held messages, an Unrecoverable placeholder, or
"Media expired".

**NOT in scope:** MLS, invite links, multi-device, group read receipts and
typing indicators, attachment size padding, and streaming media crypto.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Outside Review | skipped (user rule: one review) | Independent 2nd opinion | 0 | skipped | — |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 2 | ISSUES OPEN (all mapped) | 6 issues, 0 critical gaps |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

- **OUTSIDE COVERAGE:** skipped. The user asked for exactly one review.
- **VERDICT:** ENG reviewed, findings mapped into the plan, ready to implement.

NO UNRESOLVED DECISIONS
