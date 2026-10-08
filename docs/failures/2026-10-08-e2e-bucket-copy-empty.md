# CI e2e bucket copy was always empty

**Date:** 2026-10-08
**Status:** fixed

## What failed

The `e2e` workflow's "Copy the object storage bucket" step failed on
`a387709` (2026-10-05) and on both pushes of 2026-10-08, after the live
tests passed. It had not been recorded before.

## Cause

The step runs `cgr.dev/chainguard/minio-client` (`mc mirror`) with
`$RUNNER_TEMP/bucket` bind-mounted as the target. The image runs as uid
65532; the directory is created by the runner user (uid 1001, mode 755). `mc`
cannot write there, but it **exits 0** without copying anything, so the
step's own guard (`test "$count" -gt 0`) is what failed.

Reproduced locally on a Linux volume: directory owned by 1001 with mode 755
gives exit 0 and 0 files; after `chmod 777`, 15 objects are copied.

The ciphertext scan for the marker never ran on those commits, because it
comes after this step.

## What was done

`e2e.yml` makes the target directory writable before the copy.
