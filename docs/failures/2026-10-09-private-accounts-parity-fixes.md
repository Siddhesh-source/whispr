# Fixes while building private accounts, profiles and backups

**Date:** 2026-10-09 · **Status:** fixed

## Bugs reported by the user (product)

| Report | Cause | Fix |
|---|---|---|
| Profile photo not saved | Every upload overwrote `avatar.jpg`, so the stored path never changed and screens kept the old (or no) image; an unreadable image crashed the import | New file per upload, old ones removed; unreadable images show "That picture couldn't be read" |
| Groups vanish after leaving | The chat list query dropped groups with status `Left` | Kept, read-only, with "You're no longer a participant in this group" |

## Found by the new tests

```
PrivateContactsTest > nothingButTheRequestFlowsUntilItIsAccepted FAILED
    java.lang.AssertionError at PrivateContactsTest.kt:63
```

`RoomMessagingRepository.sendText` didn't check that the contact had
accepted us: only the chat screen (which hides the composer) stopped a
requester from writing. Fixed in the repository for text and reactions
(media already went through the target check).

```
EncryptedMessagingTest > aPeerWithoutKeysDoesNotBlockOtherChats FAILED
    java.util.NoSuchElementException: List is empty.
```

The first `ContactLink.connected` also required a pinned key, which
refused messages to a contact whose key is fetched on first send. Moved the
key check to `trusted` (calls and statuses), where it belongs.

## Tests that encoded the old behaviour (updated)

- Strangers' messages used to become a message request; now they are
  dropped and only a `contact_request` lists them (`ContactTrustTest`,
  `SessionCryptoTest`).
- Read receipts used to default to off (`MessagingEngineTest`,
  `ViewModelsTest`); viewing a status used to send nothing
  (`StatusAndCallsTest`, now with receipts off).
- Only admins could rename a group (`GroupMessagingTest`, `GroupsUiTest`).
- Adding someone by code left them connected at once (`ContactTrustTest`
  now marks the acceptance).

## CI: e2e live tests (after pushing)

```
LiveMessagingTest > twoDevicesChatAndOfflineDeliveryIsExactlyOnceInOrder FAILED
LiveMessagingTest > scanAddsBothSidesAndSafetyNumbersVerify FAILED
LiveMessagingTest > markerTravelsAndRestsOnlyAsCiphertext FAILED
```

The live tests added contacts one way and sent at once, which private
accounts now refuse. Process miss: they were skipped locally because the
server had not changed, but they drive the client's contact flow. Fixed with
a `connect` helper (request, accept, wait for the acceptance) and by ignoring
the "accepted your request" notice in status checks; all 5 live tests pass
locally against `docker compose` before pushing again.

## Tooling

- A trailing lambda in two tests bound to the new `transaction` parameter of
  `RoomContactsRepository` (`Argument type mismatch: actual type is
  '() -> Unit', but 'UserId' was expected`); the parameters were reordered.
- A test returning `runBlocking { ...; dir.deleteRecursively() }` was a
  non-void JUnit method (`initializationError`).
- Shell heredocs again broke on quotes in Kotlin source; edits went through
  Python scripts written with the editor instead.

Local CI before pushing: ktlint, lint, design tokens, 172 data and app unit
tests, domain tests, debug build, and the instrumented tests on an emulator.
