# Contributing to Whispr

Thanks for helping. Whispr is a privacy-first messenger, so every change is
judged first by what it reveals, to whom, and for how long. This page
explains how to get set up and what a change needs before it can merge.

## Before you start

- Read `docs/ARCHITECTURE.md` and `docs/THREAT_MODEL.md`.
- For anything larger than a bug fix, open an issue first and describe the
  change. Changes to the wire format, crypto, stored data or logging need a
  short design note in `docs/designs/`.
- Security problems go through `SECURITY.md`, never a public issue.

Setup and test commands are in `docs/SETUP.md`.

## Rules that are not negotiable

1. **No cryptography of our own.** All protocol cryptography comes from
   libsignal. Platform crypto (Keystore, AES-GCM for storage) only wraps
   keys at rest.
2. **The server never sees content.** No new server-side field may contain
   message content, contact lists, or anything that could be put inside the
   encrypted payload instead.
3. **Minimal metadata.** Anything new that the server stores or logs must be
   added to `docs/THREAT_MODEL.md` and `docs/DATA_RETENTION.md` with a
   reason and a retention period.
4. **No trackers.** No analytics, crash reporters, ads SDKs or telemetry.
5. **No silent failures.** Errors are surfaced to the user or logged
   (without secrets) and tested.
6. **No secrets in logs**, on either side.

## Code

- Kotlin: ktlint (`./gradlew ktlintFormat`), Android lint, and design tokens
  from `:core:designsystem` (no colour, dp or sp literals in app code).
- Go: `gofmt`, `go vet`, `golangci-lint` (v2.14.0).
- Match the style of the surrounding code: small functions, comments that
  say why, not what.
- Every behaviour change comes with tests: unit tests for logic, the
  `FakeRelay`/`FakeGateway` device tests for messaging, Postgres integration
  tests for the server. Security-relevant changes need a test that tries the
  attack.

## Commits and pull requests

- Conventional commits: `feat(android): …`, `fix(server): …`, `docs: …`,
  `test: …`, `ci: …`, `chore: …`. Describe what changed and why.
- Keep pull requests focused. CI (`android`, `server`, `e2e`) must pass.
- If something fails along the way (a flaky test, a tool problem, a
  workaround), record it in `docs/failures/` with the date, the exact
  error, the cause and what was done, and add it to the table there.

## Licence

Whispr is AGPL-3.0, the same licence as libsignal. By contributing you
agree that your contribution is licensed under the AGPL-3.0.
