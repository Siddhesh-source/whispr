# CI e2e live tests were restored from the build cache

**Date:** 2026-10-08
**Status:** fixed

## What failed

After the bucket directory was made writable, "Copy the object storage
bucket" still failed. Diagnostic annotations (run 37791982595) showed:

```
bucket copy :: objects=0 ls_rc=0 mirror_rc=0 networks=bridge host none whispr_default
server media log :: {"msg":"media enabled","retention":"720h0m0s"}
```

The bucket was genuinely empty, although `LiveGroupsTest` uploads an image.

## Cause

`org.gradle.caching=true`, and `data/build.gradle.kts` passed
`WHISPR_SERVER_URL` and `WHISPR_E2E_MARKER` to the test JVM with
`test.environment(...)`, which is not a task input. Once the Android sources
stopped changing between pushes, `:data:testDebugUnitTest` was restored from
the cache of an earlier successful run: no live test ran, nothing was
uploaded, and the "Live tests" step still passed. The ciphertext scans
would have checked a database and bucket that the tests never touched.

## What was done

- Both variables are now declared test inputs (`inputs.property`), and when a
  server URL is set the test task is never cached or up to date.
- New `e2e` step "Live tests really ran" fails if any `Live*` test class has
  no result file or skipped tests.
- Verified locally: with `WHISPR_SERVER_URL` set, the test task executes
  again instead of being up to date.
