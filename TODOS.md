# TODOS

## Android: encryption

### P3: Prune one-time private keys that were handed out but never used
- **What:** delete a local one-time EC or Kyber private key when both of these hold:
  - its public half is no longer on the server;
  - it is older than 60 days (twice the 30-day envelope retention).
- **Why:**
  - Someone can fetch a bundle and never send a message. The matching private key then stays on the device forever.
  - The bundle-fetch rate limit only slows this growth; it doesn't cap it.
- **Pros:** local storage stays bounded, and less old key material sits on the device.
- **Cons:**
  - It needs the server to report which key IDs remain, or a rule based on age alone.
  - A wrong rule could delete a key that a delayed message still needs.
- **Context:** design `docs/designs/end-to-end-encryption.md`, §PreKeyMaintainer "Not pruned". Start in `PreKeyMaintainer` and `GET /v1/keys/count`.
- **Depends on:** end-to-end encryption landing.

## Android: messaging features

### P2: Replies
- **What:**
  - Add `Payload.Reply(mid, quotedMid, body)` (reactions are done: `Payload.Reaction`).
  - Add the UI: swipe to reply and a quoted bubble.
- **Why:**
  - Brief item 5 ("encrypt text, replies, reactions, and receipts") assumes these features exist.
  - Payload encryption is generic, so new kinds are encrypted automatically.
- **Pros:** fills a real product gap. The `mid` from the encryption work makes quote and reaction references stable across resends.
- **Cons:** needs design-system work (quoted bubble, reaction chips) and new UI tests.
- **Context:**
  - `Payload.kt` (sealed interface, JSON discriminator `t`).
  - Design premise 5.
  - Old clients drop unknown kinds (`PayloadCodec.decode` returns null).
- **Depends on:** end-to-end encryption (the `mid` field).

## Groups and media

### P2: Store a fan-out payload once
- **What:** `send_multi` writes one envelope row per recipient, each with a full copy of the payload.
- **Why:** the worst case is 64 KiB × 100 recipients = 6.4 MB written per group message.
- **Fix:** a `payloads` table referenced by the envelopes, deleted after the last ack.
- **Context:** review R5, `server/internal/messaging/store.go` `AcceptMulti`.

### P2: Pad attachment sizes
- **What:** pad blobs to size buckets before encryption.
- **Why:** the server sees ciphertext sizes, which are about the file size, and that can identify a known file.
- **Context:** `MediaCrypto.seal`, threat model §Groups and media.

### P3: Stream attachment encryption
- **What:** encrypt and decrypt in chunks instead of in memory.
- **Why:** a 25 MiB file currently needs about 50 MiB of memory.
- **Note:** libsignal's `Aes256GcmEncryption` already supports incremental use. The digest check before decryption keeps streaming safe.

### P3: Real S3 in server CI
- **What:** the `server.yml` job runs attachments only against the in-memory store.
- **Fix:** add a MinIO service and set `WHISPR_TEST_S3_*`. A GitHub service container can't pass `server /data`, so a step that runs it is needed.
- **Context:** the e2e job already exercises MinIO end to end.

### P3: Group read receipts and typing
- **What:** both are deliberately off in groups.
- **Before adding them:** decide whether to aggregate receipts (privacy: every member would learn when you read).

## Completed
