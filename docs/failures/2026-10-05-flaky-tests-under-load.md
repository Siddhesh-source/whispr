# Two JVM tests failed once while the live tests ran in the same build

- **What failed:** both in the full `:data:testDebugUnitTest` run that also had `WHISPR_SERVER_URL` set:
  - `ContactTrustTest.keyChangeIsFlaggedBlocksSendingAndNeedsAcknowledgement` (assertion at line 203);
  - `GroupMessagingTest.leavingRotatesKeysAndTheLeaverReadsNothingAfter` (`timed out waiting for: keys published`).
- **Investigation:**
  - Re-ran both classes 3 times on their own: 12/12 and 8/8 passed each time.
  - `ContactTrustTest` also passes on the baseline worktree (2/2 runs).
  - Likely cause of the first failure: the outbox's bundle fetch can race the acknowledged key change in that test, and it re-flags the change.
  - Likely cause of the second: CPU contention from three live engines plus three relay engines.
- **Status:** open (flaky, not reproduced in isolation). If it recurs:
  - `ContactTrustTest` should wait for the outbox to drain before acknowledging;
  - `GroupMessagingTest` could use a longer key-publish timeout.
