# CI e2e live tests hit the new registration limit

**Date:** 2026-10-08
**Status:** fixed

## What failed

The `e2e` workflow's "Live tests" step failed on the first push of the
hardening work (run 37785485265). The other workflows passed.

## Cause

The live suite runs every client from 127.0.0.1 against the compose server
and registers 11 accounts (LiveMessagingTest 2+2+3, LiveGroupsTest 3,
LiveServerTest 1). The new per-IP registration limit is 10 an hour, so the
11th registration got `429` and the test's `as AuthResult.Ok` cast failed.
`docker-compose.yml` passed only `RATE_LIMIT_PER_MINUTE` through, so the
workflow could not raise the new limits.

The failure was diagnosed from the job's step status and the test layout;
the job log needs authentication and was not downloaded.

## What was done

`docker-compose.yml` now passes `REGISTRATIONS_PER_HOUR` and
`USER_REQUESTS_PER_MINUTE` (defaults unchanged), and `e2e.yml` raises both
for the live run, as it already did for sign-ins.
