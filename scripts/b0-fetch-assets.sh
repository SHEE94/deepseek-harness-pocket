#!/usr/bin/env bash
# =============================================================================
# b0-fetch-assets.sh — 拉取 b1b 需要的两个上游发行物到资产目录
#
# 用法（在 PC / WSL2 / Linux / macOS 上执行）：
#   bash scripts/b0-fetch-assets.sh
#
# 可选环境变量：
#   DSH_ASSETS_DIR=<dir>   资产目录（默认 <repo>/emu-assets，已在 .gitignore 中）
#   USE_CN_MIRROR=1        默认 1。node 走 npmmirror 镜像（国内网络更快）
#
# 特性：
#   - 幂等：文件已存在且 sha256 匹配即跳过
#   - 强校验：sha256 与上游官方清单逐字比对，不匹配即删并失败
#   - 资产目录不进仓库（.gitignore 已排除 emu-assets/）
#
# 产物（b1b-build-rootfs-win.py 的输入）：
#   ubuntu-base-arm64.tar.gz   Ubuntu 24.04.5 base rootfs
#   node-v24-arm64.tar.xz       Node 24.9.0 官方 linux-arm64 发行物
# =============================================================================
set -euo pipefail

SDIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$SDIR")"
ASSETS="${DSH_ASSETS_DIR:-$REPO/emu-assets}"
USE_CN_MIRROR="${USE_CN_MIRROR:-1}"

log() { printf '\n==> %s\n' "$*"; }
die() { printf '[!] %s\n' "$*" >&2; exit 1; }

# sha256 helper: sha256sum (GNU) or shasum -a 256 (BSD/macOS)
sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    die "neither sha256sum nor shasum found - cannot verify downloads"
  fi
}

# fetch <url> <dest-name> <expected-sha256>
fetch() {
  local url="$1" name="$2" want="$3"
  local dest="$ASSETS/$name"

  if [ -f "$dest" ]; then
    local got
    got="$(sha256_of "$dest")"
    if [ "$got" = "$want" ]; then
      printf '[ok] %s already present and verified\n' "$name"
      return 0
    fi
    printf '[..] %s hash mismatch, re-fetching\n' "$name"
    rm -f "$dest"
  fi

  printf '[..] downloading %s\n' "$name"
  curl -fsSL --retry 3 --retry-delay 2 -o "$dest.part" "$url" \
    || die "download failed: $url"
  mv "$dest.part" "$dest"

  local got
  got="$(sha256_of "$dest")"
  if [ "$got" != "$want" ]; then
    rm -f "$dest"
    die "sha256 mismatch for $name (want $want got $got)"
  fi
  printf '[ok] %s verified\n' "$name"
}

mkdir -p "$ASSETS"

# --- Ubuntu base rootfs -------------------------------------------------------
# 官方清单: https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/SHA256SUMS
UBUNTU_VER="24.04.5"
UBUNTU_SHA="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"
UBUNTU_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-${UBUNTU_VER}-base-arm64.tar.gz"

# --- Node.js ------------------------------------------------------------------
# 官方清单: https://nodejs.org/dist/v24.9.0/SHASUMS256.txt
NODE_VER="24.9.0"
NODE_SHA="5a5b1dc4906e891a655d2f0689db664879724f2d9e63309486fd588172a052bc"
if [ "$USE_CN_MIRROR" = "1" ]; then
  NODE_URL="https://npmmirror.com/mirrors/node/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.xz"
else
  NODE_URL="https://nodejs.org/dist/v${NODE_VER}/node-v${NODE_VER}-linux-arm64.tar.xz"
fi

log "assets dir: $ASSETS"
fetch "$UBUNTU_URL" "ubuntu-base-arm64.tar.gz" "$UBUNTU_SHA"
fetch "$NODE_URL" "node-v24-arm64.tar.xz" "$NODE_SHA"

log "done - now run: python scripts/b1b-build-rootfs-win.py \"$ASSETS\" \"$REPO/dist\""
