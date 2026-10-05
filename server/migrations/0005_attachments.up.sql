-- Encrypted attachments. The object itself (in S3-compatible storage) is
-- ciphertext the client encrypted with a random key that never reaches the
-- server. This table only tracks what retention and quotas need.
CREATE TABLE attachments (
    id          UUID PRIMARY KEY,                     -- server-chosen, random (v4)
    -- SET NULL, not CASCADE: the row must outlive a deleted account so the
    -- janitor still deletes the object when it expires.
    uploader_id UUID        REFERENCES users (id) ON DELETE SET NULL,
    size        BIGINT      NOT NULL CHECK (size > 0),
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX attachments_created_at_idx ON attachments (created_at);
