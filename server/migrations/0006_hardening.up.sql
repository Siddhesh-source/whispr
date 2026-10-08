-- Per-sender undelivered quota: the gateway counts a sender's queued client
-- envelopes to one recipient before accepting another (see messaging.PGStore).
CREATE INDEX envelopes_recipient_sender_idx ON envelopes (recipient_id, sender_id) WHERE kind = 1;
