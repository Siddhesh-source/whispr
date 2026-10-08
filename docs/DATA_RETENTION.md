# Data retention

Whispr keeps as little as it can, for as short a time as it can. This page
lists everything the server stores, how long it lives and what removes it,
and what stays on the phone. Message content never appears in this list in
readable form: the server only ever holds libsignal ciphertext.

## On the server

| Data | Kept for | Removed by |
|---|---|---|
| Account: user ID, identity public key, display name, creation time | Until the account is deleted | `DELETE /v1/me` |
| Username (optional) | Until released or the account is deleted | `DELETE /v1/me/username`, account deletion |
| Sign-in challenge (nonce) | 60 seconds, single use | Consumed on first attempt; janitor deletes expired rows |
| Session token | 15 minutes (SHA-256 hash only) | Expiry janitor, `POST /v1/auth/logout`, account deletion |
| Undelivered envelope (ciphertext and routing: sender, recipient, conversation ID, timestamps, padded size) | Until the recipient acknowledges it, at most 30 days | Ack, retention janitor, deletion of either account |
| Delivery receipt (server-generated) | Until the sender's device acknowledges it, at most 30 days | Same as envelopes |
| Dedup tombstone (sender, message ID, time) | 30 days | Retention janitor, deletion of the sender |
| Public prekeys (signed, one-time, Kyber) | Until used or replaced | Fetch consumes one-time keys; upload replaces; account deletion |
| Push token (FCM) | Until replaced, unregistered by FCM, removed, or the account is deleted | `PUT`/`DELETE /v1/push/token`, FCM "unregistered", account deletion |
| Attachment ciphertext and its row (uploader, size, time) | 30 days after upload (`ATTACHMENT_RETENTION`) | Retention janitor; account deletion clears the uploader, the janitor still deletes the blob on schedule |

Never stored: message plaintext, attachment keys, group names, pictures or
member lists, reactions, read receipts in readable form, typing indicators
(relayed live only), contact lists, phone numbers, e-mail addresses,
passwords, IP addresses.

Held in memory only and never written to disk or logs: client IP addresses
(rate-limiter buckets, evicted when idle), which users are connected right
now, recent push wake-ups.

### Logs

Request logs record method, route pattern, status and latency. They do not
record IP addresses, user IDs, tokens, keys, payloads or display names; a
redactor masks such fields if they ever reach a log call. Log retention is
up to the operator; the deployment guide suggests 14 days.

### Backups

Postgres backups contain the rows above as they were at backup time. An
operator who keeps backups should expire them on the same 30-day horizon
(the deployment guide's example does) so deleted accounts and delivered
envelopes do not outlive their retention in old snapshots.

## On the phone

Everything below lives in the app's private storage, in the SQLCipher
database or as encrypted blobs, and is excluded from backups and device
transfer.

| Data | Kept for |
|---|---|
| Messages, reactions, attachments | Until deleted by you, deleted for everyone by their author, or expired by a disappearing-message timer |
| Disappearing messages | Removed when their timer runs out: counted from sending for your own messages and from reading for received ones. The sweep runs every minute while the app is running and on start |
| Contacts, groups, identity and session keys | Until removed or the account is deleted |
| "Open with" decrypted copies | Deleted on the next app start |
| Session token | Memory only; never written to disk |

**Deleting your account** (Settings → Delete account) removes the account on
the server first; only if that succeeds does the app erase the database,
wrapped keys, the Keystore keys that wrap them, media, the avatar and caches.
Your contacts keep the messages they already received.

## Settings an operator can change

| Variable | Default | Effect |
|---|---|---|
| `TOKEN_TTL` | `15m` | Session token lifetime |
| `CHALLENGE_TTL` | `60s` | Sign-in challenge lifetime |
| `ATTACHMENT_RETENTION` | `720h` (30 days) | Attachment blob lifetime |

Envelope and tombstone retention (30 days) is fixed in code.
