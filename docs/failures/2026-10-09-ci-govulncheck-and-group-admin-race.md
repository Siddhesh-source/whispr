# CI failed: Go vulnerabilities and a group admin race

**Date:** 2026-10-09 · **Status:** fixed

## server · govulncheck

```
Vulnerability #1: GO-2026-6617  Found in: golang.org/x/net@v0.58.0  Fixed in: v0.60.0
                                Found in: net/http/internal/http2@go1.27.1  Fixed in: go1.27.2
Vulnerability #2: GO-2026-6613  Found in: net/http@go1.27.1  Fixed in: go1.27.2
Vulnerability #3: GO-2026-6612  ...
```

Newly published advisories in the standard library's HTTP/2 code and
`x/net`, reachable from the server's HTTP handlers and the FCM client. Not
caused by the feature work; the scheduled check caught them on the next push.

**Fix:** `go 1.27.2` in `go.mod` (the Dockerfile's `golang:1.27-bookworm`
picks up the patch release), `golang.org/x/net` v0.60.0. govulncheck now
reports 0 reachable vulnerabilities; golangci-lint 0 issues; tests pass.

## android · GroupMessagingTest

```
GroupMessagingTest > soleAdminLeavingHandsTheRoleOnAndPromotedAdminsCanManage FAILED
    java.lang.IllegalStateException at GroupMessagingTest.kt:324   (timed out: "carol is now admin")
```

**Cause (product bug, surfaced by timing).** The test waits until Carol
sees Bob leave, then Alice leaves. If Alice had not yet processed Bob's
leave, she still counted Bob as the other admin and handed the role to
nobody, so the group was left without an admin. Real users hit this when
two admins leave close together.

**Fix.** `GroupManager.ensureAdmin`: after any leave or update, an active
group with no admin promotes the same heir on every member's phone (earliest
added, then lowest user ID); the heir publishes it as a new revision. New
test `twoAdminsLeavingAtOnceStillLeaveAnAdmin` holds Bob's leave back from
Alice to force the race: it fails on the old code ("1 test completed, 1
failed") and passes now, along with the rest of the group suites.

## Local CI before pushing

Also removed: `design-screenshots.yml` (screenshots never run in CI).
Before pushing, the CI jobs were run locally: the Android build job (290
tests), the instrumented job on an emulator (20 tests), the server checks
(govulncheck, golangci-lint, tests) and the Docker image with the
real-libsignal test stage.

## Emulators wedged after a long idle

Both emulators' `system_server` died while idle (`DeadObjectException`,
`Can't find service: activity`), so account deletion couldn't finish.
Restarted them cold; the app data survived and both test accounts were
deleted through the app. Environment only; no code change.
