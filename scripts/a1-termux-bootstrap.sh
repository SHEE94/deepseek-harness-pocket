#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# a1-termux-bootstrap.sh — 在 Termux 里一键部署「DSH on Android（路线 A）」
#
# 用法（在 Termux 中执行，勿在其他 shell 中执行）：
#   bash a1-termux-bootstrap.sh
#
# 可选环境变量：
#   USE_CN_MIRROR=1        默认 1。apt/node/npm/ripgrep 全链路换国内镜像（USTC/npmmirror）
#   CONTAINER=ubuntu       proot-distro 容器名（默认 ubuntu）
#   UBUNTU_URL=...         直连 Docker Hub 失败时使用的 ubuntu-base 裸 rootfs URL
#   NODE_VERSION=24.9.0    容器内安装的官方 Node 版本（需 >= 24.9.0）
#   DSH_PKG=@deepseek-ai/dsh@latest   要安装的 DSH npm 包
#   INSTALL_TERMUX_BOOT=1  默认 1。写入 ~/.termux/boot/start-dsh（需已装 Termux:Boot）
#
# 特性：幂等（重复执行安全）、离线容忍（已装步骤自动跳过）
# =============================================================================
set -euo pipefail

USE_CN_MIRROR="${USE_CN_MIRROR:-1}"
CONTAINER="${CONTAINER:-ubuntu}"
NODE_VERSION="${NODE_VERSION:-24.9.0}"
DSH_PKG="${DSH_PKG:-@deepseek-ai/dsh@latest}"
UBUNTU_URL="${UBUNTU_URL:-https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz}"
SDIR="$(cd "$(dirname "$0")" && pwd)"
GUEST_SETUP="$(mktemp "${TMPDIR:-${PREFIX:-/tmp}/tmp}/dsh-guest-setup-XXXX.sh")"
trap 'rm -f "$GUEST_SETUP"' EXIT

log() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m[!] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 0. 预检 ----
case "$(uname -m)" in aarch64|arm64) ;; *) die "本方案仅支持 arm64-v8a（当前 $(uname -m)）";; esac

log "0/5 预检：ptrace/proot 可用性"
if ! command -v proot >/dev/null 2>&1 || ! command -v proot-distro >/dev/null 2>&1; then
  pkg update -y && pkg install -y proot proot-distro wget xz-utils curl
fi
if ! proot -0 -r / /system/bin/echo "proot-ok" >/dev/null 2>&1; then
  die "proot 无法运行（ptrace 被 ROM 限制？）。请换 F-Droid/GitHub 版 Termux 重试，或参考方案 §12-R1 的备选路线。"
fi
echo "proot 正常。"

# ------------------------------------------------- 1. 安装发行版容器 ----------
log "1/5 安装 proot-distro 容器「$CONTAINER」"
if [ -d "${TERMUX__PREFIX:-$PREFIX}/var/lib/proot-distro/containers/$CONTAINER/rootfs" ]; then
  echo "容器已存在，跳过。"
else
  if ! proot-distro install ubuntu:24.04 ; then
    log "Docker Hub 拉取失败 → 回退到 ubuntu-base 裸 rootfs: $UBUNTU_URL"
    proot-distro install "$UBUNTU_URL" --name "$CONTAINER"
  fi
fi

# ------------------------------------------ 2. 生成容器内初始化脚本 -----------
log "2/5 生成并注入容器初始化脚本"
{
cat <<'GUEST_EOF'
#!/bin/bash
# dsh-guest-setup — 在 proot-distro Ubuntu 24.04 arm64 内执行（由 a1 注入）
set -euo pipefail
USE_CN_MIRROR="__CN__"
NODE_VERSION="__NODE__"
DSH_PKG="__DSHPKG__"
export DEBIAN_FRONTEND=noninteractive

if [ "$USE_CN_MIRROR" = "1" ]; then
  echo "[guest] 切换 USTC 源（deb822 ubuntu.sources，容错）"
  if [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
    sed -i -E 's|https?://(archive|ports)\.ubuntu\.com/ubuntu(-ports)?/|https://mirrors.ustc.edu.cn/ubuntu-ports/|g' /etc/apt/sources.list.d/ubuntu.sources || true
  elif [ -f /etc/apt/sources.list ]; then
    sed -i -E 's|https?://(archive|ports)\.ubuntu\.com/ubuntu(-ports)?|http://mirrors.ustc.edu.cn/ubuntu-ports|g' /etc/apt/sources.list || true
  fi
  # DNS 也换阿里（proot-distro 装机时写死了 8.8.8.8）
  printf 'nameserver 223.5.5.5\nnameserver 119.29.29.29\n' > /etc/resolv.conf
fi

echo "[guest] apt 基础工具链（node-pty 编译需要 build-essential/python3；ripgrep 兜底）"
apt-get update
apt-get install -y --no-install-recommends \
  ca-certificates curl xz-utils git build-essential python3 ripgrep tmux procps iproute2 less
git config --system core.fileMode false || true

if ! command -v node >/dev/null 2>&1 || \
   [ "$(node -p 'Number(process.versions.node.split(".")[0])' 2>/dev/null || echo 0)" -lt 24 ]; then
  echo "[guest] 安装官方 Node $NODE_VERSION (linux-arm64, glibc)"
  BASE="https://nodejs.org/dist"
  [ "$USE_CN_MIRROR" = "1" ] && BASE="https://npmmirror.com/mirrors/node"
  curl -fsSL "$BASE/v$NODE_VERSION/node-v$NODE_VERSION-linux-arm64.tar.xz" \
    | tar -xJ -C /usr/local --strip-components=1
fi
node -v && npm -v

if [ "$USE_CN_MIRROR" = "1" ]; then
  npm config set registry https://registry.npmmirror.com
  export npm_config_disturl=https://npmmirror.com/mirrors/node
  # @vscode/ripgrep postinstall 二进制镜像（尽力而为；失败有 apt rg 兜底）
  export npm_config_ripgrep_mirror=https://npmmirror.com/mirrors/ripgrep/
  export RIPGREP_MIRROR=https://npmmirror.com/mirrors/ripgrep/
fi

echo "[guest] npm i -g $DSH_PKG （含 node-pty 本地编译，proot 下约需几分钟）"
npm install -g "$DSH_PKG"

if ! command -v dsh >/dev/null 2>&1; then
  echo "[guest] dsh 安装异常，输出诊断："
  node -e "console.log(process.platform, process.arch, process.versions.node)"
  npm ls -g @deepseek-ai/dsh || true
  echo "[guest] 常见原因：node-pty 编译失败(缺 g++/python3) 或 ripgrep postinstall 网络失败。"
  echo "[guest] 后者的补救：确认 apt 的 rg 在 PATH（已装），然后重新执行 npm install -g $DSH_PKG"
  exit 1
fi
dsh --version 2>/dev/null || echo "[guest] dsh 已安装（--version 不受支持则忽略本行）"

mkdir -p /root/dsh-workspace /root/logs
cat > /root/.bashrc.dsh <<'BASHRC'
export DSH_HOME_NOTE="DSH web 启动：dsh-serve（监督循环）或 dsh --profile web --no-open --port 3080"
BASHRC
grep -q dsh-home /root/.bashrc 2>/dev/null || echo '[ -f /root/.bashrc.dsh ] && . /root/.bashrc.dsh' >> /root/.bashrc

echo "[guest] 完成。dsh $(dsh --version 2>/dev/null || echo '(版本获取失败)') 就绪。"
GUEST_EOF
} | sed -e "s|__CN__|$USE_CN_MIRROR|" -e "s|__NODE__|$NODE_VERSION|" -e "s|__DSHPKG__|$DSH_PKG|" > "$GUEST_SETUP"

proot-distro copy "$GUEST_SETUP" "$CONTAINER:/root/dsh-setup.sh"
[ -f "$SDIR/a2-guest-dsh-serve.sh" ] && proot-distro copy "$SDIR/a2-guest-dsh-serve.sh" "$CONTAINER:/usr/local/bin/dsh-serve"
proot-distro login "$CONTAINER" -- chmod +x /root/dsh-setup.sh /usr/local/bin/dsh-serve 2>/dev/null || true

# ---------------------------------------------- 3. 执行容器初始化 -------------
log "3/5 初始化容器（apt + Node + DSH，可能需要几分钟）"
proot-distro login "$CONTAINER" -- bash /root/dsh-setup.sh

# ---------------------------------------------- 4. Termux:Boot 自启 -----------
log "4/5 配置开机自启（Termux:Boot）"
if [ "${INSTALL_TERMUX_BOOT:-1}" = "1" ]; then
  mkdir -p "$HOME/.termux/boot"
  BOOT_SRC="$SDIR/a2-termux-boot-dsh.sh"
  if [ -f "$BOOT_SRC" ]; then
    install -m 700 "$BOOT_SRC" "$HOME/.termux/boot/start-dsh"
  else
    cat > "$HOME/.termux/boot/start-dsh" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
# 由 a1-termux-bootstrap.sh 生成
termux-wake-lock
nohup proot-distro login $CONTAINER -- bash /usr/local/bin/dsh-serve >/dev/null 2>&1 &
EOF
    chmod 700 "$HOME/.termux/boot/start-dsh"
  fi
  echo "已写入 ~/.termux/boot/start-dsh —— 还需：安装 Termux:Boot 应用、打开它一次、"
  echo "并在系统设置里对 Termux / Termux:Boot 关闭电池优化。"
fi

# --------------------------------------------------- 5. 收尾提示 --------------
log "5/5 完成 — 启动方式"
cat <<EOF

  前台启动（首次建议，能看到 dsh web 打印的 token URL）：
      proot-distro login $CONTAINER -- bash /usr/local/bin/dsh-serve
  或裸启动自定义参数：
      proot-distro login $CONTAINER -- dsh --profile web --no-open --port 3080

  然后：Android Chrome 打开「dsh web: http://127.0.0.1:3080/?…」给出的地址，
  菜单 →「添加到主屏幕」即得全屏 PWA。API Key 在 GUI 的 Settings → Models 配置
  （或启动前 export DEEPSEEK_API_KEY=...）。

  安全提示：本方案下 DSH 文件级沙箱（Landlock）在 Android 内核上不可用，
  引擎将以「应用沙箱内 full-access + 审批流」模式运行，详见《DSH-Android-移植方案.md》§7。
EOF
