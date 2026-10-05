# LiveGroupsTest hung for more than 10 minutes

- **What failed:** the live test run didn't finish within 600 s. The server log showed no requests after the media step.
- **Cause:** a bug in the test. It called `runBlocking { repo.observeMessages(...).first() }` inside `engine.transaction { }`. The in-memory Room database has one connection, so the read waited for the transaction, which was waiting for the read.
- **Fix:** read the message ID before opening the transaction. Stopped the stuck task and the Gradle daemon.
- **Status:** fixed. The test passes in about a minute.
