# Security, polish and release

Status: plan. Goal: a beta that is safe to publish as open source.

## 1. Message features (Android)

All new payload kinds are encrypted like every other payload. Old clients
drop unknown kinds (`PayloadCodec.decode` returns null) and ignore unknown
JSON keys, so the additions are backward compatible.

| Feature | Wire | Storage (Room v6, auto-migration) | Rules |
|---|---|---|---|
| Reply | `Text.q` / `Media.q` = quoted logical message ID, `Text.qa` = quoted author | `messages.quoteId`, `messages.quoteAuthor` | The quote is resolved locally by `(conversation, author, mid)`; if we don't have it, the bubble says "Original message not found". Nothing from the quoted message travels again. |
| Forward | A new `Text`/`Media` with `fwd = true`. Media is decrypted locally and **re-encrypted with a fresh key and re-uploaded** | `messages.forwarded` | Only shown content can be forwarded (no placeholders, deleted or system rows); media only once downloaded. Reusing the blob would let the server link the forward to the original upload (two conversations fetching one blob ID) and inherit its expiry. |
| Delete for me | none | row deleted, plus its attachment file and reactions | Any message. |
| Delete for everyone | `Payload.Delete(target, ts, g?)` | `messages.deleted = 1`, body cleared, attachment file and row deleted, reactions deleted | Only the original author, only our own messages, within 24 h of sending. The receiver checks that the sender of the delete is the author of the target (1:1: pairwise sender; group: sender-key sender). A delete for a placeholder marks it deleted too, so a later resend cannot resurrect it (`recoverPlaceholder` skips deleted rows). A delete whose target we never had is ignored. |
| Copy | none | none | Text only. The clip is marked sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, API 33+) so the clipboard preview hides it. |
| Reactions | done | done | Unchanged. |
| Disappearing messages | `Payload.Timer(seconds, ts, g?)`; every `Text`/`Media` carries `exp` (seconds) | `conversation_settings(conversationId, timer, ts)`; `messages.expiresIn`, `messages.expireAt` | Options: off, 5 min, 1 h, 1 day, 1 week. Newest `Timer` (by `ts`) wins; anyone in a 1:1, any current member in a group. A message keeps the timer it was sent with (`exp`), so a later change never shortens or extends it. Outgoing: the clock starts when sent; incoming: when read. A sweeper deletes expired rows (and their attachment files and reactions) every minute and on start. A system notice records each change. |
| Search | none | none (LIKE over `messages.body` in the SQLCipher DB; SQLite folds ASCII case only, so non-ASCII search is case-sensitive, documented) | From the chat list. Case-insensitive substring, `%`/`_` escaped, newest first, at most 100 hits, excludes placeholders, deleted and system rows. Tapping a hit opens the chat. The index never leaves the encrypted database, so no FTS shadow table is needed. |
| Screen security | none | `settings.screenSecurity` (default **on**) | `FLAG_SECURE` on the single activity: no screenshots, no screen recording, blank recents thumbnail. Toggle in Settings → Privacy. |

## 2. Server hardening

| # | Change | Why |
|---|---|---|
| S1 | **Trusted proxies.** `TRUSTED_PROXIES` (CIDR list). When the TCP peer is trusted, the client IP is the rightmost `X-Forwarded-For` address that is not itself a trusted proxy; otherwise the peer address. | Behind the TLS reverse proxy in the deployment guide every request would come from the proxy's IP, so the per-IP limits would become one global limit (a trivial lock-out). |
| S2 | **WebSocket sessions end with their token.** `Authenticate` returns the expiry; the gateway closes the socket (status 4001 `token_expired`) at expiry. The client treats 4001 as "token expired": it invalidates the token and reconnects at once, without backoff. Nothing is lost: envelopes are durable and the outbox resends unacknowledged sends (deduplicated by the server). | Today a socket opened with a 15-minute token lives forever, so a revoked or expired token keeps working. |
| S3 | **Logout and account deletion.** `POST /v1/auth/logout` deletes the presented token and closes the user's socket. `DELETE /v1/me` requires, besides the token, a fresh challenge signed over `"whispr-delete-v1" 0x00 || user_id || nonce` (an irreversible action needs proof of the identity key, not just a bearer token). It deletes the account (cascades to keys, envelopes, tokens, push token, username; attachment rows keep `uploader_id = NULL` so the janitor still deletes the blobs) and disconnects live sockets. Android: Settings → Delete account (confirmation, then local wipe). | Session revocation, and data minimisation: a user can remove everything the server holds. |
| S4 | **Per-user limit on every authenticated REST route**: 120 requests a minute (keyed by user ID), in addition to the existing per-route limits. | No authenticated route other than bundles, uploads and username lookups was limited. |
| S5 | **Registration limit**: 10 a hour per IP, separate from the sign-in limit. | Account creation was only bounded by the shared 30/min auth limit. |
| S6 | **Undelivered quota**: at most 1,000 undelivered envelopes from one sender to one recipient (checked under the recipient lock; index on `(recipient_id, sender_id)`). Server receipts (kind 2) are not counted. The check is `count(*)` over a `LIMIT 1001` subquery, once per recipient. Over it: `rejected` with `recipient_full` (permanent: the message shows Failed and can be retried). | One account could fill another's queue without bound. |
| S7 | **Input bounds**: reject `client_ts` before 2020 or more than a day in the future; `MaxHeaderBytes` 16 KiB; the push wake map pruned. | Garbage timestamps reached the database; 1 MiB headers; an unbounded map. |
| S8 | **Security headers** on every response: `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`. HSTS is set by the proxy (deployment guide). | Defence in depth; the API never serves HTML. |

Retention policy (documented in `docs/DATA_RETENTION.md`, all existing
except account deletion): challenges 60 s, tokens 15 min, undelivered
envelopes and dedup tombstones 30 days, attachments 30 days, push token until
replaced or unregistered, account rows until deleted.

## 3. Client hardening

| # | Change |
|---|---|
| C1 | **Release server URL and pins from build properties.** `whispr.releaseServerUrl` (required for `assembleRelease`, must be `https://`) and `whispr.certPins` (comma-separated `sha256/…` SPKI pins, at least two: current + backup). Pins become an OkHttp `CertificatePinner` for the server host. A release build without pins fails unless `whispr.allowUnpinned=true` is passed explicitly (for self-hosters who accept system-CA trust). |
| C2 | **TLS policy in code**: OkHttp `connectionSpecs = [MODERN_TLS]` (TLS 1.2+); `ServerConfig` rejects a non-HTTPS URL unless it is loopback in a debug build. |
| C4 | Client security tests: a MockWebServer with a `HeldCertificate` proves that a pin mismatch fails the call and a matching pin passes; `ServerConfig` rejects `http://` outside loopback-debug. |
| C3 | **Session protection**: token stays memory-only (unchanged); the socket handles 4001 by invalidating the token and reconnecting; account deletion wipes the database, secrets and media. Sensitive screens (safety number, key-change acknowledgement, delete account) set `filterTouchesWhenObscured` (tapjacking). |

## 4. Audit, tests, load

- `docs/SECURITY_REVIEW.md`: the whole codebase against the threat-model
  categories (MITM, server compromise, database leak, account takeover, device
  compromise, replay, malicious clients, impersonation, key changes). Findings
  ranked Critical/High/Medium/Low/Info, each with status. Fix Critical and
  High; the S/C items above are the fixes.
- Security tests (server, Go):
  - route walk: every `/v1` route except register/challenge/verify answers 401 without a token, with a malformed token, with an expired token and with a deleted user's token;
  - auth attacks: wrong-key signature, signature replayed on a second challenge, challenge reused, register signature used as auth, cross-user challenge;
  - replay: same `(sender, id)` twice stores once; ack of another user's seq does nothing;
  - invalid messages: malformed JSON, binary frames, unknown frame types, oversized payloads, zero IDs, bad timestamps, too many recipients;
  - unauthorized access: bundle of self, ack of others' envelopes, quota, logout revokes, socket closed at expiry.
- Android tests for each feature (engine on `FakeRelay`/`FakeGateway`, real libsignal), including: delete-for-everyone forged by a non-author is ignored; timer from a non-member ignored; expired messages swept; forwarded media decrypts at the recipient; search escaping.
- Load test: `server/internal/messaging/load_test.go`, run with
  `WHISPR_LOAD=1` against Postgres: N connected users (default 200) each
  sending M messages to random peers; reports throughput and p50/p95/p99
  accept and end-to-end latency, and fails if any message is lost or
  duplicated. A manual `load.yml` workflow runs it.

## 5. Release

- README (rewritten for users and contributors), `docs/SETUP.md`,
  `docs/API.md` (REST and WebSocket), `docs/DEPLOYMENT.md` (compose + Caddy
  TLS, secrets, backups, trusted proxies, upgrades), `SECURITY.md`
  (private disclosure via GitHub security advisories, scope, response times),
  `CONTRIBUTING.md`, `ROADMAP.md`; ARCHITECTURE and THREAT_MODEL updated.
  License stays AGPL-3.0.
- Signed beta: release `signingConfig` read from `WHISPR_KEYSTORE_*` env vars
  or `android/keystore.properties` (git-ignored); `versionName 0.1.0-beta.1`.
  `release.yml` on `v*` tags builds the signed APK and AAB from repository
  secrets, prints the signing certificate SHA-256, writes `SHA256SUMS`, and
  attaches them to a GitHub release. Locally the build is verified with a
  throwaway keystore; the real key is created by the maintainer.

## Order of work

1. Server: S1–S8 with tests, route walk and security tests.
2. Load test.
3. Android: Room v6 and payloads; reply, forward, delete, copy; timers;
   search; screen security; C1–C3; account deletion.
4. Security review document.
5. Docs and signed release.

## Not in this release

Encrypted profiles, multi-device, backups, sealed sender, app lock, incognito
keyboard flag (Compose has no API yet), padded attachment sizes.

## Review notes (applied above)

1. **[P1] Forward reused the attachment blob.** Two conversations fetching one blob ID tells the server who forwarded what, and the forward expires with the original. Now re-encrypted and re-uploaded.
2. **[P1] Account deletion on a bearer token alone.** Irreversible; now needs a fresh signed challenge (`whispr-delete-v1`).
3. **[P2] Delete-for-everyone vs. placeholders.** A resend could resurrect a deleted message. Deletes now mark placeholders too.
4. **[P2] Socket expiry flapping.** 4001 reconnects immediately, without backoff; durability makes it lossless.
5. **[P2] Quota query cost.** Bounded `LIMIT 1001` count per recipient; server receipts excluded.
6. **[P3] Search case folding** is ASCII-only in SQLite; documented rather than adding ICU.
7. **[P3] Client TLS tests** were missing; added C4.
8. **[P3] Notifications** for expired or deleted messages: cancel the conversation's notification when its rows are swept or deleted.
9. **Accepted risks:** the load test runs in-process (no TLS or proxy); screen security defaults on, so `adb screencap` QA needs it turned off first.

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|---|---|---|---|---|---|
| Eng review | /plan-eng-review | Plan before implementation | 1 | CLEAR (fixes applied) | 9 (2 P1, 3 P2, 3 P3, 1 accepted) |

VERDICT: cleared to implement. One compressed pass per the maintainer's standing rule (one quick hard review, no rounds); the outside-voice pass and per-finding questions were skipped for the same reason.

NO UNRESOLVED DECISIONS
