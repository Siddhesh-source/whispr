# Scripted edits dropped backslash escapes

**Date:** 2026-10-08
**Status:** fixed

## What failed

Several source edits were applied with inline Python run from a bash
heredoc. Backslash escapes did not survive the trip:

- `AuthMessages.kt`: `"whispr-delete-v1\u0000"` was written as
  `"whispr-delete-v1u0000"` (compile error `Unresolved reference
  'DELETE_LABEL'` on the first attempt, then a wrong label).
- `app/build.gradle.kts`: `"\n - "` became a literal line break inside a
  string.
- Earlier, `sed` turned the Room query's `ESCAPE '\'` into an invalid one.
- Some heredocs failed outright: `unexpected EOF while looking for matching '`.

## Cause

The command hook that rewrites shell commands, and bash quoting, interpret
backslashes in heredoc bodies before Python sees them.

## What was done

Each broken line was found (by the compiler, the delete-message test
vector, or reading the diff) and fixed with a direct file edit. Larger
scripted edits are now written to a file first and then run, which keeps
the text byte-exact. The delete-message vector test (`AuthMessagesTest`)
pins the label, so a wrong label cannot pass.
