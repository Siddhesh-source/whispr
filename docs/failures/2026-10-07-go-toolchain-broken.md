# Go toolchain on PATH could not compile

**Date:** 2026-10-07
**Status:** worked around (environment)

## What failed

Every `go` command except `go version` failed:

```
go: cannot find GOROOT directory: 'go' binary is trimmed and GOROOT is not set
```

With `GOROOT=D:\` set:

```
go: no such tool "compile"
```

## Cause

`D:\bin\go.exe` (first on `PATH`) belongs to a Go 1.27.1 install rooted at
`D:\` whose `pkg\tool\windows_amd64` directory (compile, link, asm, …) is
missing. The binary is built with `-trimpath`, so it cannot find its root
without `GOROOT`, and even with it the compiler is absent.

## What was done

Downloaded the official `go1.27.1.windows-amd64.zip` and unpacked it to
`D:\go-sdk\go` (not C:, which is nearly full). Server builds and tests run
with `PATH=/d/go-sdk/go/bin:$PATH`. The broken install at `D:\` was left
untouched.

## Follow-up

Put `D:\go-sdk\go\bin` ahead of `D:\bin` on the user `PATH`, or repair the
`D:\` install with `D:\go1.27.1.windows-amd64.msi`.
