# Compile, lint, test and tooling failures while implementing (all fixed)

Each was found by the build, a test or the shell, and fixed before committing.

| Where | Failure | Fix |
|---|---|---|
| `data` `Attachments.kt` | `Incorrect character literal`: a shell edit turned `'\\'` into `'\'` | constant `BACKSLASH = '\\'` |
| `data` `MediaApi.kt` | `Return type mismatch: ApiResult<Any>` | rebuild `HttpError` / `NetworkError` explicitly |
| `data` tests `ResetRecoveryTest.kt:74` | `IncomingPipeline` constructor gained a `GroupManager` | pass `GroupManager(bob.db, bob.crypto, clock)` |
| `data` `WireFormatTest` | asserted type `0x03` is invalid; it is now the sender-key type | asserts `0x03` accepted, `0x04` rejected |
| `data` `GroupMessagingTest` | waited for 2 Bob→Carol envelopes where only 1 exists | waits for Carol's ack of the forged update |
| `app` `ChatScreen.kt` | suspend `decode` called outside a coroutine | `produceState` |
| `app` | `ChatsScreen` used the now-nullable `peer` | group-aware rows |
| `app` `GroupsUiTest` | 6 failures: queried text inside merged bubble semantics, unconsumed Turbine events, off-screen lazy items | content-description queries, `cancelAndIgnoreRemainingEvents`, `performScrollToNode` |
| `app` | ktlint: unused import, wrapping; an unresolved plural after ktlint rewrapped the line | `ktlintFormat`; manual fix |
| `app` lint | `PluralsCandidate` on three new count strings | converted to `<plurals>` (the remaining warning is a pre-existing string) |
| `server` `attachments_test.go` | `errorlint`: compared an error with `!=` | `errors.Is` |
| `server` | `gofmt` flagged `protocol.go` | `gofmt -w` |
| shell | heredocs and `sed` expressions failed on quoting several times (`unexpected EOF while looking for matching`, `unknown option to s`); one `perl` replacement left literal `\n` in README.md | rewrote those files with the editor tool and fixed the README by hand |
