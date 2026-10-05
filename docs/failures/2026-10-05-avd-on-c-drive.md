# Emulator ran from C: in an earlier session

- **What failed:** an earlier session started the Android emulator from `C:\Users\LENOVO\.android\avd` (a 513 MB copy) while C: had about 2.5 GB free.
- **Cause:** `ANDROID_AVD_HOME` was not set at user level. The AVDs in `D:\avd-home` were found only when the variable was set in that one shell.
- **Fix:**
  - Set the user environment variable `ANDROID_AVD_HOME=D:\avd-home`.
  - Recorded the rule in Claude memory (`emulator-on-d`).
  - This session's emulator run wrote only to `D:\avd-home\Medium_Phone.avd`; checked by file timestamps, and the C: copy was untouched.
- **Status:** fixed. The stale C: copy can be deleted to free space; it was left alone because nobody asked for that.
