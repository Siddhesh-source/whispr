# Group updates after creation were all rejected

- **What failed:** 7 of 14 new group tests, including "bob and carol see the removal", "bob is admin" and `aConcurrentUpdateCannotBringBackARemovedMember`.
- **Cause:** member rows store the role as `GroupRole.Admin.name` ("Admin"), but four checks in `GroupManager` compared them against the wire constant `ADMIN` ("admin"). The sender of every later update therefore failed the "is an admin" check.
- **Fix:** those checks compare with `GroupRole.Admin.name`; the wire format still uses lowercase.
- **Status:** fixed, before any commit. All group tests pass.
