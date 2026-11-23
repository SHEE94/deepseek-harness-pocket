# scripts/ — DSH on Android 交付脚本

完整设计见上级目录《[DSH-Android-移植方案.md](../DSH-Android-移植方案.md)》。

| 文件 | 运行位置 | 作用 |
|---|---|---|
| `install.sh` | 手机 Termux（`curl \| bash` 入口） | 一键安装器：预检（arm64 / 磁盘≥4GB / ptrace 探针，失败给人话+出路）→ 版本化拉取 a1/a2（随包 SHA256SUMS 强制校验）→ 核心安装 → 完整性验证。契约退出码 10 预检 / 11 下载校验 / 12 核心 / 13 完整性（CI 有 exit-10 契约测试） |
| `a1-termux-bootstrap.sh` | 手机 Termux | 路线 A 一键装机：proot + proot-distro Ubuntu 24.04 + 官方 Node 24 arm64 + `npm i -g @deepseek-ai/dsh`（node-pty 现场编译）+ Termux:Boot 自启 |
| `a2-guest-dsh-serve.sh` | 容器内（由 a1 注入为 `/usr/local/bin/dsh-serve`） | `dsh --profile web` 监督循环：崩溃自愈、日志轮转、连续失败进入救援节奏；也是 appliance 镜像的 ENTRYPOINT |
| `a2-termux-boot-dsh.sh` | Termux（由 a1 安装为 `~/.termux/boot/start-dsh`） | 开机自启 + wake-lock |
| `Dockerfile.appliance` | PC/CI（Docker） | 构建 linux/arm64「DSH appliance」：引擎与全部原生件（node-pty 编译产物、rg、sharp）预装，手机零编译 |
| `b0-fetch-assets.sh` | PC/WSL2/Linux/macOS | 拉取 b1b 需要的两个上游发行物（Ubuntu 24.04.5 base rootfs + Node 24.9.0 linux-arm64）到资产目录：幂等、sha256 与上游官方清单逐字校验。资产目录 gitignored，不进仓库 |
| `b1-build-appliance.sh` | PC/CI（WSL2/Linux/macOS + buildx） | 产出 `dist/dsh-appliance-<ver>.oci.tar`（OCI，proot-distro 可直接 install）+ 裸 rootfs tar.gz + SHA256SUMS |
| `b2-stage-shell-assets.ps1` | Windows PC + adb | 把 b1 产物推到壳 APK 收件目录（`/sdcard/Android/data/com.dsh.android/files/incoming`），并自动附带 b3 的 `proot-bundle.tar.gz`（可用 `-Proot` 覆盖为单文件），触发 AssetInstaller 解包 |
| `b3-fetch-proot.sh` | PC/CI（arm64 原生才能跑冒烟） | 从 Termux 官方 apt 仓库**可验证重打包** proot 运行件：Packages 索引取 Filename+SHA256 → 强校验 → deb 提取 proot/loader/libtalloc → 原生冒烟（`--version` + ptrace 起 `/bin/cat`）→ `proot-bundle.tar.gz`。选型理由与编译配方见 [docs/proot-build-recipe-report.md](../docs/proot-build-recipe-report.md) |
| `seed-mobile-comfort.mjs` | appliance 构建期 | 防御性兜底激活：实测 `dsh plugin add` 原生双写 `dependencies` + `dsh.profile.bundles`，seed 仅在镜像构建环境 CLI 行为漂移时补位（幂等 + 缺依赖拒激活；本地三路径测试通过） |
| `dev/mobile-comfort-behavior.test.mjs` | 任意 Node | `mobile-comfort/client.js` 的**无浏览器行为测试**（有状态 hook 垫片驱动真实 apply/渲染/点击路径，10 组断言：样式挂载、指针门控、标签/图标/aria、能力探测、字号 API 路由、方向限流、偏好持久化、跨标签收敛、键盘退让、清理）。改插件必跑 |
| `dev/shell-logic-parity.test.mjs` | 任意 Node | 壳三处**纯逻辑内核**的 JS 等价副本测试（tar 双测同模式）：RescueLadder 状态机（升级/单次搁置窗口/HALT 粘性与再推导/restore）、SHA256SUMS 解析（标准/二进制标记/CRLF/路径前缀/大小写/坏行容忍）、findPlan 与 runtime.stamp 匹配（名+哈希双条件否则重解包）。**改对应 Kotlin 必须同步这里** |
| `dev/tar-parity-lib.mjs` + `tar-parser-parity.mjs` + `tar-gnu-branches-test.mjs` | 任意 Node | `android-shell/TarExtractor.kt` 分支逻辑的 JS 等价副本 + 两套测试：真 tar 对照解包（bsdtar）与手写 GNU 长名/pax path 覆盖/硬链接/设备跳过分支单测。改 Kotlin 解析器时同步改这里并跑 `node tar-gnu-branches-test.mjs` |

## 快速开始（路线 A）

```bash
# 手机：F-Droid 安装 Termux（勿用旧商店版）→ 把 a1/a2 两个脚本传到手机 →
bash a1-termux-bootstrap.sh
proot-distro login ubuntu -- bash /usr/local/bin/dsh-serve
# Chrome 打开打印出的 dsh web URL → 添加到主屏幕 = PWA
```

## 构建 appliance（路线 B 资产）

```bash
# Windows 用户在 WSL2（Docker Desktop 后端）里：
DSH_VERSION=latest USE_CN_MIRROR=1 bash scripts/b1-build-appliance.sh
```

无 Docker 路线（Windows 原生 / Linux 均可，走 tar 二进制拼接，零解包保符号链接）：

```bash
# 1) 拉上游发行物（幂等 + sha256 强校验；默认落到 <repo>/emu-assets，可用 DSH_ASSETS_DIR 改）
bash scripts/b0-fetch-assets.sh
# 2) 拼接 rootfs + proot bundle
python scripts/b1b-build-rootfs-win.py emu-assets dist
```

## CI（`.github/workflows/build.yml`）

| Job | Runner | 干什么 | 关键断言 |
|---|---|---|---|
| `appliance` | `ubuntu-24.04-arm` | b1 构建 OCI/rootfs（release 锁版本 + nightly `latest` 金丝雀双通道），并**真实起容器**：等 `dsh web:` URL → curl 2xx/3xx → `--dump-config` 里必须见 `dsh-mobile-comfort` | 上游破坏性变更/激活漂移 = 构建红 |
| `shell-apk` | `ubuntu-latest` | 生成 gradle wrapper → `assembleDebug` → aapt2 校验 **targetSdk 28 / 15 字符包名 / 仅 arm64** 三条硬约束 | 编不过 = 构建红 |
| `tooling` | `ubuntu-latest` | tar 分支单测 + 与系统 tar 的 `diff -r` parity + seed 幂等/拒绝语义 | 解析器漂移 = 构建红 |

> 断言脚本已在 Windows 本地干跑通过（seed 双跑幂等、负路径拒绝、node 断言本体）；YAML 已用运行时 js-yaml 校验。

## 发布（`.github/workflows/release.yml`）

`git tag vX.Y.Z && git push --tags` 即产出完整 Release：appliance OCI/rootfs（+其 `SHA256SUMS.txt`，壳端校验）+ 壳 APK（debug 签名，aapt2 三条硬约束门禁）+ installer 四件套（+专属 `SHA256SUMS-installer.txt`，install.sh 校验，命名与 appliance sums 隔离防资产覆盖）+ proot bundle。发布说明内置一行装机命令：

```bash
export DSH_ANDROID_BASE=https://github.com/SHEE94/deepseek-harness-pocket/releases/download/<tag>
curl -fsSL "$DSH_ANDROID_BASE/install.sh" | bash
```

## 约定

- 全脚本 `set -euo pipefail`，幂等可重跑。
- 国内网络默认镜像：USTC（apt）、npmmirror（node/npm/ripgrep/node-gyp 头文件）、阿里 DNS。
  海外设备传 `USE_CN_MIRROR=0`。
- 仅支持 arm64-v8a；未 root。ptrace 预检失败时脚本会直接给出指引（方案 §12-R1）。
