# Compile and test fixes while adding message actions and hardening

**Date:** 2026-10-08
**Status:** fixed

Failures hit while building this change, all fixed before commit:

- **Room DAO overloads:** `CryptoDao.setting(String)` existed for key/value
  settings; the new per-conversation `setting(conversationId)` clashed
  (`Conflicting overloads` in generated `CryptoDao_Impl`). Renamed to
  `conversationSetting` / `putConversationSetting`.
- **Test fakes:** `FakeAuth`, `FakeMessaging`, `FakeSettings` (app) and
  `FakeAuthRepository` (domain) did not implement the new interface members.
- **`MessageNotifier.run`:** `coroutineScope { … collect }` needed an
  explicit `Unit` return type.
- **`TlsPolicyTest`:** expected `SSLPeerUnverifiedException`, but OkHttp
  retries the next address (`::1`) and reports `ConnectException` with the
  pin failure suppressed. The test now looks through causes and suppressed
  exceptions.
- **`GroupsUiTest.longPressOpensTheReactionPicker`:** long press now opens
  the action sheet; the test taps "React" first.
- **`MessageActionsUiTest`:** two nodes matched a substring content
  description (bubble and its merged parent); a no-op state change produced
  no new Turbine item; the delete-account row was off screen and needed
  `performScrollTo()`.
- **golangci-lint `ineffassign`** in `revoke_test.go`: an unused token
  assignment.
