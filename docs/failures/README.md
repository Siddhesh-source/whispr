# Failure log

Every build, test, tool or environment failure hit during development gets
its own document here, including flaky and pre-existing ones and anything
skipped. Each says what failed, the exact error, the cause if known, what
was done, and whether it is fixed or open.

| Date | Doc | Status |
|---|---|---|
| 2026-10-05 | [Emulator ran from C:](2026-10-05-avd-on-c-drive.md) | fixed |
| 2026-10-05 | [Python not installed](2026-10-05-python-not-installed.md) | open (env) |
| 2026-10-05 | [GitHub CLI not installed](2026-10-05-gh-cli-missing.md) | open (env) |
| 2026-10-05 | [Gradle tmpdir escaping](2026-10-05-gradle-tmpdir-escaping.md) | fixed |
| 2026-10-05 | [LiveMessagingTest failed before this work](2026-10-05-live-messaging-test-preexisting.md) | fixed |
| 2026-10-05 | [Group role mismatch](2026-10-05-group-role-mismatch.md) | fixed |
| 2026-10-05 | [LiveGroupsTest deadlock](2026-10-05-live-groups-test-deadlock.md) | fixed |
| 2026-10-05 | [Intermittent test failures: key upload never retried](2026-10-05-flaky-tests-under-load.md) | fixed |
| 2026-10-05 | [CI ciphertext scan could pass without proving anything](2026-10-05-e2e-marker-not-forwarded.md) | fixed |
| 2026-10-05 | [Compile, lint, test and tooling fixes](2026-10-05-compile-and-test-fixes.md) | fixed |
| 2026-10-07 | [Go toolchain on PATH could not compile](2026-10-07-go-toolchain-broken.md) | worked around (env) |
| 2026-10-08 | [Release build failed in R8: missing Firebase transport class](2026-10-08-release-r8-missing-class.md) | fixed |
| 2026-10-08 | [Release APK carried 440 MB of native debug info](2026-10-08-release-apk-unstripped.md) | fixed |
| 2026-10-08 | [Postgres-backed server tests skipped locally](2026-10-08-db-tests-skipped.md) | fixed (run later) |
| 2026-10-08 | [Scripted edits dropped backslash escapes](2026-10-08-shell-edit-escapes.md) | fixed |
| 2026-10-08 | [Compile and test fixes: message actions and hardening](2026-10-08-compile-and-test-fixes.md) | fixed |
| 2026-10-08 | [Token-expiry close sent as a bare EOF; load test miscounted accepts](2026-10-08-token-expiry-close-race.md) | fixed |
| 2026-10-08 | [CI e2e live tests hit the new registration limit](2026-10-08-e2e-registration-limit.md) | fixed |
| 2026-10-08 | [CI e2e bucket copy was always empty](2026-10-08-e2e-bucket-copy-empty.md) | fixed |
| 2026-10-08 | [CI e2e live tests were restored from the build cache](2026-10-08-e2e-live-tests-cached.md) | fixed |
| 2026-10-09 | [Composer dropped typed characters](2026-10-09-composer-dropped-keystrokes.md) | fixed |
| 2026-10-09 | [Fixes while building camera, GIFs, status and calls](2026-10-09-camera-gifs-status-calls-fixes.md) | fixed (2 open) |
| 2026-10-09 | [Server deploy blocked: SSH rule points at an old IP](2026-10-09-deploy-blocked-ssh-rule.md) | open |
