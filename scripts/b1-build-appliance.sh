#!/usr/bin/env bash
# =============================================================================
# b1-build-appliance.sh — 在 PC/CI（WSL2 / Linux / macOS，需 Docker + buildx）
# 构建 linux/arm64 的 DSH appliance，输出：
#   dist/dsh-appliance-<VER>.oci.tar      （OCI image layout → proot-distro install）
#   dist/dsh-appliance-<VER>-rootfs.tar.gz（裸 rootfs 备选 → proot-distro install 亦支持）
#   dist/SHA256SUMS.txt
#
# 用法：
#   DSH_VERSION=0.2.2-rc.2 USE_CN_MIRROR=1 bash scripts/b1-build-appliance.sh
# 国内构建机建议 USE_CN_MIRROR=1；推送到 ghcr 供手机拉取：
#   docker buildx imagetools / 或 skopeo copy oci:dist/….oci.tar ghcr.io/<you>/dsh-appliance:<VER>
# =============================================================================
set -euo pipefail
SDIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SDIR")"
OUT="$ROOT/dist"
VER="${APPLIANCE_VERSION:-$(date +%Y%m%d)-dsh${DSH_VERSION:-latest}}"
mkdir -p "$OUT"
IMG="dsh-appliance:${VER}"

docker buildx build \
  --platform linux/arm64 \
  -f "$SDIR/Dockerfile.appliance" \
  --build-arg "NODE_VERSION=${NODE_VERSION:-24.9.0}" \
  --build-arg "DSH_VERSION=${DSH_VERSION:-latest}" \
  --build-arg "USE_CN_MIRROR=${USE_CN_MIRROR:-0}" \
  -t "$IMG" \
  --load \
  "$ROOT" 2>/dev/null \
|| docker buildx build \
  --platform linux/arm64 \
  -f "$SDIR/Dockerfile.appliance" \
  --build-arg "NODE_VERSION=${NODE_VERSION:-24.9.0}" \
  --build-arg "DSH_VERSION=${DSH_VERSION:-latest}" \
  --build-arg "USE_CN_MIRROR=${USE_CN_MIRROR:-0}" \
  -t "$IMG" \
  "$ROOT"

echo "[b1] export OCI layout → dist/dsh-appliance-$VER.oci.tar"
docker buildx build \
  --platform linux/arm64 \
  -f "$SDIR/Dockerfile.appliance" \
  --build-arg "NODE_VERSION=${NODE_VERSION:-24.9.0}" \
  --build-arg "DSH_VERSION=${DSH_VERSION:-latest}" \
  --build-arg "USE_CN_MIRROR=${USE_CN_MIRROR:-0}" \
  --output "type=oci,dest=$OUT/dsh-appliance-$VER.oci.tar" \
  "$ROOT"

echo "[b1] export plain rootfs tar.gz → dist/dsh-appliance-$VER-rootfs.tar.gz"
docker buildx build \
  --platform linux/arm64 \
  -f "$SDIR/Dockerfile.appliance" \
  --build-arg "NODE_VERSION=${NODE_VERSION:-24.9.0}" \
  --build-arg "DSH_VERSION=${DSH_VERSION:-latest}" \
  --build-arg "USE_CN_MIRROR=${USE_CN_MIRROR:-0}" \
  --output "type=tar,dest=$OUT/dsh-appliance-$VER-rootfs.tar" \
  "$ROOT"
gzip -f "$OUT/dsh-appliance-$VER-rootfs.tar"

( cd "$OUT" && sha256sum "dsh-appliance-$VER.oci.tar" "dsh-appliance-$VER-rootfs.tar.gz" > SHA256SUMS.txt )

cat <<EOF

[b1] 完成。下一步（手机侧，任选其一）：
  A) 起一个 HTTPS 静态源（或 python3 -m http.server + 内网穿透/scp），然后：
       proot-distro install https://<host>/dsh-appliance-$VER.oci.tar --name dsh
       proot-distro login dsh -- /usr/local/bin/dsh-serve
  B) 推送到 ghcr.io（skopeo copy oci:dist/dsh-appliance-$VER.oci.tar docker://ghcr.io/<you>/dsh-appliance:$VER）
     手机端 export PD_DOCKER_AUTH=<user>:<PAT>; proot-distro install ghcr.io/<you>/dsh-appliance:$VER
  启动后 Chrome 打开日志里的 dsh web token URL（/root/logs/dsh-web.log 亦可回查）。
EOF
