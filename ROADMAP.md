# Roadmap

Where Whispr is and where it is going. Dates are not promised; order is.
Each item names the gap it closes (see `docs/THREAT_MODEL.md` and
`docs/SECURITY_REVIEW.md`). Smaller engineering tasks live in `TODOS.md`.

## Now: 0.1 beta

- One-to-one and group chats, end-to-end encrypted with libsignal (PQXDH,
  Double Ratchet, sender keys).
- Encrypted photos, files and voice messages.
- Replies, reactions, forwarding, copy, delete for me and for everyone.
- Disappearing messages per conversation.
- Local message search.
- QR contacts, usernames, safety-number verification, key-change warnings.
- Screen security, private notifications, account deletion.
- No phone number, e-mail or password. No trackers.
- Self-hostable server with a deployment guide; signed APK releases.

## Next: 0.2

- **App lock** (biometric or PIN), for unlocked stolen phones (M6).
- **Blocking and reporting** of contacts and requests.
- **Encrypted profiles**: display names and usernames stored encrypted, so
  the server no longer holds them in plaintext.
- **Incognito keyboard** flag once Compose exposes it (I2).
- **Pad attachment sizes** (L8).
- **Dependency verification**: Gradle verification metadata, libsignal
  pinned by commit, container images by digest (L9).
- **UnifiedPush** as an alternative to Firebase for wake-ups.

## Later

- **Sealed sender**, so the server no longer learns who sends to whom (M5).
- **Spam resistance without phone numbers**: proof of work or anonymous
  rate-limit tokens for registration (M2 residual).
- **Multi-device**: linked devices with their own keys.
- **Encrypted backups** that never hand key material to the server.
- **Horizontal scaling**: shared rate limits and presence so the server can
  run more than one instance.
- **Voice and video calls.**
- **MLS for large groups** (`docs/MLS.md`), if group sizes outgrow sender keys.
- **Reproducible builds** and F-Droid distribution.

## Not planned

Ads, analytics, feeds, stories, contact-list upload, cloud message history in
readable form.
