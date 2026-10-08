# Release build failed in R8: missing Firebase transport class

**Date:** 2026-10-08
**Status:** fixed

## What failed

The first `:app:assembleRelease` since push support was added:

```
ERROR: R8: Missing class com.google.firebase.datatransport.TransportBackend
(referenced from: java.util.List com.google.firebase.messaging.FirebaseMessagingRegistrar.getComponents())
> Task :app:minifyReleaseWithR8 FAILED
```

## Cause

`app/build.gradle.kts` excludes `firebase-datatransport` and the
`transport-*` modules on purpose (Google delivery telemetry, not needed to
receive wake-ups). `FirebaseMessagingRegistrar` still names the backend
class, and R8 treats a missing referenced class as an error. CI only builds
debug, so this was never exercised.

## What was done

Added `-dontwarn com.google.firebase.datatransport.**` to
`app/proguard-rules.pro`. The release build now completes. The release
workflow builds release on every tag, so this cannot regress silently again.
