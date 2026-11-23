# Security Policy

## Scope

This project ships two things: the **shell APK** (an Android wrapper around
the upstream DSH engine) and **build scripts** that assemble a proot guest
image. The DSH engine itself is the upstream
[`@deepseek-ai/dsh`](https://github.com/deepseek-ai/deepseek-harness) package
and is consumed unmodified — report engine vulnerabilities upstream.

## The security model

The guest runs under **proot** inside the Android app sandbox, as the app's
own UID. It is **not** a real root. The isolation boundary is the Android
application sandbox plus the DSH approval flow, not a Linux security module:

- **Landlock is unavailable** in the Android kernel, so no Landlock-based
  file sandbox is provided. The design relies on the app sandbox and on the
  user's explicit approval prompts inside the DSH Web GUI.
- The guest's root is cosmetic. `proot --change-id=0:0` was removed because
  changing the UID breaks the FUSE view of shared storage, and nothing in the
  guest actually needs real root.

## Known sensitive surfaces

| Surface | Status |
|---|---|
| One-shot engine token in the engine URL | **Redacted** before it can leave the device. `DiagnosticsExporter` writes only origin + path to `url-redacted.txt`; the assertion lives in `docs/device-acceptance.md` item S7 and is enforced by `scripts/dev/diag-logic-parity.test.mjs`. |
| `DEEPSEEK_API_KEY` and model credentials | Configured by the user inside the DSH Web GUI (Settings → Models), or exported in the guest shell. Never bundled, never committed. |
| Diagnostic exports | The zip contains the device model, OS version, engine logs, and process state. It is device-identifying. Scrub it before sharing publicly. |
| Shared storage bridge | Host-to-guest and guest-to-host mtime-based delta sync between the app's external projects directory and the guest tree. |
| `--link2symlink` | Deliberately **not** used: it rewrites guest hard links into temporary symlinks and leaves dangling links behind after a `kill -9`. |

## Reporting a vulnerability

Please report security issues privately rather than in a public issue. Use
GitHub's **Report a vulnerability** button on the Security tab of the
repository (enable private vulnerability reporting in Settings → Security).

Include:

- the affected component (APK / script / bridge / guest image),
- reproduction steps and the acceptance item ID (A/S/K/U series),
- a scrubbed diagnostic zip if one helps.

We aim to acknowledge within a few days and to coordinate disclosure with
you before any public write-up.

## Hardening notes for users

- The APK is **sideload-only** (`targetSdk <= 28`), so it is not distributed
  through Google Play and gets no Play Protect reputation. Only install it
  from this repository's own Releases, and verify the published
  `SHA256SUMS.txt` after downloading.
- Route A (Termux) gives the guest more host access than route B, because
  Termux already owns the shared-storage permissions. Treat it as running an
  arbitrary Linux distribution on the phone.
- `targetSdk <= 28` is required by the platform, but it also means the app
  is not covered by modern Android permission scoping. Grant shared-storage
  access only if you actually need the storage bridge.
