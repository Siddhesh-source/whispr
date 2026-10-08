# Server API

The Whispr server speaks JSON over HTTPS and one WebSocket. Everything here
is transport: message content travels as opaque libsignal ciphertext that the
server never parses. The client's half of each contract lives in
`android/data/src/main/kotlin/dev/whispr/data/network/`.

Conventions:

- Base path `/v1`. Request and response bodies are JSON objects; unknown
  fields are rejected; bodies are limited to 64 KiB unless noted.
- `[]byte` fields are standard base64. IDs are UUIDs. Times are RFC 3339 UTC.
- Errors: `{"code": "...", "message": "..."}` with the HTTP status. Codes are
  stable; messages are for humans.
- `429 rate_limited` comes with `Retry-After` (seconds).
- Authenticated routes need `Authorization: Bearer <token>`. Without a valid
  token they answer `401 unauthorized`.

## Rate limits

| Scope | Limit | Setting |
|---|---|---|
| Register, challenge, verify (per client IP) | 30 a minute | `RATE_LIMIT_PER_MINUTE` |
| Register (per client IP, in addition) | 10 an hour | `REGISTRATIONS_PER_HOUR` |
| Every authenticated route (per user) | 120 a minute | `USER_REQUESTS_PER_MINUTE` |
| Key bundle fetch | 20 a minute per requester, 6 an hour per requester and target | code |
| Username lookup (per client IP) | 10 a minute | code |
| Attachment upload (per user) | 30 a minute | code |
| WebSocket sends (per connection) | 20 a second | code |

Client IPs come from the TCP peer, or from `X-Forwarded-For` only when the
peer is in `TRUSTED_PROXIES` (see `docs/DEPLOYMENT.md`).

## Health

`GET /healthz` → `200 {"status":"ok","database":"up"}` when the database
answers, `503 {"status":"unavailable","database":"down"}` otherwise. No authentication; not under `/v1`.

## Accounts and sign-in

There are no passwords. An account is an identity key pair generated on the
phone; signing in proves possession of the private key.

Signed messages (byte strings, shared test vectors in
`server/internal/auth/messages_test.go` and `AuthMessagesTest.kt`):

```
register: "whispr-register-v1" 0x00 || identity_key (33 bytes) || display_name (UTF-8)
auth:     "whispr-auth-v1"     0x00 || user_id (16 bytes)     || nonce (32 bytes)
delete:   "whispr-delete-v1"   0x00 || user_id (16 bytes)     || nonce (32 bytes)
```

### `POST /v1/register`

```json
{"identity_key": "<base64, 33 bytes>", "display_name": "Ada", "signature": "<base64>"}
```

`201 {"user_id": "..."}` for a new account, `200` with the same ID when the
key is already registered (safe retry). `400 invalid_input` for a malformed
key or name (1–64 characters, no control or bidi-override characters, no
leading or trailing spaces), `401 auth_failed` for a
bad signature.

### `POST /v1/auth/challenge`

`{"user_id": "..."}` → `200 {"challenge_id": "...", "nonce": "<base64, 32 bytes>", "expires_at": "..."}`.
`404 unknown_user`. The challenge lives 60 seconds and can be answered once.

### `POST /v1/auth/verify`

`{"challenge_id": "...", "signature": "<base64 over the auth message>"}` →
`200 {"token": "...", "expires_at": "..."}`. Every failure is
`401 auth_failed`; the challenge is consumed by the first attempt either way.
Tokens last 15 minutes; clients sign in again when one expires.

### `GET /v1/me`

`200 {"user_id": "...", "display_name": "..."}`.

### `POST /v1/auth/logout`

Revokes the presented token and closes the user's WebSocket. `204`.

### `DELETE /v1/me`

Deletes the account. Needs the token **and** a fresh challenge signed over the
delete message:

```json
{"challenge_id": "...", "signature": "<base64 over the delete message>"}
```

`204` on success; the user's sockets close and every token stops working.
`401 auth_failed` if the challenge is unknown, expired, used, belongs to
someone else, or the signature does not verify. Deleted: the account,
username, keys, tokens, push token, and envelopes to and from the account.
Attachments it uploaded expire on their normal schedule.

## Profiles and usernames

| Route | Body | Response |
|---|---|---|
| `GET /v1/me/profile` | | `200 {"user_id", "display_name", "identity_key", "username"?}` |
| `PUT /v1/me/profile` | `{"display_name": "..."}` | `204`; `400 invalid_input` |
| `PUT /v1/me/username` | `{"nickname": "ada"}` | `200 {"username": "ada.42"}`; the server picks the number. `400 invalid_input`, `409 unavailable` |
| `DELETE /v1/me/username` | | `204` |
| `GET /v1/usernames/{username}` | | `200` profile as above; `404 not_found`. Exact handles only |
| `GET /v1/users/{id}` | | `200 {"user_id", "display_name", "identity_key"}`; `404 unknown_user` |

## Prekeys

### `PUT /v1/keys`

Uploads any of: `registration_id`, `signed_prekey`, `kyber_last_resort`,
`one_time_prekeys`, `kyber_prekeys`. Signed keys are
`{"key_id", "public_key", "signature"}`, one-time keys `{"key_id", "public_key"}`.
Signatures are checked against the account's identity key. `204`;
`400 invalid_keys`, `400 too_many_keys`, `409 key_id_conflict`. Body limit is
larger than the default to fit a batch of Kyber keys.

### `GET /v1/keys/count`

`200 {"registration_id", "signed_prekey_id", "kyber_last_resort_id", "one_time_prekeys", "kyber_prekeys"}`
so the device knows when to top up.

### `GET /v1/keys/{user_id}`

A PQXDH bundle for starting a session:
`{"user_id", "device_id", "registration_id", "identity_key", "signed_prekey", "one_time_prekey"?, "kyber_prekey"}`.
Each fetch consumes one one-time key and one Kyber key (falling back to the
last-resort Kyber key). `400 self`, `404 unknown_user`, `404 no_keys`.

## Push

`PUT /v1/push/token` with `{"provider": "fcm", "token": "..."}` → `204`.
`DELETE /v1/push/token` → `204`. Pushes are data-only wake-ups
(`{"t":"wake"}`) with no content, sender or conversation.

## Attachments

### `POST /v1/attachments`

Body: the encrypted blob as `application/octet-stream`, with `Content-Length`.
At most 25 MiB of plaintext (+28 bytes of nonce and tag). `201 {"id": "..."}`;
`411 length_required`, `400 empty`, `413 too_large`, `503 media_unavailable`
when the server has no object storage.

### `GET /v1/attachments/{id}`

`200` with the blob (`Cache-Control: no-store`); `404 not_found` once
expired (30 days). Any account that knows the ID can download it: the ID and
the key travel inside the encrypted message, and the blob is useless without
the key.

## WebSocket: `GET /v1/ws`

Upgrade with the bearer token. One connection per user; a new one replaces
the old. Heartbeats are WebSocket pings (every 25 s; 10 s to answer).

The socket is closed with status **4001** when the token it was opened with
expires (or is revoked). Clients should sign in again and reconnect at once;
nothing is lost.

Frames are JSON text. Client to server:

| Frame | Fields | Meaning |
|---|---|---|
| `send` | `id`, `conversation_id`, `recipient_id`, `client_ts`, `payload` | Store and forward one envelope |
| `send_multi` | `id`, `conversation_id`, `recipient_ids` (≤ 100), `client_ts`, `payload` | One payload fanned out to each recipient (groups) |
| `ack` | `seq` | The envelope is durably stored on this device; delete it |
| `transient` | `recipient_id`, `conversation_id`, `payload` (≤ 1 KiB) | Best effort to a live connection, never stored (typing) |

Server to client:

| Frame | Fields | Meaning |
|---|---|---|
| `accepted` | `id`, `seq`, `server_ts` | Durably stored: the message is "sent" |
| `rejected` | `id`, `code` | Not stored; see codes |
| `envelope` | `seq`, `id`, `conversation_id`, `sender_id`, `kind` (`envelope` or `delivered`), `ref_id`?, `client_ts`, `server_ts`, `payload` | Deliver; the client acks `seq` after storing it |
| `transient` | `sender_id`, `conversation_id`, `payload` | Live-only relay |

Rejection codes: `invalid` (malformed, payload over 64 KiB, timestamp before
2020 or more than a day ahead, too many recipients), `unknown_recipient`,
`self`, `recipient_full` (1,000 undelivered envelopes from you to that
recipient), `rate_limited`, `internal`.

Guarantees: `(sender, id)` is stored once (resends are deduplicated for 30
days); envelopes for one recipient are delivered in `seq` order and
redelivered until acknowledged; the sender comes from the authenticated
connection, never the frame; `delivered` receipts are generated by the server
when the recipient acks. Undelivered envelopes expire after 30 days.

## Payloads (inside the ciphertext)

The server never sees these. They are listed so other clients can
interoperate. After decryption a payload is JSON with a `t` discriminator
(`text`, `media`, `reaction`, `delete`, `timer`, `read`, `typing`,
`contact_request`, `reset`, `reset_done`, `group`, `sender_key`,
`group_join`, `group_decline`, `group_leave`). The fields added in this release:

- `text` / `media`: `q` and `qa` (the quoted message's ID and author),
  `fwd` (forwarded), `exp` (disappearing timer in seconds).
- `delete`: `target`, `ts`, `g`? — the author deletes their own message;
  receivers check the author and a 24-hour window.
- `timer`: `seconds` (0 = off, at most 28 days), `ts`, `g`? — the newest
  change wins.

See `android/data/src/main/kotlin/dev/whispr/data/messaging/Payload.kt`.
