# Security review: 0.1.0-beta.1

**Date:** 2026-10-08
**Scope:** the whole repository at this release: Go server (`server/`),
Android client (`android/`), CI and release workflows, deployment defaults.
**Method:** each category in `docs/THREAT_MODEL.md` was traced through the
code: where the control lives, what bypasses it, and which test proves it.
Findings are ranked by impact and likelihood for a public beta. Every
Critical and High finding is fixed in this release; open items are listed
with the reason they are accepted.

| Severity | Meaning |
|---|---|
| Critical | Breaks message confidentiality or lets someone act as another user. |
| High | Defeats an advertised control, or lets one account deny service to another, with little effort. |
| Medium | Weakens a control or leaks metadata beyond what the threat model states. |
| Low | Defence in depth; needs unusual conditions. |
| Info | Noted for the record; no change needed now. |

No Critical findings. Message content, keys and identities rely on libsignal
and on checks that already had tests; nothing found lets the server or
another user read messages or sign in as someone else.

## Findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| H1 | High | Per-IP limits collapse behind a reverse proxy | Fixed |
| H2 | High | WebSocket sessions outlive their token; no logout | Fixed |
| H3 | High | Release builds trusted any system CA, had no real server URL, and could not be built | Fixed |
| H4 | High | One account could fill another's queue without bound | Fixed |
| M1 | Medium | Most authenticated REST routes had no rate limit | Fixed |
| M2 | Medium | Account creation shared the sign-in limit | Fixed (residual risk open) |
| M3 | Medium | No way to delete an account or the data the server holds | Fixed |
| M4 | Medium | Screenshots, screen recording and recents thumbnails exposed chats | Fixed |
| M5 | Medium | The server sees who talks to whom (no sealed sender) | Open, accepted |
| M6 | Medium | No app lock; Keystore keys are not bound to user authentication | Open, accepted |
| L1 | Low | Client timestamps were stored unchecked | Fixed |
| L2 | Low | 1 MiB request headers accepted; no security headers | Fixed |
| L3 | Low | Copied message text reached clipboard history and keyboard previews | Fixed |
| L4 | Low | Security confirmations could be tapjacked by an overlay | Fixed |
| L5 | Low | Notifications kept the text of deleted or expired messages | Fixed |
| L6 | Low | Push wake-up map grew without bound | Fixed |
| L7 | Low | `POST /v1/auth/challenge` reveals whether a user ID exists | Open, accepted |
| L8 | Low | Attachment sizes are not padded | Open, accepted |
| L9 | Low | Supply chain: libsignal pinned by tag, MinIO image by `:latest` | Open |
| I1 | Info | Release APK shipped 440 MB of native debug info | Fixed |
| I2 | Info | Keyboards may learn typed text (no incognito flag in Compose) | Open |
| I3 | Info | A recipient can always keep a disappearing message | By design |

### H1: Per-IP limits collapse behind a reverse proxy

**Where:** `httpx.RateLimiter` keyed on `r.RemoteAddr`.
**Impact:** the deployment puts the server behind a TLS proxy, so every
request arrived from the proxy's address. The 30/min sign-in limit became one
global bucket: any client could lock everyone out of sign-in, and the
registration limit stopped nobody.
**Fix:** `TRUSTED_PROXIES` (CIDRs). Only when the TCP peer is trusted is
`X-Forwarded-For` read, taking the rightmost address that is not itself a
trusted proxy, so clients cannot spoof it by sending their own header.
(`platform/httpx/clientip.go`)
**Tests:** `TestClientIPTrustsForwardedForOnlyFromTrustedProxies`,
`TestParseTrustedProxiesRejectsGarbage`.

### H2: WebSocket sessions outlive their token; no logout

**Where:** `messaging/gateway.go`, `auth/handler.go`.
**Impact:** a socket opened with a 15-minute token stayed open for as long as
the client kept it alive, so an expired or stolen token never stopped
working on an open connection. There was no way to revoke a token.
**Fix:** `Authenticate` returns the token's expiry and the gateway closes the
socket with status 4001 at that moment (the client signs in again and
reconnects at once; nothing is lost because envelopes are durable and the
outbox resends). `POST /v1/auth/logout` deletes the token and closes the
user's sockets; deleting the account does the same.
**Tests:** `TestSocketClosesWhenItsTokenExpires`,
`TestLogoutRevokesTokenAndClosesSocket`,
`TestExpiredAndDeletedUsersTokensAreRefused` (server);
`tokenExpiryCloseDropsTheTokenAndReconnects` (Android engine).

### H3: Release transport trusted any system CA; release could not be built

**Where:** `app/build.gradle.kts`, `DataModule.okHttp`.
**Impact:** release builds pointed at a placeholder URL and accepted any
certificate from any system CA. A mis-issued certificate or a CA under an
attacker's control could intercept sign-in, prekey fetches and metadata.
(Message content stays end-to-end encrypted, and key substitution is caught
by pinned contact keys, which is why this is High and not Critical.) The
release build also failed in R8 (see `docs/failures/2026-10-08-release-r8-missing-class.md`).
**Fix:** `TlsPolicy`: TLS 1.2+ with OkHttp's modern suites, plain HTTP only
for a loopback debug server, and SPKI pinning from
`-Pwhispr.certPins` (at least two: live and backup key). The release build
refuses to run without an HTTPS server URL, pins (or an explicit
`whispr.allowUnpinned=true`) and a signing key (`checkReleaseConfig`).
**Tests:** `TlsPolicyTest` (matching pin, backup pin on the root, trusted-CA
certificate with the wrong key rejected, plain HTTP only for loopback);
`checkReleaseConfig` was run without configuration and refused the build.

### H4: One account could fill another's queue

**Where:** `messaging/store.go`.
**Impact:** the per-connection limit (20 sends/s) bounds rate, not volume.
Any account that knew a user ID could queue envelopes for that user for 30
days, without bound, costing the server storage and the victim a flood on
reconnect.
**Fix:** at most 1,000 undelivered envelopes from one sender to one
recipient, checked under the recipient lock with a bounded count; extra
sends are rejected with `recipient_full`. Server receipts are not counted.
**Tests:** `TestUndeliveredQuotaPerSenderAndRecipient`.

### M1: Most authenticated routes were not rate limited

Only bundle fetches, uploads and username lookups had limits. Added a per-user
limit of 120 requests a minute on every authenticated route
(`USER_REQUESTS_PER_MINUTE`), on top of the per-route ones.
**Test:** `TestUserLimiterIsPerUser`.

### M2: Account creation shared the sign-in limit

Registration is now limited separately, to 10 an hour per client IP
(`REGISTRATIONS_PER_HOUR`). **Residual:** with no phone number, IP addresses
remain the only cost of an account. Proof-of-work or anonymous rate-limit
tokens are on the roadmap. **Test:** `TestRegistrationHasItsOwnStricterLimit`.

### M3: No account deletion

`DELETE /v1/me` deletes the account and everything keyed to it (keys,
envelopes to and from it, tokens, push token, username; attachment rows lose
their uploader and are removed by the janitor). Because it is irreversible
it needs, besides the token, a fresh challenge signed over its own label
(`"whispr-delete-v1" 0x00 || user_id || nonce`), so a stolen token or a
sign-in signature cannot delete an account. The app then erases the
database, wrapped keys, Keystore entries, media and caches.
**Tests:** `TestAccountDeletionRemovesEverythingAndNeedsAFreshSignature`,
`TestDeleteAccountNeedsAFreshDeleteSignature`, `TestDeleteMessageVector`
(shared with `AuthMessagesTest.deleteVector`),
`SessionAuthRepositoryTest.deleteAccountSignsDeleteMessageThenWipes`,
`failedServerDeleteLeavesDeviceUntouched`.

### M4: Screen capture

`FLAG_SECURE` is set before any content draws and follows the Screen
security setting (on by default): no screenshots, no screen recording, and a
blank recents thumbnail.

### M5: Social graph (open)

The server needs sender and recipient to route. Envelopes are deleted on
delivery, but while queued (up to 30 days) they reveal who writes to whom.
Sealed sender is on the roadmap.

### M6: No app lock (open)

Anyone holding an unlocked phone can open the app. Binding the Keystore keys
to user authentication would stop background delivery. An optional app lock
(biometric or PIN) is on the roadmap.

### L1–L6

- **L1** `client_ts` before 2020 or more than a day ahead is rejected.
- **L2** `MaxHeaderBytes` is 16 KiB; every response carries `nosniff`,
  `no-referrer`, `X-Frame-Options: DENY` and a deny-all CSP
  (`TestSecurityHeadersOnEveryResponse`). HSTS is set at the proxy.
- **L3** "Copy text" marks the clip sensitive (`EXTRA_IS_SENSITIVE`), so
  Android hides it in the clipboard preview and keyboards skip it.
- **L4** The verify screen, the key-change card and the delete-account
  dialog ignore touches while another app's window covers them.
- **L5** When a message is deleted for everyone or expires, the
  conversation's notification is cancelled.
- **L6** The push wake-up map is pruned.

### L7–L9 (open)

- **L7** The challenge endpoint answers `404 unknown_user` for unknown IDs.
  IDs are random UUIDv4 and are shared through QR codes anyway; revisit if IDs
  become discoverable.
- **L8** Attachment ciphertext sizes reveal roughly the file size.
- **L9** Pin libsignal by commit, enable Gradle dependency verification, pin
  container images by digest.

### I1–I3

- **I1** libsignal's Android library ships unstripped; without an NDK, AGP
  packaged ~110 MB of debug info per ABI. The NDK is now pinned and the
  release workflow refuses an unstripped library
  (`docs/failures/2026-10-08-release-apk-unstripped.md`).
- **I2** Compose has no API for `IME_FLAG_NO_PERSONALIZED_LEARNING`.
- **I3** Disappearing messages remove copies from cooperating apps. A
  recipient can still photograph the screen or run a modified client; the UI
  does not claim otherwise.

## Review by threat category

### Network attacker (MITM)

| Control | Where | Verified by |
|---|---|---|
| TLS 1.2+, modern suites; HTTP only for loopback debug | `TlsPolicy` | `TlsPolicyTest` |
| SPKI pinning (live + backup) | `TlsPolicy`, `checkReleaseConfig` | `TlsPolicyTest` |
| System CAs only, no user CAs, no cleartext in release | `network_security_config.xml` | lint, manual |
| Content end-to-end encrypted, so TLS failure exposes metadata only | libsignal | CI `e2e` ciphertext scan |

### Malicious or compromised server

| Control | Where | Verified by |
|---|---|---|
| Server never holds private keys; cannot sign as a user | design | auth tests |
| Payloads are libsignal ciphertext; server cannot read or forge content | `SessionCrypto` | `EncryptedMessagingTest`, CI `e2e` |
| Key substitution: contact keys pinned, changes held and flagged | `RoomContactsRepository`, `IncomingPipeline` | `ContactTrustTest` |
| Forged "delete for everyone" (server or another member) | author must match the authenticated sender and the target's author; 24 h window | `forgedDeleteFromTheOtherSideIsIgnored` |
| Forged timer change | accepted only inside the matching conversation from a member; newest wins | `timerFromSomeoneOutsideTheGroupIsIgnored` |
| Blob substitution | SHA-256 digest from inside the encrypted message is checked before use | `MediaTest` |
| Forwarded media not linkable by blob ID | forwarding re-encrypts and re-uploads | `forwardedMediaIsResealedAndReadable` |

### Server database or backup leak

| Data in the database | Exposure |
|---|---|
| Session tokens | SHA-256 hashes only |
| Envelopes | ciphertext, padded; deleted on ack or after 30 days |
| Identity keys, prekeys | public keys only |
| Display names, usernames | plaintext (known gap: encrypted profiles on roadmap) |
| Attachments | ciphertext in object storage; keys never on the server |

See `docs/DATA_RETENTION.md` for what is kept and for how long.

### Account takeover

| Attack | Control | Verified by |
|---|---|---|
| Signature with the wrong key | libsignal verification | `TestVerifyRejectsWrongKey` |
| Replay a captured sign-in | single-use 60 s nonce, burned before checking | `TestVerifyRejectsReplay`, `TestVerifyBurnsChallengeOnFailure` |
| Reuse a registration signature to sign in | domain-separated labels | `TestRegisterSignatureCannotBeUsedForAuth` |
| Use a sign-in signature to delete the account | separate delete label, fresh challenge, must match the token's user | `TestDeleteAccountNeedsAFreshDeleteSignature` |
| Stolen token | memory-only on device; 15 min; logout; socket closed at expiry | H2 tests |
| Token of a deleted user | refused everywhere | `TestExpiredAndDeletedUsersTokensAreRefused` |
| Any route without a token | every `/v1` route except register/challenge/verify answers 401 | `TestEveryOtherRouteRequiresAuth` (walks the router) |

### Device compromise

| Threat | Control |
|---|---|
| Disk forensics | SQLCipher database; identity and DB keys wrapped by non-exportable Keystore keys (StrongBox when present) |
| Backups and device transfer | `allowBackup=false`, extraction rules exclude everything |
| Shoulder surfing, screen capture | Screen security (M4); private lock-screen notifications |
| Leftover data after leaving | Account deletion wipes the database, keys, Keystore entries, media and caches |
| Unlocked stolen phone | Open (M6) |

### Replay

Server dedup on `(sender, message_id)` for 30 days
(`TestDuplicateSendsAreStoredAndDeliveredOnce`); libsignal rejects reused
message keys; device-side unique message IDs; deletes and timers carry their
own timestamps and the newest wins.

### Malicious clients

| Attack | Control | Verified by |
|---|---|---|
| Malformed, binary, oversized or unknown frames | rejected or connection dropped | `TestHostileFramesAreRejectedOrDropTheConnection`, `TestRejections` |
| Acknowledge someone else's envelope | ack scoped to the authenticated recipient | `TestCannotAckSomeoneElsesEnvelope` |
| Spoof the sender | sender comes from the connection, never the frame | `TestRealTimeDeliveryAndReceipts` |
| Flood a recipient | quota (H4), 20 sends/s per connection, 64 KiB payloads, 100 recipients per fan-out | H4 test, `TestRejections` |
| Flood REST | per-IP, per-user and per-route limits | M1/M2 tests |
| Hostile payloads on the device | strict decoding, length limits on IDs and emoji, unknown kinds dropped | `PayloadCodecTest`, pipeline tests |

### Impersonation

Users are identified by their identity key. Contacts are added by QR code
(key out of band) or by username lookup (trust on first use, flagged as
unverified); safety numbers verify in person. A display name can be copied,
but not the key behind it; the verified badge only appears after comparison.

### Key changes

A changed key is never accepted silently: messages under the new key are
held unread, sending pauses, and the user must acknowledge. The
acknowledgement card ignores obscured touches (L4). Covered by
`ContactTrustTest` and the live tests.

## Testing added in this release

- Server: route walk, token expiry and revocation, account deletion
  cascade, quota, hostile frames, cross-user acks, client-IP resolution,
  per-user and registration limits, security headers.
- Server load test (`TestGatewayLoad`, `WHISPR_LOAD=1`, manual `load.yml`
  workflow): many connected users sending to random peers; fails on any lost
  or duplicated message and reports p50/p95/p99 latency.
- Android: replies, delete for everyone (and forged deletes), delete for me,
  disappearing messages on both sides, timers from non-members, forwarded
  media, search escaping, 4001 reconnect, TLS pinning, account deletion.

The Postgres-backed server tests and the load test were not run on the
release machine (Docker was not running); they run in CI. See
`docs/failures/2026-10-08-db-tests-skipped.md`.
