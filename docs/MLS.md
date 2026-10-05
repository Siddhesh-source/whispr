# MLS: a possible future for groups

Status: note only. Nothing here is built or scheduled.

## What we have now

Groups use libsignal sender keys (`docs/designs/groups-and-media.md`):
- Each member has a symmetric chain per group, sent to the others over
  pairwise Signal sessions.
- Messages are encrypted once and fanned out by the server.
- Membership is replicated by admins over pairwise sessions.
- Every remaining member rotates their chain when someone is removed or
  leaves.

This works well for the groups Whispr targets: small, up to 100 members.
It has known limits:

- **No post-compromise security.** If a device's sender key leaks, the
  attacker reads that sender's messages until the next rotation, and we rotate
  only on removal or leave.
- **Rotation cost.** A removal costs every remaining member one pairwise
  message to every other member: O(n²) envelopes per removal.
- **Membership is not agreed cryptographically.** Clients converge through
  revision numbers and tombstones, but nothing proves that all members share
  the same view.

## What MLS (RFC 9420) would change

Messaging Layer Security gives a group one shared, evolving secret built on a
ratchet tree:
- **Post-compromise security** for the whole group after every commit.
- **O(log n)** cost for adds, removes and key updates.
- **An agreed group state.** Every member signs off on the same epoch,
  roster and extensions, so silent divergence can't happen.

## Why we're not doing it now

1. **libsignal has no MLS.** Our rule is libsignal-only protocol code. MLS
   would mean taking on a second audited library (for example OpenMLS) and a
   change to that rule. The project owner has to make that call.
2. **MLS needs an ordering service.** Commits must be ordered per group, which
   the server would have to do. Today it holds no group state at all; the
   delivery service would have to learn group IDs and epochs. That needs its
   own threat-model review.
3. **The extra cost buys little at our group size.** At 100 members or fewer,
   the O(n²) rotation and the lack of post-compromise security are acceptable
   trade-offs that we document.

## What would make us revisit it

- Groups larger than about 100, or frequent membership churn.
- A requirement for post-compromise security in groups.
- libsignal shipping MLS, or an audited MLS library we're willing to adopt.
- Multi-device support. MLS handles multiple devices per user much better
  than per-device sender-key fan-out.

## How a migration could go

- New groups could be created as MLS groups behind a capability flag, while
  sender-key groups keep working.
- The wire format already reserves a type byte per ciphertext kind, so an MLS
  ciphertext would get its own type.
- Group state would move from admin-replicated JSON to MLS
  GroupContext extensions.
