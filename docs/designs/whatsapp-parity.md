# WhatsApp parity without the phone number

Status: implemented (2026-10-09). Quick review; decisions are final.

**Problem.** WhatsApp is tied to your phone number. Everyone who ever saved
it can find you, add you to groups and watch your status, so the app fills
up with people you never chose. Whispr keeps what people like about
WhatsApp and replaces the number with a code you share on purpose.

## Bugs found in review

| Report | Cause | Fix |
|---|---|---|
| Profile photo isn't saved | Every upload went to the same `avatar.jpg`, so the stored path never changed and the screen kept the old (or no) image; a bad image crashed the import | A new file per upload (old ones removed), errors shown |
| Groups vanish after leaving | The chat list query dropped groups with status `Left` | Left and removed groups stay, read-only, with "You're no longer a participant in this group" |
| Can't change a group's name or photo | Only admins could | Any member can (below) |

## Decisions

| Feature | Decision | Why |
|---|---|---|
| Share your code over the internet | My code → Share: the QR image plus a `whispr://add?c=…` link that opens Whispr's add screen; Add contact also takes a pasted code | People meet online; the code is only useful to add you as a request, and verification stays in person (safety numbers) |
| Chats survive reinstalling | Encrypted chat backup to `Downloads/Whispr/` (survives uninstall): turned on in Settings, which shows a 64-character recovery key once; daily automatic backup plus "Back up now"; "Restore from backup" during onboarding brings back the account, chats and media. Deleting the account deletes the backups' value (the server account is gone) | Android wipes app data on uninstall and our keys can't leave the Keystore, so a user-held key is the only way; this is how Signal does it |
| Read receipts | On by default (still reciprocal and switchable) | WhatsApp default; users expect ticks |
| Status views and likes | Viewing sends a `status_seen` to the author, only while read receipts are on (reciprocal, like WhatsApp); a heart sends `status_like`. Your own status shows a view count and who viewed and liked | Requested; mirrors read receipts so the privacy rule stays simple |
| Profile photos | Your photo goes end to end encrypted to your accepted contacts (`profile` payload, JPEG ≤ 32 KB) when you change it and when someone becomes your contact; shown in chats, the chat list, statuses and calls | Contacts see who they talk to; the server still never sees the photo |
| Contact profile | Tap the name in a chat: photo, name, safety number, disappearing timer, and every photo and file in the chat, each with Save | WhatsApp parity |
| Saving files | Save on any received or sent attachment, to `Downloads/Whispr/` by default or a folder picked in Settings | Requested |
| Private by default: connection requests | Scanning a code, opening a shared link or finding a username sends only a `contact_request`. The requester sees "Request sent" and can't message, call or see status until the other person accepts; accepting sends `contact_accept`. Messages, calls and statuses from anyone not connected are dropped on the receiving phone. Two people who request each other are connected at once | The core promise: nobody reaches you unless you said yes, unlike a phone number anyone can save |
| Group name and photo | Any current member can change them (`group_info`, pairwise to every member like `group_leave`; newest change wins, ties ignored). Admins' group updates carry the name's own timestamp, so a later admin change never reverts a newer rename; admins still control membership | WhatsApp default |

Not changed: no phone numbers, no contact upload, no discovery by name.

## Build notes

- Schema v8 (auto-migration): `contacts.awaitingAccept`, `contacts.avatar`,
  `groups.infoTs`, `statuses.liked`, new `status_views`.
- New payloads: `contact_accept`, `profile`, `group_info` (resent after a
  session reset), `status_seen`, `status_like` (control, never resent).
  Older apps drop them, so a beta.2 phone can still message a beta.3 phone
  only after it was accepted the old way.
- A stranger's first PreKey message pins their key but leaves them hidden;
  only their `contact_request` lists them as a request.
- Sending checks the connection in the repository, not only in the UI (the
  first version of the test found the UI was the only guard).
- Backup file: `WHSPBK01` + salt + AES-GCM chunks around a zip of the
  identity key, database key, database and WAL (copied inside a
  transaction), attachments and avatar. Restore stages everything, writes
  the keys, and the next start swaps the database in before anything opens it.
