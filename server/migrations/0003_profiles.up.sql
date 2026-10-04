-- Optional username: a chosen nickname plus a server-assigned two-digit
-- number (e.g. "sam.42"), stored lowercase. Lookups require the exact handle,
-- which makes enumeration much harder than plain nicknames.
ALTER TABLE users ADD COLUMN username TEXT UNIQUE
    CHECK (username ~ '^[a-z][a-z0-9_]{2,31}\.[0-9]{2,3}$');
