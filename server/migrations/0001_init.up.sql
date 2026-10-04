-- Users are identified by a libsignal identity public key. No phone number,
-- no email, no password. display_name is a documented interim exception
-- (see docs/THREAT_MODEL.md) until encrypted profiles land.
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    identity_key  BYTEA NOT NULL UNIQUE CHECK (octet_length(identity_key) = 33),
    display_name  TEXT  NOT NULL CHECK (char_length(display_name) BETWEEN 1 AND 64),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Single-use auth challenges. Rows are consumed (used_at set) before the
-- signature is checked, so each nonce gets exactly one attempt.
CREATE TABLE auth_challenges (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    nonce       BYTEA NOT NULL CHECK (octet_length(nonce) = 32),
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ
);
CREATE INDEX auth_challenges_expires_at_idx ON auth_challenges (expires_at);

-- Only the SHA-256 of each bearer token is stored; a database leak does not
-- yield usable tokens.
CREATE TABLE auth_tokens (
    token_hash  BYTEA PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX auth_tokens_expires_at_idx ON auth_tokens (expires_at);
