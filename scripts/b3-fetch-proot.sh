#!/usr/bin/env bash
# =============================================================================
# b3-fetch-proot.sh — 从 Termux 官方 apt 仓库可验证地获取 proot 运行件
#
# 为什么是「重打包官方 deb」而不是自己编：docs/proot-build-recipe-report.md 的
# NDK 编译配方已备好，但其 F 节列了 8 项未证实（waf cross-answers、-m32、
# 全静态 talloc 等）。发布线要先能确定性出货；编译路径留作后续对比实验。
#
# 供应链：Packages 索引 → Filename + SHA256 逐字取自仓库元数据（https），
# deb 下载后强制 sha256sum -c。产物：
#   out/proot-bundle/
#     proot                     主程序（Termux 构建，unbundled-loader 版）
#     loader                    （若存在）配套 loader
#     lib/libtalloc.so.2        依赖库
#   out/proot-bundle.tar.gz + out/SHA256SUMS-proot.txt
#
# 壳 APK 运行契约（由报告 E 节 enter.c 原文推导，CI 冒烟兜底）：
#   PROOT_LOADER=<bundle>/loader   （env 优先于编译期烤入的 $PREFIX 路径）
#   LD_LIBRARY_PATH=<bundle>/lib
#   PROOT_TMP_DIR=<app 私有可执行目录>
#
# 用法：bash scripts/b3-fetch-proot.sh [outDir]   （原生 arm64 环境才能跑冒烟；
# x86 环境设 SKIP_SMOKE=1 仅取包）
# =============================================================================
set -euo pipefail
OUT="${1:-out}"
REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
DIST="${TERMUX_DIST:-stable}"
ARCH=aarch64
mkdir -p "$OUT/proot-bundle/lib" "$OUT/tmp"

pkget () { # $1=package $2=field(Filename|SHA256)
  awk -v p="$1" -v f="$2" '
    $0 ~ "^Package: "p"$" { inblock=1; next }
    /^$/ { inblock=0 }
    inblock && $0 ~ "^"f": " { v=substr($0, index($0,": ")+2); print v }
  ' "$OUT/Packages" | tail -1
}

grab () { # $1=package $2=destdir-root-marker
  local pkg="$1" root="$2"
  local file sha deb
  file=$(pkget "$pkg" Filename)
  sha=$(pkget "$pkg" SHA256)
  [ -n "$file" ] && [ -n "$sha" ] || { echo "[b3] Packages 索引里找不到 $pkg（仓库/发行配置错误？）" >&2; return 1; }
  deb="$OUT/$(basename "$file")"
  echo "[b3] ↓ $REPO/$file"
  curl -fsSL --retry 3 -o "$deb" "$REPO/$file"
  echo "$sha  $deb" | sha256sum -c - || { echo "[b3] $pkg SHA-256 校验失败" >&2; return 1; }
  mkdir -p "$root"
  local data="$OUT/data-$pkg.tar.xz"
  ar x "$deb" --output="$OUT" && mv -f "$OUT"/data.tar.* "$data"
  tar -xf "$data" -C "$root"
}

step_fetch_proot=1
curl -fsSL --retry 3 -o "$OUT/Packages" "$REPO/dists/$DIST/main/binary-$ARCH/Packages"
grab proot "$OUT/root-proot"
grab libtalloc "$OUT/root-talloc"
# libandroid-shmem：proot 的第二个非系统依赖（--sysvipc 用）。真机 CANNOT LINK
# 逐个暴露的教训 —— 依赖闭包必须完整（见 docs/device-field-notes.md）。
grab libandroid-shmem "$OUT/root-shmem"

PROOT_BIN=$(find "$OUT/root-proot" -type f -path '*/bin/proot' | head -1)
[ -n "$PROOT_BIN" ] || { echo "[b3] deb 里没有 bin/proot？" >&2; exit 1; }
cp -fL "$PROOT_BIN" "$OUT/proot-bundle/proot"
LOADER=$(find "$OUT/root-proot" -type f -path '*libexec/proot/loader' | head -1 || true)
if [ -n "$LOADER" ]; then
  cp -fL "$LOADER" "$OUT/proot-bundle/loader"
  chmod 755 "$OUT/proot-bundle/loader"
  echo "[b3] loader 随包（unbundled 构建）"
fi
SO=$(find "$OUT/root-talloc" -name 'libtalloc.so.2*' | sort | tail -1)
[ -n "$SO" ] || { echo "[b3] 找不到 libtalloc.so.2" >&2; exit 1; }
cp -fL "$SO" "$OUT/proot-bundle/lib/libtalloc.so.2"
# 泛化收库：deb 展开树里所有 *.so*（libtalloc + libandroid-shmem + 未来新增）
find "$OUT/root-talloc" "$OUT/root-shmem" -type f -name '*.so*' -print0 2>/dev/null \
  | while IFS= read -r -d '' f; do cp -fL "$f" "$OUT/proot-bundle/lib/$(basename "$f")"; done
chmod 755 "$OUT/proot-bundle/proot" 2>/dev/null || true

# RUNPATH 补丁：Termux 烤死的 /data/data/com.termux/… 在壳 APK 里是死路；
# bionic 的 LD_LIBRARY_PATH 又不可依赖 → 就地改写 DT_RUNPATH 指向
# <dataDir>/lib（AssetInstaller 落库位置）。b1d 幂等 + 写回校验；再跑一遍
# 必须报 already patched = 磁盘终验。
SDIR="$(cd "$(dirname "$0")" && pwd)"
python3 "$SDIR/b1d-patch-proot-runpath.py" "$OUT/proot-bundle/proot"
python3 "$SDIR/b1d-patch-proot-runpath.py" "$OUT/proot-bundle/proot" | grep -q 'already patched' \
  || { echo "[b3] RUNPATH 终验失败" >&2; exit 1; }
echo "[b3] RUNPATH 已指向 /data/data/com.dsh.android/lib 并终验"

if [ "${SKIP_SMOKE:-0}" != "1" ]; then
  echo "[b3] 冒烟（需原生 arm64）"
  export PROOT_TMP_DIR="$PWD/$OUT/tmp"
  export LD_LIBRARY_PATH="$PWD/$OUT/proot-bundle/lib"
  if [ -f "$PWD/$OUT/proot-bundle/loader" ]; then
    export PROOT_LOADER="$PWD/$OUT/proot-bundle/loader"
  fi
  "$PWD/$OUT/proot-bundle/proot" --version
  echo ok | "$PWD/$OUT/proot-bundle/proot" -0 -r / /bin/cat || {
    echo "[b3] 冒烟失败：proot 在本机 ptrace 环境下不可用？" >&2; exit 1; }
else
  echo "[b3] SKIP_SMOKE=1：跳过运行冒烟（发布线请在 arm64 runner 上真跑）"
fi

tar -czf "$OUT/proot-bundle.tar.gz" -C "$OUT" proot-bundle
( cd "$OUT" && sha256sum proot-bundle.tar.gz > SHA256SUMS-proot.txt )
echo "[b3] 产出：$OUT/proot-bundle.tar.gz"
cat "$OUT/SHA256SUMS-proot.txt"
