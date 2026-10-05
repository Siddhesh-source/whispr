# GitHub CLI is not installed

- **What failed:** `gh run list` gave `gh: command not found`.
- **Impact:** couldn't check whether the `e2e` workflow had ever passed. The earlier commits were local only and never pushed, so CI never ran them.
- **Workaround:** ran the baseline (HEAD before this work) in a separate git worktree, `D:\tmp\wt-base` (see `2026-10-05-live-messaging-test-preexisting.md`).
- **Status:** open (environment).
