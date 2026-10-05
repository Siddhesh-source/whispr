# LiveMessagingTest failed before this work started

- **What failed:** against the real server (`WHISPR_SERVER_URL=http://127.0.0.1:8080/`), two tests timed out:
  - `twoDevicesChatAndOfflineDeliveryIsExactlyOnceInOrder`: `timed out waiting for: all accepted by server`;
  - `scanAddsBothSidesAndSafetyNumbersVerify`: `timed out waiting for: alice got request`.
- **Pre-existing:** both fail the same way on an untouched worktree of `79937a4`.
- **Cause:** server logs show `GET /v1/keys/{id}` returning 404 `no_keys`.
  - In the first test, Bob never came online, so he never published prekeys. Under end-to-end encryption nobody can encrypt to such an account.
  - In the second, the peer published a moment after being asked. The lane then parks for the default `noKeysRetryMs` (5 minutes), well past the 20 s test timeout.
- **Fix** (`test(android): run groups and media against the real server`):
  - Bob goes online once to publish keys, then goes offline before the burst.
  - The live devices use 500 ms lane retries.
- **Status:** fixed. All live tests pass: `LiveMessagingTest` 2 passed and 1 skipped, `LiveGroupsTest` 1/1, `LiveServerTest` 1/1. The production 5-minute retry is unchanged.
