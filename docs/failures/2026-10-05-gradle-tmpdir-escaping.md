# Gradle failed to start: tmpdir path lost its backslashes

- **What failed:**
  - Command: `./gradlew :data:compileDebugKotlin` with `JAVA_OPTS=-Djava.io.tmpdir=D:\\tmp\\java`.
  - Error: `java.io.tmpdir is set to a directory that doesn't exist: D:tmpjava`.
- **Cause:** Git Bash removed the backslashes.
- **Fix:** stopped the Gradle daemons and used forward slashes: `-Djava.io.tmpdir=D:/tmp/java`, `TMP=D:/tmp`, `TEMP=D:/tmp`. Every later build used these, so build temp files stay on D:.
- **Status:** fixed.
