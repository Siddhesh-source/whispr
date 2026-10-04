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

### P2: Replies and reactions
- **What:**
  - Add `Payload.Reply(mid, quotedMid, body)` and `Payload.Reaction(targetMid, emoji | remove)`.
  - Add the UI: swipe to reply, long-press to react, and reaction chips on bubbles.
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

## Completed
