# Fixes while building camera, GIFs, status and calls

**Date:** 2026-10-09 · **Status:** fixed except where noted

| What failed | Exact error / symptom | Cause | Fix |
|---|---|---|---|
| Lint | `AnimatedImage.kt:46: Error: Call requires API level 28 (current min is 26)` | The API check was a boolean computed earlier; lint can't follow it | Version check inline in the decode call; `Animatable` (API 1) for start/stop |
| Lint | `CallOverlay.kt:134: Error: LocalContext should not be cast to Activity, use LocalActivity instead` | Cast of `LocalContext` | `LocalActivity.current` |
| Compile | `WebRtcCallMedia.kt: Unresolved label` (3×) | `return@execute` left after renaming the executor helper to `post` | `return@post` |
| Compile | `This foundation API is experimental` | `Modifier.contentReceiver` needs opt-in | `@OptIn(ExperimentalFoundationApi::class)` on `MessageInputBar` |
| `CallManagerTest.glareKeepsTheLowerCallIdOnBothSides` | `expected:<1> but was:<2>` (media closed twice) | Real bug: on glare the old session was closed, then closed again by `newSession()` | Clear `session` after closing it |
| `CallManagerTest.localCandidatesAreBatchedAndRemoteOnesApplied` | `ClassCastException: CallSignal$Offer cannot be cast to CallSignal$Ice` | Test bug: `filterIsInstance<Pair<UserId, Ice>>` can't see the erased type | Filter the signals, not the pairs |
| `StatusCallsUiTest` (2) | Node "New status" / "3" not found in the merged tree | FAB and badge text are merged into their parents | `useUnmergedTree = true` |
| String script | `AssertionError` in a Python edit script | The match text for `calls_failed` differed (escaped apostrophe); nothing was written | Re-applied with the exact text |
| Shell heredocs | `unexpected EOF while looking for matching '` | Inline Python with quotes inside bash heredocs | Edits through a JSON-driven script file |
| Device: settings | Account-ID accessibility label read literally `${accountIdLabel}: ${state.userId}` | Escaped `$` in a Kotlin string (pre-existing) | Plain string template |
| Device: chat list | 1:1 chats whose last message was media showed an empty preview (pre-existing) | The 1:1 conversation query didn't load the attachment kind (the group query did) | Query loads kind and content type; GIFs read "GIF" |
| Device: media bubbles | Screen readers heard "You, 5:08 AM: ." for a photo (pre-existing) | Spoken text used only the message text | `attachmentLabel` ("Photo", "GIF", "Voice message", file name) |
| Device: status composer | Blank white screen once, Compose root reported 0 width | Followed reinstalling over the running app; not reproduced after a clean restart or on the other phone | Open: watch for it; no code change |
| Device: calls | Calls between the two emulators end with "Couldn't connect" | No TURN relay yet on the live server (deploy blocked, see `2026-10-09-deploy-blocked-ssh-rule.md`); two emulators behind the same NAT can't connect directly | Open until the relay is deployed; the failure is shown and logged, as designed |
