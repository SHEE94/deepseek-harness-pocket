#!/data/data/com.termux/files/usr/bin/sh
# =============================================================================
# a2-termux-boot-dsh.sh → ~/.termux/boot/start-dsh （Termux:Boot 开机自启入口）
# 前置：安装 Termux:Boot 并打开过一次；对 Termux/Termux:Boot 关闭电池优化。
# =============================================================================
CONTAINER="${DSH_CONTAINER:-ubuntu}"
termux-wake-lock || true
nohup proot-distro login "$CONTAINER" -- bash /usr/local/bin/dsh-serve \
  >/dev/null 2>&1 &
