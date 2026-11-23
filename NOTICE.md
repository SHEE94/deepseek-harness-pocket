# NOTICE — third-party components

This repository ships source and build recipes, not vendored upstream binaries.
The runtime artifacts it produces (`dsh-appliance-*`, `proot-bundle.tar.gz`,
`dsh-android-*.apk`) embed or depend on the components below. Their licenses
apply to those components; this repository's own MIT license covers only the
files in this repository that are not listed here.

## DSH engine

- **@deepseek-ai/dsh** — MIT — <https://github.com/deepseek-ai/deepseek-harness>
- The engine is consumed **unmodified** from the official npm package. The
  Android port does not patch the engine; platform adaptation happens in the
  guest environment (proot) and the shell APK.
- Bundled plugins (for example `@deepseek-ai/dsh-base`) carry their own
  licenses, as published on npm.

## proot runtime (supplied via the Termux official apt repository)

- **proot** — GPL-2.0-or-later — <https://proot-me.github.io/>
- **libtalloc** — LGPL-3.0-or-later — <https://talloc.samba.org/>
  - A prebuilt `libtalloc.so.2` is vendored at
    `android-shell/app/src/main/jniLibs/arm64-v8a/` because the proot runtime
    needs it at exec time and the device has no package manager. Under
    LGPL-3.0 you remain free to replace it with your own build; the source is
    at <https://talloc.samba.org/> and the exact build is reproducible from
    the Termux package referenced by `scripts/b3-fetch-proot.sh`.
- **libandroid-shmem** — BSD-3-Clause — <https://github.com/termux/libandroid-shmem>
- **proot-distro / Termux packages** — see the upstream Termux repository for
  per-package terms: <https://github.com/termux/proot-distro>

`scripts/b3-fetch-proot.sh` repackages these from the official Termux apt
index with SHA-256 verification; the recipe and its rationale are documented
in [docs/proot-build-recipe-report.md](docs/proot-build-recipe-report.md).

## Guest Linux distribution

- **Ubuntu base image** (`ubuntu-base-arm64.tar.gz`) — the official Ubuntu
  base tarball from <https://cdimage.ubuntu.com/ubuntu-base/releases/>.
- **Node.js** (`node-v24-*-linux-arm64.tar.xz`) — MIT and bundled third-party
  licenses — <https://nodejs.org/>

## Mobile UX plugin

- **mobile-comfort/** — MIT (this repository) — a plugin that adapts the
  official DSH Web GUI for touch input. It contains no upstream DSH code.

## Architecture precedent

- Design reference: <https://github.com/kai6666666/deepseek-harness> (MIT).
  No code was copied from it; the approach to running the unmodified engine
  under a proot guest on Android was studied independently.

## Trademarks

"DeepSeek" and related marks are the property of their respective owners.
This project is an unofficial community port and is not endorsed by or
affiliated with DeepSeek.
