-- Public prekeys for libsignal session setup (PQXDH). The server stores only
-- public halves and their signatures; it verifies the signatures against the
-- owner's identity key on upload and never sees private keys or sessions.

-- libsignal registration IDs are 14-bit and never 0.
ALTER TABLE users ADD COLUMN registration_id INTEGER CHECK (registration_id BETWEEN 1 AND 16380);

-- The current signed EC prekey (one per user; replaced on rotation).
CREATE TABLE signed_prekeys (
    user_id      UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    key_id       INTEGER NOT NULL CHECK (key_id BETWEEN 0 AND 16777215),
    public_key   BYTEA   NOT NULL CHECK (octet_length(public_key) = 33),
    signature    BYTEA   NOT NULL CHECK (octet_length(signature) = 64),
    uploaded_at  TIMESTAMPTZ NOT NULL
);

-- The current last-resort Kyber prekey: handed out when no one-time Kyber
-- prekey is left, and never deleted on use.
CREATE TABLE kyber_last_resort (
    user_id      UUID PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    key_id       INTEGER NOT NULL CHECK (key_id BETWEEN 0 AND 16777215),
    public_key   BYTEA   NOT NULL CHECK (octet_length(public_key) = 1569),
    signature    BYTEA   NOT NULL CHECK (octet_length(signature) = 64),
    uploaded_at  TIMESTAMPTZ NOT NULL
);

-- One-time EC prekeys: each is handed out at most once, then deleted.
CREATE TABLE one_time_prekeys (
    user_id     UUID    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_id      INTEGER NOT NULL CHECK (key_id BETWEEN 0 AND 16777215),
    public_key  BYTEA   NOT NULL CHECK (octet_length(public_key) = 33),
    PRIMARY KEY (user_id, key_id)
);

-- One-time Kyber prekeys: each is handed out at most once, then deleted.
CREATE TABLE kyber_prekeys (
    user_id     UUID    NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    key_id      INTEGER NOT NULL CHECK (key_id BETWEEN 0 AND 16777215),
    public_key  BYTEA   NOT NULL CHECK (octet_length(public_key) = 1569),
    signature   BYTEA   NOT NULL CHECK (octet_length(signature) = 64),
    PRIMARY KEY (user_id, key_id)
);

-- From now on payloads are libsignal ciphertext. Undelivered envelopes from
-- before this migration may hold plaintext: delete them (development build,
-- no migration of in-flight messages).
DELETE FROM envelopes;
