# Composer dropped typed characters

**Date:** 2026-10-09 · **Status:** fixed

**What failed.** On a device (two emulators against the live server),
text typed into the message composer lost characters: "Climbing the ridge
today" arrived as "Clmbin he idgetody", "hello Ada" as "hllo Ad". Found
during the device pass for camera, GIFs, status and calls.

**Cause.** Keyboard GIFs need the composer field to be the state-based
`BasicTextField(TextFieldState)` (only it receives `commitContent`). The
field kept the screen's `value` in step both ways: every edit went up via
`onValueChange`, and a `LaunchedEffect(value)` wrote `value` back when it
differed. The screen's copy lags the typing, so an echo of an earlier
keystroke ("H") arrived while the field already held "Hi" and overwrote it.

**Fix.** `MessageInputBar` records what it emitted; a returning `value`
that matches one of those is recognised as an echo and ignored. Only a
value the field never sent (cleared after sending, a restored draft) is
written into the field. Regression tests: `MessageInputBarTest`
(late echoes, external clear and restore, type then delete). Verified on
the emulator: the same sentence now arrives intact.
