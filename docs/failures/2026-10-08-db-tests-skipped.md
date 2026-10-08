# Postgres-backed server tests skipped locally

**Date:** 2026-10-08
**Status:** fixed (run once Docker was started)

## What failed

`go test ./...` passes, but every test that needs Postgres skipped:
the auth store contract tests against `PGStore`, the messaging integration
and security tests (`security_test.go`), and the gateway load test. The
Docker daemon was not running, so there was no database at
`WHISPR_TEST_DATABASE_URL`.

## Cause

Docker Desktop must be started by hand on this machine (`docker.exe` is not
on `PATH`).

## What was done

The tests compile and the in-memory store contract tests ran. CI
(`server.yml`) runs the Postgres tests against a service container on every
push, so they are not skipped there.

## Follow-up

Start Docker Desktop, then run locally:

```
docker compose up -d db
export WHISPR_TEST_DATABASE_URL='postgres://whispr:whispr-dev-only@127.0.0.1:5432/whispr?sslmode=disable'
go test ./...
WHISPR_LOAD=1 go test ./internal/messaging -run TestGatewayLoad -v
```

## Result

Docker was started later the same day. The full suite passed against
Postgres (three shuffled runs), and the load test passed. Running them
exposed two real problems, recorded in
`2026-10-08-token-expiry-close-race.md`.
