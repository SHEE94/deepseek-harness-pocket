#!/usr/bin/env bash
# =============================================================================
# install.sh — DSH on Android · 路线 A 一键安装器（Termux 内运行）
#
#   curl -fsSL https://<你的发布地址>/install.sh | bash
#   （谨慎姿势：先 curl -O 存下来看过再 bash，欢迎这么做）
#
# 分层：本文件只做「预检 + 版本化拉取核心脚本 + 校验 + 执行 + 完整性验证」。
# 安装主体逻辑保持住在 a1-termux-bootstrap.sh（离线单文件同样可跑），
# 避免两处逻辑漂移。
#
# 可配置（环境变量）：
#   DSH_ANDROID_BASE        核心脚本发布基址（必配，指向你的 Releases/raw 目录）
#   DSH_ANDROID_VERSION     版本目录名（默认 stable）
#   DSH_ANDROID_SHA256SUMS  可选：覆盖校验文件首选名；默认依次尝试
#                            SHA256SUMS-installer.txt → SHA256SUMS.txt，找到即强制校验
#   USE_CN_MIRROR / CONTAINER / NODE_VERSION / DSH_PKG
#                           透传给 a1（含义见 a1 头部注释）
# 退出码：0 成功；10 预检失败；11 下载/校验失败；12 a1 失败；13 完整性验证失败
# =============================================================================
set -euo pipefail

BASE="${DSH_ANDROID_BASE:-}"
VER="${DSH_ANDROID_VERSION:-stable}"
if [ -n "${DSH_ANDROID_SHA256SUMS:-}" ]; then
  SUMS_CANDIDATES=("$DSH_ANDROID_SHA256SUMS" SHA256SUMS.txt)
else
  SUMS_CANDIDATES=(SHA256SUMS-installer.txt SHA256SUMS.txt)
fi
WORK="${TMPDIR:-${PREFIX:-/tmp}/tmp}/dsh-android-installer"
CORE_FILES=(a1-termux-bootstrap.sh a2-guest-dsh-serve.sh a2-termux-boot-dsh.sh)

green() { printf '\033[32m%s\033[0m\n' "$*"; }
step()  { printf '\n\033[1;36m▶ %s\033[0m\n' "$*"; }
fail()  { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit "${2:-1}"; }

# ─────────────────────────── 1. 预检（先说人话，再报细节）───────────────────────────
step "1/4 预检"
[ -n "${PREFIX:-}" ] || fail "当前不在 Termux 里运行。请先安装 Termux（F-Droid 或 Termux GitHub Releases），把本命令在 Termux 内执行。" 10
case "$(uname -m)" in
  aarch64|arm64) green "  ✓ arm64 设备" ;;
  *) fail "仅支持 arm64-v8a（当前 $(uname -m)）。" 10 ;;
esac

# 磁盘：装机约需 2.5–3 GB（rootfs + node + node_modules），留 4 GB 余量报错。
free_kb=$(df -k "$PREFIX" | awk 'NR==2{print $4}')
if [ "${free_kb:-0}" -lt $((4 * 1024 * 1024)) ]; then
  fail "剩余空间不足：需要 ≥4 GB（当前 $((free_kb / 1024 / 1024)) GB）。清理后重试。" 10
fi
green "  ✓ 磁盘余量 $((free_kb / 1024 / 1024)) GB"

command -v curl >/dev/null 2>&1 || pkg install -y curl >/dev/null 2>&1 || true

# ptrace 探针：proot 的生死门，提前失败好过装到一半。
if ! command -v proot >/dev/null 2>&1; then
  step "安装 proot（用于探针与后续容器）"
  pkg update -y >/dev/null && pkg install -y proot proot-distro >/dev/null
fi
if ! proot -0 -r / /system/bin/echo ok >/dev/null 2>&1; then
  fail "ptrace 被系统限制，proot 无法运行（部分厂商 ROM 会禁）。
  解法：① 确认用的是 F-Droid/GitHub 版 Termux；② 系统设置里给 Termux 关闭省电限制；
  ③ 仍失败 = 该设备走不了 proot 路线，参考方案 §12-R1（Termux 原生路线）。" 10
fi
green "  ✓ ptrace/proot 可用"

# ─────────────────────────── 2. 拉取核心脚本（版本化 + 校验）───────────────────────────
step "2/4 获取核心脚本（$VER）"
if [ -z "$BASE" ]; then
  # 无发布地址时：假定与 install.sh 同目录（GitHub raw 场景常见）
  fail "需设置 DSH_ANDROID_BASE（核心脚本发布基址），例如：
  export DSH_ANDROID_BASE=https://github.com/SHEE94/deepseek-harness-pocket/releases/download/installer-v1
  curl -fsSL \"\$DSH_ANDROID_BASE/install.sh\" | bash
  离线方式：直接把 a1-termux-bootstrap.sh 传到手机执行，install.sh 的预检价值同样适用。" 11
fi
mkdir -p "$WORK"
fetch() { # $1 url $2 out
  curl -fsSL --retry 3 --retry-delay 2 -o "$2.part" "$1" && mv -f "$2.part" "$2"
}
for f in "${CORE_FILES[@]}"; do
  echo "  ↓ $f"
  fetch "$BASE/$f" "$WORK/$f" || fail "下载失败：$BASE/$f（检查网络/代理，或改用国内镜像环境重试）" 11
done

SUMS_FETCHED=""
for cand in "${SUMS_CANDIDATES[@]}"; do
  if fetch "$BASE/$cand" "$WORK/SHA256SUMS.txt" 2>/dev/null; then
    command -v sha256sum >/dev/null 2>&1 || pkg install -y coreutils >/dev/null 2>&1 || true
    res=$( cd "$WORK" && sha256sum -c --ignore-missing "SHA256SUMS.txt" 2>&1 ) \
      || fail "SHA-256 校验失败（$cand）：下载损坏或版本目录不匹配。删掉 $WORK 重试。
$res" 11
    # 防假绿：--ignore-missing 在“sums 条目与本地文件全部对不上”时也返回 0
    # （例如回退时误拉到 appliance 的 sums）。至少一行 OK 才算校验真的发生。
    printf '%s\n' "$res" | grep -q ': OK$' \
      || fail "校验文件 $cand 不含本 installer 的任何条目（发布命名契约被破坏？）；为安全拒绝继续。" 11
    green "  ✓ SHA-256 校验通过（$cand）"
    SUMS_FETCHED="$cand"
    break
  fi
done
if [ -z "$SUMS_FETCHED" ]; then
  printf '\033[33m  ! 未提供 SHA256SUMS-installer.txt / SHA256SUMS.txt，跳过完整性校验（发布方建议随包提供）\033[0m\n'
fi

# ─────────────────────────── 3. 执行核心安装 ───────────────────────────
step "3/4 执行核心安装（容器 + Node + DSH，几分钟）"
cd "$WORK"
# shellcheck disable=SC2086  # USE_CN_MIRROR 等按 a1 头部约定透传
bash a1-termux-bootstrap.sh || fail "核心安装失败（上方有诊断）。修复后可直接重跑本命令，全程幂等。" 12

# ─────────────────────────── 4. 完整性验证 ───────────────────────────
step "4/4 完整性验证"
CONTAINER="${CONTAINER:-ubuntu}"
if ! proot-distro login "$CONTAINER" -- sh -c 'node -v && command -v dsh >/dev/null' ; then
  fail "容器内 node/dsh 缺失：请重跑本安装器（幂等），仍失败看 $WORK 下 a1 的报错原文。" 13
fi
green "  ✓ node + dsh 就位"

cat <<EOF

$(green '✓ 安装完成。')

启动引擎：
    proot-distro login $CONTAINER -- bash /usr/local/bin/dsh-serve
然后用 Chrome 打开终端里打印的「dsh web: http://127.0.0.1:… 」地址，
菜单 → 添加到主屏幕 = 全屏 App 体验。

建议顺手做两件事（否则息屏后可能掉线）：
  1) 系统设置 → 应用 → Termux → 关闭电池优化
  2) 安装 Termux:Boot 并打开一次（已写好开机自启脚本）

API Key：GUI 里 Settings → Models 配置即可。
EOF
