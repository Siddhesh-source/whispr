# Two JVM tests failed intermittently

- **What failed:**
  - `GroupMessagingTest.leavingRotatesKeysAndTheLeaverReadsNothingAfter`: `timed out waiting for: keys published`. Seen twice: once in the first full run, and again in run 2 of 3 during the follow-up check.
  - `ContactTrustTest.keyChangeIsFlaggedBlocksSendingAndNeedsAcknowledgement`: assertion at line 203 (`sendText` returned false after the user acknowledged the key change). Seen once.

## Root causes

**1. Product bug: a failed key upload was never retried while connected.**
- `MessagingEngine.upkeep` called `maintainer.maintain()` once per connection.
- `maintain()` returns `false` on failure instead of throwing, so after one failed upload nothing retried until the socket reconnected.
- A device that stayed connected kept no published prekeys, so nobody could start a chat with it.
- The test fake made the failure likely: `FakeKeyServer` used an unsynchronized `HashMap` and `ArrayDeque` while three devices' HTTP threads uploaded at once.

**2. Test race (pre-existing; the baseline has the same test).**
- The fake server reported the new key from `/v1/users` but kept serving bundles under the old key.
- When the contact request's bundle fetch landed after the user's acknowledgement, the engine correctly flagged the key change again.

## Fixes

- **Engine:** `upkeep` retries `maintain()` every `parkRetryMs` (30 s in production) until the keys are on the server, then returns to the 5-minute tick. Exceptions count as a failure to retry. Settings keeps showing "keys not set up, retrying" meanwhile, so the failure is never silent.
- **New `KeyUploadRetryTest`:** the first upload fails with 503 and the keys must still reach the server on the same connection.
  - It fails on the previous engine (`timed out waiting for: keys uploaded on the same connection`) and passes on the fixed one.
- **`FakeKeyServer`:** made thread-safe, as the real server is.
- **`ContactTrustTest`:** lets the contact request go out before simulating the key change.

## Verification

- The full data suite with the live server ran 3 times, then twice more with the e2e marker. 124 tests ran each time, with 0 failures.

**Status:** fixed.
