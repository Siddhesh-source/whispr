# Release APK carried 440 MB of native debug info

**Date:** 2026-10-08
**Status:** fixed

## What failed

The first signed release build produced a 485 MB APK (112 MB AAB).
`libsignal_jni.so` was 110–122 MB per ABI; its ELF sections were mostly
`.debug_str` (73 MB), `.debug_info` (16 MB), `.debug_ranges` and
`.debug_line`, with only 6.7 MB of `.text`.

## Cause

libsignal's Android artifact ships unstripped native libraries. AGP strips
them in `stripReleaseDebugSymbols`, but only when an NDK is installed. None
was installed locally, and the project did not pin one, so AGP packaged the
libraries as they came (its warning was hidden by an up-to-date task).

## What was done

- Pinned `ndkVersion = "28.2.13676358"` (r28c) in `app/build.gradle.kts`.
- Installed that NDK at `D:\dependencies\ndk\28.2.13676358` from Google's
  repository (SHA-1 `086bba43…` matches `repository2-3.xml`).
- `release.yml` installs the same NDK with `sdkmanager` before building.
- After an `ndkVersion` change `stripReleaseDebugSymbols` stayed up to date
  locally; deleting `app/build/intermediates/stripped_native_libs` forced it
  to run. CI builds start clean.

Result: 56 MB universal APK (four ABIs), 55 MB AAB; Play delivers one ABI
per device from the AAB.
