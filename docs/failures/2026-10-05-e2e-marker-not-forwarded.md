# The CI ciphertext scan could pass without proving anything

## What failed

- `data/build.gradle.kts` forwarded only `WHISPR_SERVER_URL` to the test JVM, not `WHISPR_E2E_MARKER`.
- In CI, `LiveMessagingTest.markerTravelsAndRestsOnlyAsCiphertext` was therefore always skipped (`assumeTrue` on the marker).
- `LiveGroupsTest` used a random marker of its own.
- The e2e job then searched the database dump, traffic and media bucket for `$WHISPR_E2E_MARKER`, a string no test had ever sent. The scan would pass even if plaintext had leaked.
- My earlier local check had the same blind spot: it reported "marker absent", but the tests never used that marker.

## Found by

Checking why one test was still reported as skipped with the marker set.

## Fix

- `data/build.gradle.kts` forwards `WHISPR_E2E_MARKER`.
- Re-ran all live tests with a fixed marker: 5 run, 0 skipped, 0 failed.
- Mirrored the MinIO bucket (12 objects) and dumped Postgres. No raw or hex copy of the marker in either.
- **Positive control:** a file containing the marker added to the copied bucket was found, so the scan can detect a leak.

## Second failure the fix exposed

- With the marker test running, `LiveServerTest` failed with `Err(Network)`. The server log showed one `429` on `/v1/auth/challenge`.
- Cause: unauthenticated auth calls are limited to 30 a minute per IP, and the live suite registers and signs in about 10 users from 127.0.0.1 within a minute. CI would hit the same limit.
- Fix:
  - `docker-compose.yml` reads `RATE_LIMIT_PER_MINUTE` (default unchanged at 30).
  - The e2e workflow starts the stack with 600.
  - Local live runs use `RATE_LIMIT_PER_MINUTE=600 docker compose up -d server`.
- After the fix: two full data-suite runs with the live server, 124 run, 0 skipped, 0 failed, and no `429`s.

## Status

Fixed. The CI job itself hasn't run yet: nothing is pushed.
