#!/bin/bash
# =============================================================================
# a2-guest-dsh-serve.sh → 容器内 /usr/local/bin/dsh-serve
# DSH Web 监督循环：崩溃自愈 + 日志轮转 + 连续失败退避（救援阶梯）。
# 同时用作 appliance 镜像（路线 B）的 ENTRYPOINT。
# =============================================================================
set -u
# Guest owns its PATH absolutely: when spawned by the Android host, the
# inherited PATH is /system/bin-style (no mkdir/date inside the rootfs).
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/node/bin
# Android appliance marker: open-in-app emits DSH_ANDROID_OPEN:<path> on stdout
# instead of spawning desktop openers (no xdg-open here); the shell pump reads it.
export DSH_ANDROID=1
PORT="${DSH_PORT:-3080}"
HOST="${DSH_HOST:-127.0.0.1}"
WORKDIR="${DSH_WORKDIR:-/root/dsh-workspace}"
LOG_DIR="${DSH_LOG_DIR:-/root/logs}"
LOG="$LOG_DIR/dsh-web.log"
FAIL_LIMIT="${DSH_FAIL_LIMIT:-5}"
mkdir -p "$LOG_DIR" "$WORKDIR"

rotate() { # 简单轮转：>20MB 滚动两代
  if [ -f "$LOG" ] && [ "$(stat -c%s "$LOG" 2>/dev/null || echo 0)" -gt 20971520 ]; then
    mv -f "$LOG.1" "$LOG.2" 2>/dev/null; mv -f "$LOG" "$LOG.1"
  fi
}

fails=0
# ---- 挂载自检（每次启动 6 行进日志；排障第一现场）----
{
  echo "[maptest] $(date -Is) uid=$(id -u):$(id -g)"
  echo "[maptest] projects(/root/projects): $(ls /root/projects 2>&1 | head -1)"
  echo "[maptest] sdcard(/root/sdcard): $(ls /root/sdcard 2>&1 | head -1)"
} >> "$LOG" 2>&1

# ---- 存储同步桥（proot fuse-bind 在 A16 定制系统上静默失效的兜底）----
# /root/projects 与 /root/sdcard 是 rootfs 内真实目录；本后台环每 10s 把
# host 侧（app external / FUSE 共享存储）内容差量搬进 guest，把 guest 新文件
# 搬出到 host。host 侧目录由 Android 壳以 --bind 只读借道？不 —— proot bind
# 失效，所以壳层在 guest 外做同步？不行（host 无 rootfs 内目录权限的逆问题
# 不存在：host 就是文件系统的主人）。方案定型：**host 壳负责双向同步**
# （DshEngineService.startStorageBridge），guest 只读写本地目录。此处仅
# 保留探针与说明；同步在壳层实现，guest 无需任何动作。
cd "$WORKDIR"
while :; do
  rotate
  echo "[$(date -Is)] dsh-serve: starting dsh --profile web --host $HOST --port $PORT (fails=$fails)" >> "$LOG"
  # --no-open：Android 无 xdg-open，跳过自动开浏览器
  # tee: 引擎输出必须同时上 stdout（host pump 靠 "dsh web:" 行提取 token URL
  # 写 URL_FILE，等待页据此切 WebView）与 guest 日志。PIPESTATUS 保 rc 语义。
  dsh --profile web --no-open --host "$HOST" --port "$PORT" 2>&1 | tee -a "$LOG"
  rc=${PIPESTATUS[0]}
  echo "[$(date -Is)] dsh-serve: engine exited rc=$rc" >> "$LOG"
  [ "$rc" -eq 0 ] && { echo "[$(date -Is)] clean exit, stop supervision."; break; }
  fails=$((fails + 1))
  if [ "$fails" -ge "$FAIL_LIMIT" ]; then
    # 救援阶梯：连续失败 → 以 60s 间隔的“安全重试”，并留下提示。
    # 常见病因：坏插件（可临时挂载 DSH_PROFILE_DIR 外置或用干净 profile）、配置错误、端口占用。
    echo "[$(date -Is)] RESCUE-LADDER: $fails consecutive failures; slow retry every 60s." >> "$LOG"
    sleep 60; fails=$((FAIL_LIMIT - 1))
  else
    sleep 5
  fi
done
