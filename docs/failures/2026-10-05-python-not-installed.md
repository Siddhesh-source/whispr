# Python is not installed

- **What failed:** `python3` is a Microsoft Store stub on this machine (`Python was not found`).
- **Impact:**
  - Two scripted file edits had to be redone with the editor tool.
  - `.github/scripts/no-marker.py` (the CI ciphertext scan) can't run locally.
- **Workaround:**
  - Reproduced the scan by hand: ran `LiveGroupsTest` with a fixed `WHISPR_E2E_MARKER`, mirrored the MinIO bucket (4 objects), and dumped Postgres (9.6 MB).
  - `grep` found the marker in neither, raw or hex.
  - The base64 and UTF-16 variants the script also checks were not checked locally.
- **Status:** open (environment). Install Python 3 to run the CI script locally.
