-- Envelopes waiting for delivery. The payload is opaque bytes: the server
-- never parses it (Phase 4 makes it libsignal ciphertext without schema
-- changes). Rows are deleted as soon as the recipient acknowledges them.
CREATE TABLE envelopes (
    seq             BIGSERIAL PRIMARY KEY,           -- delivery order per recipient
    message_id      UUID        NOT NULL,            -- client-generated
    conversation_id UUID        NOT NULL,            -- opaque to the server
    sender_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    recipient_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind            SMALLINT    NOT NULL,            -- 1 = client envelope, 2 = server delivery receipt
    ref_message_id  UUID,                            -- receipts: the message that was delivered
    client_ts       TIMESTAMPTZ NOT NULL,
    server_ts       TIMESTAMPTZ NOT NULL,
    payload         BYTEA       NOT NULL CHECK (octet_length(payload) <= 65536)
);
CREATE INDEX envelopes_recipient_seq_idx ON envelopes (recipient_id, seq);
CREATE INDEX envelopes_server_ts_idx ON envelopes (server_ts);

-- Dedup tombstones: survive envelope deletion so a retried send that arrives
-- after delivery is acknowledged again instead of delivered twice. Purged
-- after the retention window.
CREATE TABLE accepted_messages (
    sender_id   UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    message_id  UUID        NOT NULL,
    seq         BIGINT      NOT NULL,
    server_ts   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (sender_id, message_id)
);
CREATE INDEX accepted_messages_server_ts_idx ON accepted_messages (server_ts);

-- One push token per account (single device). Content-free wake-ups only.
CREATE TABLE push_tokens (
    user_id     UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    provider    TEXT        NOT NULL CHECK (provider IN ('fcm')),
    token       TEXT        NOT NULL CHECK (char_length(token) BETWEEN 1 AND 4096),
    updated_at  TIMESTAMPTZ NOT NULL
);
