# Contributing to DSH on Android

Thanks for helping. This project ports the **unmodified** DeepSeek Harness
engine onto non-rooted Android phones. The guiding rule: **adapt the
environment, not the engine**.

## Hard constraints — read before changing anything

These three are physical limits of the platform. Violating them breaks the
port in ways that look like unrelated bugs.

1. **`targetSdk <= 28`.** API 29+ forbids `execve()` from app-private
   directories, which kills node and bash outright. This is also why the APK
   is sideload-only and cannot ship on Google Play.
2. **The application id must be exactly 15 characters** (`com.dsh.android`).
   Termux-prefixed ELF path patches rely on a fixed-length string.
3. **arm64-v8a only.** Landlock file sandboxing is unavailable in the Android
   kernel, so the security model is "Android app sandbox + the DSH approval
   flow" (design doc section 7).

The design document [DSH-Android-移植方案.md](DSH-Android-移植方案.md) is the
source of truth for the architecture. Read the relevant section before
touching anything non-obvious.

## Ground rules

- **Do not patch the DSH engine.** It is consumed unmodified from the npm
  package `@deepseek-ai/dsh`. Platform adaptation belongs in the guest
  environment (proot) or in the shell APK.
- **Do not weaken the three constraints above** to make a build pass. A CI
  gate asserts `targetSdk 28`, the 15-character package id, and arm64-only.
- **Keep the JS parity copies in sync.** `scripts/dev/*.mjs` are JS replicas
  of pure-logic kernels in the Kotlin shell. When you change
  `TarExtractor.kt`, `RescueLadder.kt`, or the SHA256SUMS parser, change the
  corresponding `.mjs` in the same commit and run both suites.
- **Record what you actually verified.** The design doc's progress register
  and the field notes carry an explicit "verification status" column. A row
  only counts as done when the check has really run.

## Before you push

```bash
# Kotlin/shell logic parity + tar parser parity
node scripts/dev/tar-gnu-branches-test.mjs
node scripts/dev/tar-parser-parity.mjs
node scripts/dev/shell-logic-parity.test.mjs
node scripts/dev/diag-logic-parity.test.mjs
node scripts/dev/mobile-comfort-behavior.test.mjs
node scripts/dev/wait-page-behavior.test.mjs
```

`bash -n` every shell script you touch. The CI `tooling` job runs the same
suites plus a `diff -r` comparison against the system `tar`, and the
`appliance` job boots a real container and asserts `--dump-config` still
activates `dsh-mobile-comfort` — that assertion is what catches upstream
breaking changes.

## Building the appliance without Docker

The CI path uses `scripts/Dockerfile.appliance`. For a Docker-free host
(Windows included), use the concatenation builder:

```bash
bash scripts/b0-fetch-assets.sh        # fetch upstream tarballs (sha256-verified)
python scripts/b1b-build-rootfs-win.py emu-assets dist
```

`b0-fetch-assets.sh` is idempotent and verifies every download against the
upstream published manifests. The asset directory is gitignored on purpose:
it holds fetched binaries, not source.

## Privacy rules for anything you add

This repository is public. The maintainers' field notes are included because
the technical findings are valuable, but **identifying details are not**:

- **No real device identifiers.** Do not commit adb guids, serial numbers,
  IMEIs, Android IDs, or MAC addresses. Refer to test devices generically
  ("test tablet A", "test phone B"), as the field notes already do.
- **No personal network details.** No LAN IPs, DHCP ranges, router models, or
  WiFi SSIDs.
- **No machine-specific absolute paths.** Use `$REPO`-relative paths or
  environment variables (`DSH_ASSETS_DIR`, `DSH_PYTHON`), never a hardcoded
  path into a personal user profile or a local SDK install.
- **No secrets, ever.** API keys, engine tokens, signing keys, and keystores
  are never committed. `*.jks`, `*.keystore`, and `local.properties` are
  gitignored.
- **No raw device captures.** Screenshots, logcat dumps, and diagnostic zips
  live in the gitignored `emu-assets/` directory. Only deliberately
  sanitized evidence images belong in `docs/images/`.
- **Publish app imagery only.** The repository carries the app's own visuals:
  the icon and wordmark (`brand/icons`, `brand/logo`), the brand sheet
  (`brand/preview/brand-sheet.png`), the running app
  (`brand/preview/device-run.png`), and the app UI evidence in `docs/images/`.
  Launcher home screens, system settings pages, and app-info pages do **not**
  belong in the repository: they carry device attribution (location, WiFi
  name, OEM apps, account nickname) and add nothing to the project.
- **Diagnostic exports identify the device.** The zip produced by
  `DiagnosticsExporter` contains the device model, OS version, and engine
  logs. Scrub it before attaching it to a public issue.

## Commit messages

Plain, imperative subject lines. Describe what changed and why, not what you
personally did. Reference the relevant design-doc section when the change
touches an architectural decision.

## Reporting bugs

Open an issue with:

- the failing item ID from [docs/device-acceptance.md](docs/device-acceptance.md)
  (A/S/K/U series),
- your device class, Android version, and ABI,
- a scrubbed diagnostic zip (see the privacy rules above),
- the exact steps to reproduce.

Field observations from real devices are the most valuable input to this
project. The [device field notes](docs/device-field-notes.md) grew entirely
from that kind of report.
