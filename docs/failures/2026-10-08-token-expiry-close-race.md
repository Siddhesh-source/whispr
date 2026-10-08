# Token-expiry close sent as a bare EOF; load test miscounted accepts

**Date:** 2026-10-08
**Status:** fixed

Found when the Postgres-backed tests first ran locally (Docker started).

## 1. Expired sockets dropped without the 4001 close

`TestSocketClosesWhenItsTokenExpires` passed in the full suite but failed
11 of 15 runs on its own:

```
security_test.go:70: close status -1 (failed to get reader: failed to read frame header: EOF), want 4001
```

**Cause:** in `Gateway.ServeHTTP` the session context was cancelled before
`conn.Close(StatusTokenExpired, …)`. The read loop's `conn.Read(ctx)` uses
that context, and coder/websocket closes the connection at once when a
reader's context is cancelled, so the close frame usually lost the race.
Clients then saw an ordinary drop and reconnected with backoff (still on the
expired token) instead of signing in again immediately.

**Fix:** on token expiry, send the 4001 close first, then cancel. 20 of 20
isolated runs pass, and the full suite passes three times shuffled.

## 2. Load test reported 42 of 10,000 sends unanswered

```
load_test.go:184: accepted 9958 of 10000
```

All 10,000 messages had arrived exactly once and none were rejected. The
test stopped reading as soon as the last delivery arrived, but a sender's
`accepted` frame can still be in flight then (each connection has its own
writer). **Fix:** the test also waits until every send is answered. It
passes 3 of 3 runs: 200 users, 10,000 messages, 1,100–1,500 msg/s.
