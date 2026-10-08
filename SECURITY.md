# Security policy

Whispr is a messenger whose whole point is privacy, so security reports are
our highest priority. Thank you for taking the time.

## Reporting a vulnerability

**Please do not open a public issue.** Report privately through GitHub:
**Security → Report a vulnerability** on this repository (a private security
advisory). Include:

- what an attacker can do, and under which conditions;
- steps or a proof of concept (against your own server and accounts);
- the version, commit, or APK SHA-256 you tested.

We will:

| Within | We do this |
|---|---|
| 3 working days | Acknowledge the report |
| 10 working days | Confirm or dispute it, with a severity and a plan |
| 90 days | Release a fix and publish an advisory, crediting you if you wish |

If a fix needs longer we will tell you why and agree a date. If a
vulnerability is being exploited, we move faster and may publish a
mitigation before the full fix.

## Scope

In scope:

- the Android app in `android/` and the server in `server/`;
- our use of libsignal (key handling, session management, payload formats);
- the build and release pipeline (`.github/workflows/`), signing and
  certificate pinning;
- the deployment defaults in `docs/DEPLOYMENT.md`.

Out of scope:

- vulnerabilities in libsignal itself: report those to Signal
  (security@signal.org), and tell us so we can ship the fix;
- attacks needing root, a compromised OS, or an unlocked phone in the
  attacker's hands (see `docs/THREAT_MODEL.md`);
- volumetric denial of service against someone else's deployment;
- findings that `docs/SECURITY_REVIEW.md` or `docs/THREAT_MODEL.md` already
  list as known gaps, unless you show a worse impact than described;
- missing headers or TLS settings on servers we do not operate.

## Safe harbour

We will not pursue or support legal action against good-faith research that
respects these rules: test only against accounts and servers you own or have
permission to test, do not access or keep other people's data, do not
degrade service for others, and give us reasonable time to fix before
disclosure.

## Supported versions

During the beta only the latest release receives security fixes.

## Verifying releases

Each GitHub release lists `SHA256SUMS` for the APK and AAB and the signing
certificate's SHA-256 (`SIGNING-CERT.txt`). Check both before installing:

```sh
sha256sum -c SHA256SUMS
apksigner verify --print-certs whispr-<version>.apk
```
