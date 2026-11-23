# DeepSeek Harness (DSH) 移植 Android 独立系统 · 完整方案

> 目标：让 DSH 在一台未 root 的 Android 手机上作为「独立 AI Agent 系统」长期运行，
> shell 环境用 proot 模拟，界面复用官方 Web GUI，最终形态可以是一个自包含 APK。
>
> 本方案基于对 **DSH 0.2.2-rc.2 实际安装** 的依赖审计（`@deepseek-ai/dsh`，npm 公开发布）、
> 对 [termux/proot-distro](https://github.com/termux/proot-distro) 能力的逐项核对，
> 以及对社区先例 [kai6666666/deepseek-harness（DSH for Android）](https://github.com/kai6666666/deepseek-harness)
> 已验证的两条平台硬约束的分析。所有关键假设都标注了证据来源。

---

## 0. 结论速览（TL;DR）

```
┌─────────────────────────── Android 设备（不 root，arm64-v8a）───────────────────────────┐
│                                                                                          │
│  ┌─ UI 层（三选一，可共存）──────────────┐      ┌─ 引擎层（Node · glibc · linux-arm64）─┐ │
│  │ A. Chrome/PWA（零开发，立即可用）      │      │  dsh --profile web --no-open --port   │ │
│  │ B. 壳 APK：WebView + 前台服务保活      │◄────►│  @deepseek-ai/dsh（npm 原版，零补丁） │ │
│  │ C. gecko 壳：GeckoView（老设备兜底）   │ HTTP │  bash/grep/rg/read/write/PTY 全可用   │ │
│  └───────────────────────────────────────┘ 127 │  loopback 127.0.0.1:3080 + token 认证  │ │
│                                                └───────────────┬────────────────────────┘ │
│  ┌─ 兼容层：proot（ptrace 路径转译，非安全沙箱）────────────────┐ │
│  │ rootfs：Ubuntu 24.04 arm64（glibc 2.39）                     │◄┘
│  │ 默认参数已含 --link2symlink --sysvipc --kill-on-exit -0       │
│  │ 自动写入 /etc/resolv.conf；宿主 UID 注册为 aid_*             │
│  └───────────────────────────────────────────────────────────────┘
│  ┌─ 生命周期：Termux:Boot 自启 + termux-wake-lock +（壳 APK 时）前台服务通知 ─────────┐
│  ┌─ 分发：CI（docker buildx arm64）预构建 appliance OCI 镜像 → 手机一条命令安装 ────────┐
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

- **核心洞察**：DSH 的 CLI/GUI 本来就是「Node 服务器 + Web 前端」两层，且 `@deepseek-ai/dsh`
  以 MIT 协议发布在 npm 上。移植 = 在 Android 上提供一个可信的 linux-arm64 glibc 运行环境，
  然后像 Linux 服务器上那样跑 `dsh --profile web`。proot 恰好就是这个运行环境。
- **两条路线，先 A 后 B**：
  - **路线 A（Day-0 可用）**：Termux + proot-distro Ubuntu + 官方 Node 24 + `npm i @deepseek-ai/dsh`，
    Chrome「添加到主屏幕」即得全屏 PWA。零自研代码，本文附一键脚本。
  - **路线 B（真正的"独立系统"）**：自打包 APK（前台服务 + WebView/GeckoView），
    引擎与 rootfs 做成 CI 预构建的 appliance OCI 镜像或随包资产。先例项目已把这条路的
    所有暗坑踩平（MIT 协议），可 fork 也可按本方案自建。
- **必须接受的事实**（详见 §3）：仅 arm64-v8a；不能上架 Google Play（targetSdk ≤ 28 硬约束，
  见先例项目「两条硬约束」）；Landlock 沙箱在绝大多数 Android 内核上不可用 → 用
  「App 沙箱 = 外层 jails + DSH 审批流」的安全模型替代文件级沙箱；proot 有 15–40% 的
  文件系统开销。

---

## 1. DSH 现状审计（已验证事实）

以下全部来自本机 DSH 安装目录（`<DSH_INSTALL_DIR>/resources/app.asar/dsh/`）的逐包核对与
npm registry 实测（2026-11-13）：

### 1.1 可分发形态

| 事实 | 值 | 证据 |
|---|---|---|
| CLI 包 | `@deepseek-ai/dsh`，**npm 公开**，MIT，bin=`dsh → lib/bin.js` | registry.npmjs.org `/latest` 实测 |
| 运行要求 | **Node ≥ 24.9.0**，纯 ESM | `apps/cli/package.json` `engines` |
| 源码仓库 | `github.com/deepseek-ai/deepseek-harness`（可自构建） | package.json `repository` |
| Web 形态 | `dsh --profile web` 在 loopback 起完整 GUI（默认 3080），启动 URL 带 token，签发会话 cookie，支持 `--no-open/--port/--host/--tls-cert/--tls-key/--trusted-host/--public-url` | `dsh-web-app/README.md`、`docs/user/guide/public-deployments.zh.md` |
| 无头形态 | `dsh-headless`（一次性 CLI 任务）、ACP（`dsh-acp-app`） | 依赖清单 |
| **官方快速上手** | `npx @deepseek-ai/dsh web` —— 默认 `http://127.0.0.1:3080`，本地启动自动开浏览器，`--no-open` 关闭；与 `dsh --profile web` 同义 | [官方 README](https://github.com/deepseek-ai/deepseek-harness)「Run from npm」 |
| 源码构建 | monorepo（`apps/ packages/ native/ python/ vendor/`）：`git clone → pnpm install && pnpm run build && pnpm dsh web` | README「Run from source」 |
| 上游状态 | **Developer preview，MIT 开源，迭代快**（README 明示将有破坏性变更，运行前须读 [SAFETY.md](https://github.com/deepseek-ai/deepseek-harness/blob/master/SAFETY.md)）；反馈走 [GitHub Discussions](https://github.com/deepseek-ai/deepseek-harness/discussions) | README |
| 文档站点 | https://deepseek-harness.github.io/deepseek-harness/ | README |

### 1.2 原生依赖矩阵（决定移植难度）

`@deepseek-ai/dsh` 的**直接**依赖里唯一的平台二进制来自这些传递依赖：

| 原生件 | 谁依赖 | linux-arm64 可用性 | Android 处置 |
|---|---|---|---|
| `koffi`（FFI：flock 等） | `dsh-fs-local`、`dsh-session-persistence-jsonl`、`dsh-subprocess-local` | ✔ 官方 `@koromix/koffi-linux-arm64` 预编译 | 直接用 |
| `node-pty` 1.2.0-beta.15 | `dsh-subprocess-local`（bash/终端的 PTY 核心） | ✘ 无 linux-arm64 prebuild → **需 node-gyp 编译**（glibc 环境内 `build-essential` 即可）；Termux 生态另有 `@lydell/node-pty-linux-arm64` 等 prebuild 源 | 在 proot 内编译，或由 CI 预编译进镜像 |
| `@vscode/ripgrep` ^1.18 | `dsh-util-ripgrep` | ✔ postinstall 自动下载 `aarch64-unknown-linux-gnu` 包（glibc 环境下 `os.platform()=linux`） | 国内网络走 npmmirror 二进制镜像；CI 预置 |
| `sharp` 0.35.5 | `dsh-attachment-local`（图片缩放） | ✔ `@img/sharp-linuxarm64`（glibc）/ `@img/sharp-linuxmusl-arm64` | 直接用（**前提：跑在 glibc 容器里**；纯 Termux bionic 环境要退化成 sharp-wasm32，见先例项目） |
| `@deepseek-ai/node-addon-system` | bash 沙箱（Landlock launcher + flock addon） | ✔ **官方一等公民**：[支持矩阵](https://github.com/deepseek-ai/deepseek-harness/blob/master/native/system/docs/support-matrix.md)列明 linux-arm64 由 ubuntu-24.04-arm 构建，静态 Landlock 启动器 + glibc/musl 双版 `system.node`，Node 20/22/24/26 CI 验证 | 装得上；矩阵原文——Landlock 由**功能探针**判 full/partial/unusable（内核版本不保证），Android 内核普遍 unusable，行为见 §3.4 |
| `sherpa-onnx-node` | 实验语音输入（sensevoice） | ✘ 无 arm64 预编译 | 移动 profile 中禁用 |
| `@deepseek-ai/libreoffice-kit(+win32/darwin 运行时)` | Office 转换/预览 | ✘ Android 无法运行 LibreOffice | 禁用；Office 文档改由容器内 python（python-docx/pptx/xlsx）处理 |
| `dsh-win32-process`、`dsh-sandbox-windows-acl`、`dsh-desktop-host`、`dsh-cua` | 桌面专属 | — | 不装（本就不在 CLI 依赖里） |
| SQLite | `dsh-session-query-sqlite` **无原生依赖**（Node 24 内置 `node:sqlite`，含 FTS5） | ✔ | 零成本 |

> **推论**：只要给 DSH 一个 **glibc 的 linux-arm64** 用户态（而不是 Termux 的 bionic/
> `process.platform==='android'` 环境），npm 安装链路**一行代码都不用改**。
> 先例项目走的是「纯 Termux bionic」路线，所以被迫自写了 `liblinkcompat.so`、
> `boo-sandbox-run`、`libkeyinject.so` 等 LD_PRELOAD 补丁层——**这正是我们选择 proot 路线的原因**：
> 补丁层大部分可以省掉。

### 1.3 桌面版会拖进来的"重资产"（npm 路线天然不含）

Electron 打包版把 LibreOffice（数百 MB）、bundled Python/pnpm 运行时、sherpa-onnx 都装进了
`app.asar.unpacked`。npm 独立安装不含这些，APK 体积因此可控（引擎 `node_modules` 实测约
55 MB 压缩包，先例项目的 `engine.bin` 即此物）。

---

## 2. 总体架构决策

### 2.1 候选路线对比

| 维度 | A. Termux+proot-distro（推荐起步） | B. 自包含 APK + proot + 预构建 rootfs（"独立系统"终态） | C. 纯 Termux bionic（先例项目路线） | D. 手机连远程 DSH 服务器 |
|---|---|---|---|---|
| 开发量 | 0（一键脚本） | 中（Kotlin 壳 + CI 镜像） | 高（LD_PRELOAD 补丁层全家桶） | 0，但不是"移植" |
| DSH 改动 | **无** | **无** | 需 android 平台垫片（link/sandbox/sharp-wasm） | 无 |
| 引擎完整性 | 100%（glibc，官方二进制生态） | 100%（同左） | 95%（bionic 特判多） | 100% |
| "独立系统"观感 | 依赖装 Termux，像装个运行库 | 单 APK 双击即得 | 单 APK | 依赖网络 |
| 性能 | proot 开销（CPU ~5%，FS 15–40%） | 同左 | 原生最快 | 最好 |
| 分发 | F-Droid Termux + 脚本 | 侧载 APK（≤28 targetSdk） | 侧载 APK | — |
| 风险 | 依赖 proot 对 ptrace 可用性（个别 ROM 禁） | 同左 + 壳自身工程量 | 全在自维护补丁层 | 数据出设备 |

**结论**：A 验证价值 → B 产品化，C 的补丁层思想仅在需要"文件级沙箱"时按需引入（§7.3）。
D 作为长期备选：DSH 的 Web GUI 天然支持远程访问（TLS + token + Host 栅栏），同一套代码
"设备在桌上就是服务器、在手机就是客户端"，A/B 的成果直接复用。

### 2.2 进程与网络拓扑（A、B 通用）

```
Termux(APK) / 壳APK(ForegroundService, 常驻通知)
   └─ proot（ptrace 主进程，--kill-on-exit）
       └─ node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js
              --profile web --no-open --port 3080
           ├─ HTTP/WS 服务器 @ 127.0.0.1:3080（loopback，绝不 0.0.0.0）
           ├─ bash 工具 → /bin/bash（容器内）→ 子进程继续被 proot 跟踪
           ├─ 终端面板 → node-pty → /dev/ptmx（Android 内核 PTY 可用，先例项目已验证）
           ├─ 会话持久化 → /root/.dsh/profiles/web/…（ext4 私有分区，SQLite WAL 安全）
           └─ LLM API → 设备网络栈（DNS: 容器 /etc/resolv.conf=8.8.8.8，装机时自动写好）
UI：WebView / Chrome 加载 http://127.0.0.1:3080/<token>（token 只出现一次，换 cookie）
```

要点：
- proot **不做网络隔离**，Node 直接共享 Android 内核网络栈 → loopback 可达、无额外配置。
- WebView 与服务器同设备，`127.0.0.1` 永远同域，Host 栅栏天然通过（文档：栅栏接受 loopback）。
- 同一 Android app 的 uid 即可绑定 1024 以下端口之外的一切端口；3080 无需特权。

---

## 3. Android 平台硬约束（逐条验证）

这三条是"改不掉的物理定律"，方案必须绕开而不是优化：

### 3.1 execve 私有目录 ⇒ targetSdk ≤ 28（仅当自打包 APK 时适用）

Android 10（API 29）起，`untrusted_app` 域禁止执行应用私有目录内的文件（W^X，
`app_data_file execute_no_trans`）。node/bash/dpkg 全在 `filesDir` 下 → 一旦 targetSdk ≥ 29
全线 `Permission denied`。Termux 官方与先例项目均停在 28。**代价：只能侧载，上不了 Google Play。**
路线 A 用现成 Termux APK，此约束由 Termux 承担，用户无感。
证据：[先例项目 README「两条硬约束」](https://github.com/kai6666666/deepseek-harness)。

附带：若 APK 内做 ELF 路径补丁（Termux bootstrap 的 `$PREFIX` 重定位），包名必须恰好
15 字符（`/data/data/com.termux/files`=27B 等长原地覆盖，`/data/data/<pkg>/`=12+N ⇒ N=15）。
先例 `com.dsh.android` 即由此而来。自建 B' 若用"bundled proot + 通用 rootfs"可不玩 ELF 补丁。

### 3.2 SELinux 能力缺失清单（proot 只转译路径，不转译 SELinux）

| 能力 | 状态 | 对 DSH 的影响 | 处置 |
|---|---|---|---|
| `link()` 硬链接 | 普遍 EACCES | npm 内部布局（少量硬链接） | proot-distro 默认 `--link2symlink` 兜底（README 实测命令行含此参数）✔ |
| user namespaces / `unshare` | 不可用 | bwrap/真容器不可行 | 接受；沙箱走 §7 |
| Landlock | **GKI 内核普遍未编译**（实测 5.15-android13 与 6.12 设备均 `ENOSYS`/`CONFIG_SECURITY_LANDLOCK` 未启用） | DSH confined 模式 **fail-closed**：无可用 runner 时 bash 直接报 `SANDBOX_UNAVAILABLE`，绝不静默放行（`dsh-bash-sandbox` README 原文） | 默认切 full-access 预设（§7.1），可选自研 provider（§7.3） |
| systemd / 真 init | proot 内不可用 | 不能 systemctl | 用 Termux:Boot + 监督循环脚本（§8） |
| 部分 `/proc`（stat/uptime/loadavg） | proot-distro 注入固定替身 | `os.cpus()`/`top` 数值失真 | 仅影响展示，接受 |
| PTY（`/dev/ptmx`） | 可用（`--bind=/dev`） | node-pty/tmux 正常 | ✔（先例项目已真机验证 PTY 全功能） |
| 后台被杀 | Doze / OEM 省电 / Android 12+ phantom process killer | 引擎掉线 | 前台服务 + wake-lock + 忽略电池优化（§8） |

### 3.3 存储语义

- `/sdcard`（FUSE/sdcardfs）：**无 exec、无符号链接、无 chmod** → 引擎、`node_modules`、
  SQLite、工作区一律放应用私有 ext4（rootfs 内）；`/sdcard` 仅作文件交换盘
  （proot-distro 默认 `--bind=/storage`）。DSH 的 `present`/附件导出落 `/root/exports`，
  用 `proot-distro copy` 或系统文件管理器（SAF）取回。
- 私有目录卸载即消失 → §10 的备份通道是必选项，不是可选项。

### 3.4 proot 的"假 root"边界

proot 的 root 是 ptrace 伪造（uid 仍是应用 uid）：`chown` 假成功、真挂载不存在。
DSH 不用这些语义（文件属主、挂载均无依赖），`git` 仓库建议 `git config core.fileMode false`。

---

## 4. 路线 A 详细设计：Termux + proot-distro（Day-0）

**适用**：立刻获得 100% 功能；日常主力使用；也作为路线 B 资产的开发/调试台。

### 4.1 安装序列（人工版，脚本见 [`scripts/a1-termux-bootstrap.sh`](scripts/a1-termux-bootstrap.sh)）

```bash
# ── Termux 侧（F-Droid 或 GitHub Releases 安装，勿用商店旧版）──
pkg update -y && pkg install -y proot-distro wget xz-utils termux-services   # 可选 termux-api
# ── 发行版：二选一 ──
proot-distro install ubuntu:24.04          # Docker Hub 直连
# 国内网络备选：官方 ubuntu-base 裸 rootfs（proot-distro 支持从 URL 安装 plain tarball）
proot-distro install https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz --name ubuntu
proot-distro login ubuntu                  # 进去先 apt-get update 验证 DNS
```

容器内一次性初始化（脚本 `a1` 自动生成并 `proot-distro copy` 进去执行）：

```bash
export DEBIAN_FRONTEND=noninteractive
apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl xz-utils git build-essential python3 ripgrep tmux procps iproute2
# Node 24 官方 arm64（glibc）：npmmirror 镜像；海外可用 nodejs.org
export NODE_VER=v24.9.0
curl -fsSL "https://npmmirror.com/mirrors/node/$NODE_VER/node-$NODE_VER-linux-arm64.tar.xz" \
  | tar -xJ -C /usr/local --strip-components=1
node -v && npm -v
# DSH：npm 原版，零补丁。node-pty 现场编译（build-essential 已装）
npm config set registry https://registry.npmmirror.com        # 可选
export npm_config_disturl=https://npmmirror.com/mirrors/node   # node-gyp 头文件镜像
npm install -g @deepseek-ai/dsh
# ripgrep 若 @vscode/ripgrep postinstall 拉 GitHub 失败：apt 的 rg 已装兜底
```

### 4.2 启动与访问

```bash
proot-distro login ubuntu -- dsh --profile web --no-open --port 3080
# stdout 出现： dsh web: http://127.0.0.1:3080/?dsh-token=…  （token 一次性，换 cookie）
```

- 手机 Chrome 打开该 URL → 菜单「添加到主屏幕」→ 全屏 PWA 体验（登录态走 cookie，持久）。
- 侧滑分屏/折叠屏/平板自适应由官方前端负责；如需窄屏增强，走 DSH 插件机制写一个
  client 侧移动适配 bundle（先例项目的 `mobile-ui-plugin` 提供了可抄的交互清单：悬浮球、
  窄屏抽屉、审批卡片放大等）。
- `DEEPSEEK_API_KEY` 或号池凭据在「Settings → Models」里配置（web GUI 内即可完成），
  或先 `export DEEPSEEK_API_KEY=...` 写入容器 `/root/.bashrc`（私有存储）。

### 4.3 开机自启与保活（脚本 [`scripts/a2-`](scripts/) 两个文件）

```bash
# Termux 侧一次性：
mkdir -p ~/.termux/boot
# 写入 ~/.termux/boot/start-dsh（见 a2-termux-boot-dsh.sh），安装 Termux:Boot 并打开一次
# 手机设置：对 Termux/Termux:Boot 关闭电池优化
termux-wake-lock        # 监督脚本内已含
```

监督循环（容器内）：`while true; do dsh --profile web --no-open; sleep 5; done` 包在
`nohup` + Termux session 通知里；崩溃自愈、日志轮转到 `/root/logs/dsh-web.log`。

### 4.4 验收清单（路线 A 完成的定义）

> 已细化为可打勾、可分流的门禁文档：**[docs/device-acceptance.md](docs/device-acceptance.md)**（含设备矩阵、A/S/K/U 四组项、失败分流、记录模板）。以下为摘要。

- [ ] `node -v ≥ 24.9`、`dsh --version` 正常，无 Landlock 相关启动错误（应打印沙箱不可用的降级说明而非崩溃）
- [ ] 会话创建/续聊/压缩/历史检索（node:sqlite FTS5）全通
- [ ] bash 工具：`echo hi > /root/t && rg hi /root/t`；persistent shell 保持 cwd
- [ ] 终端面板开 PTY（`htop` 能跑）；job 后台任务跨会话可查
- [ ] grep/glob/read/write/edit 工具；图片附件（sharp）缩放链路
- [ ] 息屏 10 分钟 + 切后台 30 分钟后回来会话仍在（wake-lock + 电池白名单）
- [ ] 重启手机后服务自恢复（Termux:Boot）
- [ ] 子代理 / agent team / workflow 并发 5 路无 OOM（中端 6GB 机）

---

## 5. 路线 B 详细设计：自包含「独立系统」

目标形态：一个 APK（+可选 sidecar rootfs 资产），双击图标 = 一个完整的 DSH 主机。
两条子路线可组合：

### B'（推荐）**proot 容器壳**：APK 内嵌 `proot` 二进制 + appliance rootfs + WebView

- **APK 组成（Kotlin）**：
  - `ForegroundService`：常驻通知「DSH 引擎运行中」；持有 PARTIAL_WAKE_LOCK；
    fork 并监管 proot→node 进程树（`--kill-on-exit` 语义由服务重启循环实现）。
  - `WebView`（flavor `system`）/ `GeckoView`（flavor `gecko`，系统 WebView 太旧/被裁时）
    加载启动日志里的 token URL；cookie 持久化到应用目录；
    `usesCleartextTraffic` 用 networkSecurityConfig 精确放行 `127.0.0.1`。
  - 资产解包器：首启把 `dsh.oci.tar`（或 squashfs/tar.xz rootfs）解到 `files/`，校验 SHA-256。
  - 文件桥：SAF 导入导出（`proot-distro copy` 的等价物）、诊断包一键导出（日志+env 快照）。
  - 救援模式：引擎连续崩溃 N 次 → 进入「安全启动」：`--profile web` 禁用第三方插件目录
    （DSH 插件出问题的标准解法），并提供"清空 profile（保留备份）"。
- **为什么壳里有 proot 而不是裸 bionic**：glibc 容器让 §1.2 表里的官方预编译件全部原样可用，
  免去 `liblinkcompat`/`sharp-wasm32`/`platform==='android'` 垫片（先例项目为这些写了 24 个资产 + 3 个 .so）。
  代价是 ptrace 性能税；收益是**引擎零补丁、升级只换镜像**。
- **构建资产流水线**（PC/CI，一次构建全网分发）：
  [`scripts/b1-build-appliance.sh`](scripts/b1-build-appliance.sh) + 同目录 `Dockerfile.appliance`：
  `docker buildx --platform linux/arm64`（Windows 上走 WSL2/Docker Desktop）→ 容器内
  `npm install -g @deepseek-ai/dsh`（node-pty 在真 arm64 glibc 环境编译好）→ `docker save` 出
  `dsh-appliance-<ver>.oci.tar`。手机侧 `proot-distro install <URL 或本地 tar>` 即可——
  **同一资产同时服务路线 A 和 B'**。
- **硬约束**：targetSdk 28、仅 arm64-v8a、侧载分发（GitHub Releases/自建更新源）；
  版本戳纪律：换资产必须同步 versionCode 与 stamp 文件（先例项目「静默失败陷阱」）。

### B''（对照）**Termux 运行时复用壳**：APK 只做 WebView+配对，引擎交给已装 Termux

工程量最小（≈3 个类），但把体验交给 Termux 保活质量。适合先例项目那种"引擎随包"路线
不可行时的过渡。不展开。

### 先例项目复用策略

[kai6666666/deepseek-harness](https://github.com/kai6666666/deepseek-harness)（MIT，v0.4.2，
35 篇开发文档、救援模式、双内核 flavor、远程配对、MCP loopback bridge 全部现成）：

| 选择 | 适合 |
|---|---|
| **直接用其发行版 APK** | 想今天就体验"独立系统"，接受 bionic 补丁层 |
| **fork 其壳 + 换成 B' 的 proot 容器资产** | 要其工程外壳（保活/UI 调试/救援/MCP），不要 bionic 垫片 |
| **按本节自建** | 要完全掌控供应链与更新策略 |

注意其 `keyinject.bin`（内置号池）为可选且涉及账号合规，自建时以用户自备
`DEEPSEEK_API_KEY` 为标准路径。

---

## 6. 功能开关矩阵（移动 profile 的取舍）

| 能力 | 状态 | 说明 |
|---|---|---|
| chat/会话/压缩/子代理/团队/workflow/goal/schedule/审批 | ✅ 完整 | 纯 JS |
| bash + persistent shell、终端面板（PTY）、job | ✅ | node-pty 编译后 |
| fs 工具 + ripgrep + glob | ✅ | rg arm64 |
| 图片附件（sharp）、`read_image` | ✅ | linux-arm64 预编译 |
| 会话搜索（SQLite FTS5） | ✅ | Node 内置 sqlite |
| 外部程序嵌入引擎（官方 Python SDK `deepseek-harness-sdk` + `deepseek-harness-runtime-bin` wheel，stdio JSON-RPC） | ✅ 可选 | 壳 APK 走 HTTP 面已够用；SDK/ACP 留给进阶集成。注：该 wheel 是“dsh 运行时的 Python 载体”，不是 Office skill 依赖的解释器——后者仍按本表 python-docx 路线由 apt python3 提供 |
| 文件上传/交付物 present | ✅ | 导出走文件桥（§3.3） |
| pwsh 工具（PowerShell） | ⛔ 默认关 | 需要时可 `apt install powershell`（linux-arm64 官方 deb 存在），非必需 |
| Office 转换/预览（LibreOffice kit） | ⛔ 关 | 以容器内 python-docx/pptx/openpyxl 路线替代（office skills 的 Python 依赖 `pip install` 即可） |
| 语音输入（sherpa-onnx） | ⛔ 关 | 无 arm64 预编译 |
| 桌面控制 CUA、win32 相关 | ⛔ 天然不存在 | 不装包 |
| 插件市场安装 bundle | ✅ | 纯 JS bundle；native bundle 按 arm64 目标重新构建 |
| MCP（stdio 子进程/HTTP） | ✅ | 子进程同样在 proot 内；可加 Android intent 桥 MCP（先例 `bridge/mcp-android`：open-with/分享/剪贴板） |
| 远程访问（电脑连手机上的引擎） | ✅ 可选 | `--host <wlan-ip> --tls-cert/--tls-key --trusted-host`；默认关 |

---

## 7. 安全模型（Android 语境重写，不是照搬桌面）

### 7.1 沙箱策略

桌面 DSH 的文件级沙箱（Landlock/bwrap/Seatbelt）在 Android 内核上不可得，且 DSH
**confined 模式 fail-closed**（无 runner → `SANDBOX_UNAVAILABLE`）。因此：

- **默认**：web profile 的沙箱策略设为 full-access（权限预设「完全访问」/
  `dsh-sandbox-policy` 默认 mode 配置），**同时保留审批流**（approval prompt 开启、
  `danger-full-access` 的一次性升级确认等 UI 层闸门不变）。
  理由：真正的边界已经上移——引擎整体困在 Android **应用沙箱**（专属 uid、SELinux
  `untrusted_app` 域、无网络权限外的系统写路径、/sdcard 需用户授权）内。
  这是「same-world, outer-jail」模型：与 DSH 文档中 same-world confinement 词汇一致。
- **风险声明**：full-access 下 Agent 可读写 Termux/壳应用的全部私有数据（含 API key 文件）。
  缓解：引擎专用目录最小化（profile 与凭证分目录）、敏感文件不进工作区、审批流兜底。
- **加固选项（ rooted/未来内核）**：若 `dsh` 的 Landlock probe 通过（部分新 GKI/自定义内核），
  直接恢复官方 confined 模式，零配置改动。
- **可选 provider（进阶）**：为 Android 写一个 `ctx.sandbox` provider 插件，后端用
  seccomp+LD_PRELOAD（先例项目 `boo-sandbox-run` 的设计：workspace-write 文件写边界完整，
  仅缺 PID 隔离）——作为 M3 增强，不是 Day-0 依赖。

### 7.2 Web 面

- 只绑 loopback；Host/Origin 栅栏对 `127.0.0.1:port` 天然放行（官方行为）。
- 启动 token 一次性、URL 不进历史（重定向剥 token）；cookie 为签名 host-only。
- 壳 APK 禁 WebView 外链（`shouldOverrideUrlLoading` 只允许本机 origin），防"任意网页驱动引擎"。
- 远程暴露默认关闭；开启时必须 TLS（DSH 原生支持 `--tls-cert/--tls-key`），且 `--trusted-host`
  白名单，不公网直挂（文档原文：栅栏只放行请求，端口本身要靠你自己守住）。

### 7.3 凭据

无 OS keychain → `dsh-credentials-local` 文件存储，位于应用私有目录（其他 app 不可读）。
不内置共享号池（合规与追责风险）；语音播报"密钥已导出"类遥测保持 DSH 默认关闭项不变。

### 7.4 更新完整性

rootfs/引擎资产带 SHA-256 + 版本号双戳；APK 自更新走 HTTPS + 校验（侧载场景），
失败自动回滚上一版资产。

---

## 8. 生命周期工程

| 事件 | 处置 |
|---|---|
| 开机 | Termux:Boot（路线 A）/ 壳 APK `BOOT_COMPLETED` + ForegroundService（B'） |
| 前台运行 | 常驻通知（点击回到 WebView/打开 Chrome）；wake-lock 常持 |
| 息屏/后台 | 忽略电池优化白名单引导页；Android 12+ 若被 phantom-kill：保持"同 app 树"（proot 子进程属于同 uid，实测 Termux 场景受控于前台服务可见性），厂商省电策略逐项白名单（小米/华为另设） |
| 引擎崩溃 | 监督循环 5s 重启，连续 5 次失败进救援模式并通知（不无限弹窗） |
| 内存压力 | `--max-old-space-size` 按设备 25%~40% 设置（如 6GB 机给 2048）；OOM 由 LLM 会话压缩先行 |
| 升级 | 引擎：`npm i -g @deepseek-ai/dsh@<ver>`（A）或换 appliance 镜像（B）；先 `proot-distro backup` |
| 卸载 | 数据导出向导（会话 JSONL + profile + 工作区 zip）后才谈卸载 |

---

## 9. 性能预期与调优

- proot 税：CPU 密集 ~5%，文件系统密集（apt/编译/git/grep）**15–40%**（proot-distro README
  与社区实测口径一致）。Agent 日常交互（网络 LLM 占大头）感知不明显；`rg` 大仓检索最慢，
  可接受。
- 调优清单：工作区放内部存储（勿放 /sdcard）；`git config core.fileMode false`；
  终端 `TERM=xterm-256color`（proot-distro 已默认导出）；node `--use-largepages=off`；
  大仓先浅克隆；镜像源全链路换国内（apt/node/npm/ripgrep）。
- 参考基线（骁龙 8 Gen2/12GB，真机先例）：首启解包 10–60s；稳定运行 24h+ 不掉线
  （前台服务 + wake-lock 前提）。

---

## 10. 备份与迁移

- `proot-distro backup ubuntu --output /sdcard/…/dsh-$(date).tar.xz`（含 rootfs+profile）。
- 最小备份集：`/root/.dsh`（profiles/config/credentials）+ 各工作区 git 仓库。
- 恢复：新设备同版本 Termux+proot-distro → `proot-distro restore` → API key 重配一次即可。
- 壳 APK：把 backup/restore 做成设置页按钮（调同一命令面）。

---

## 11. 里程碑与验收

| 里程碑 | 内容 | 交付物 | 验收 |
|---|---|---|---|
| **M0 冒烟**（0.5 天） | 路线 A 手动流程在一台机上跑通 | 环境 + 截图 | §4.4 前 5 项 |
| **M1 脚本化**（1–2 天） | a1/a2 脚本 + appliance 镜像 v1（CI） | `scripts/` 全部、`dsh-appliance:0.1` | 两台异构设备（含小米/华为各一）15 分钟内从零可用 |
| **M2 壳 APK**（1–2 周） | B' 壳：前台服务+WebView+资产解包+救援+诊断导出（可 fork 先例） | `dsh-android-x.y.apk`（system/gecko 双 flavor） | 双击图标即得 GUI；崩溃自愈；重启自恢复；息屏 2h 在线 |
| **M3 移动增强**（迭代） | 移动适配插件（悬浮球/窄屏抽屉，挂 [`dsh-plugin` topic](https://github.com/topics/dsh-plugin)）、Android intent MCP 桥、沙箱 provider（可选）、远程访问向导；向 [上游 Discussions](https://github.com/deepseek-ai/deepseek-harness/discussions) 提交 Android/linux-arm64 支持提案（node-pty aarch64 预编译、sandbox provider 接缝文档） | 插件 bundle ×3 + 上游 issue | 官方前端零改动前提下的"原生感" |
| **M4 分发运营** | 版本戳纪律、更新通道（HTTPS+校验+回滚）、兼容矩阵页 | Releases 流程 | 一次带外升级演练 |

**建议测试矩阵**：Android 12/13/14/15/16 × {Pixel（接近 GKI）、小米 HyperOS、三星 OneUI、
一台 Android Go 低内存机}；WebView 版本下限探测（系统 WebView 停更设备 → gecko flavor）。

---

## 12. 风险登记册

| # | 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|---|
| R1 | 个别 ROM 限制 ptrace → proot 起不来 | 低-中（OEM 而异） | A/B' 整体不可用 | 备选 B''/C 路线（Termux 原生 + 垫片，先例项目即答案）；兼容矩阵公示；引导用户 `proot -0 /bin/echo` 预检 |
| R2 | node-pty 编译/PTY 行为在个别内核异常 | 低 | bash 工具不可用 | appliance 内预编译产物随镜像分发；退化路径：Docker 镜像内先以 pipe-spawn 垫片验证 |
| R3 | Landlock 期望错位（用户以为有沙箱） | 高（文档层面） | 安全误判 | 首启明确展示"same-world + App 沙箱"安全说明；`dsh` 启动日志已含 runner 状态 |
| R4 | 厂商省电杀后台 | 中 | 会话掉线 | 前台服务+白名单引导+自愈监督循环；通知内嵌"重新拉起"入口 |
| R5 | npm/GitHub 网络（国内） | 高 | 装机失败 | 全链路镜像（npmmirror/USTC/ubuntu-base URL）+ appliance 镜像内置全部二进制 |
| R6 | 侧载分发（Play 不可） | 确定 | 安装摩擦 | 官网 Releases + SHA-256；内部分发用 MDM/工作资料 |
| R7 | 上游 DSH 升级引入新原生件 | 中 | 镜像失配 | CI 每晚重建 appliance + 冒烟测试（headless 一轮对话）自动发现 |
| R8 | WebView 碎片化（API 缺失） | 中 | GUI 白屏 | gecko flavor；启动探测降级页 |
| R9 | 上游 developer preview 快速迭代、明示将有破坏性变更（官方 README/SAFETY.md） | 高（必然发生） | 引擎升级导致镜像/壳失配 | appliance **锁版本**构建（`DSH_VERSION=x.y.z`）；壳与引擎分频道更新；R7 的 nightly 金丝雀先于用户吃到升级 |

---

## 13. 立即可做的第一步

- **一行式（推荐）**：Release 产物就位后，Termux 里
  `export DSH_ANDROID_BASE=https://github.com/<owner>/<repo>/releases/download/<tag>` 然后
  `curl -fsSL "$DSH_ANDROID_BASE/install.sh" | bash`
  （预检、SHA-256、断点重跑、契约退出码，见 `scripts/install.sh`。必须 export 形态——行首前缀不穿管道）。
0. **30 秒冒烟（可选）**：任何已装 Node ≥ 24.9 的 linux-arm64 环境（包括你手机上刚建好的
   proot Ubuntu）跑官方一行命令 `npx @deepseek-ai/dsh web`——起来即证明 npm 分发链路在
   Android 上完整可用，后面全是工程化。
1. 手机装 F-Droid → Termux + Termux:Boot（GitHub Releases APK 亦可）。
2. 把 [`scripts/a1-termux-bootstrap.sh`](scripts/a1-termux-bootstrap.sh) 传到手机（curl/粘贴均可），
   Termux 里 `bash a1-termux-bootstrap.sh`。
3. 按提示执行 `proot-distro login ubuntu -- dsh-serve`，Chrome 打开打印的 URL。
4. 用 PC 跑 [`scripts/b1-build-appliance.sh`](scripts/b1-build-appliance.sh)（WSL2/Docker Desktop）
   产出 `dsh-appliance-<ver>.oci.tar`，之后所有新设备一条
   `proot-distro install <url>` 完成克隆——这台手机从此就是你的"移动 DSH 主机"。

---

## 附录 A：与桌面安装的关键差异对照

| 项 | Windows 桌面（本机实测） | Android 方案 |
|---|---|---|
| 载体 | Electron（app.asar + win32 原生件） | Node CLI + Web GUI（npm 原版） |
| shell | PowerShell（pwsh 工具）+ Windows 沙箱 ACL | bash（容器）+ full-access/App 沙箱 |
| 界面 | Electron 窗口 | WebView/Chrome（同一套官方 Web 前端） |
| 沙箱 | 受限令牌 / (Linux 上为 Landlock) | Android 应用沙箱（外） + 审批流（内） |
| Office/CUA/语音 | 有 | 关/替代实现（§6） |
| 升级 | 应用内更新器 | npm/appliance 镜像 + 校验回滚 |

## 附录 B：证据与引用

- **官方仓库**：[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
  （MIT；`npx @deepseek-ai/dsh web` 快速上手；源码构建 `pnpm install && pnpm run build && pnpm dsh web`；
  [native/system 支持矩阵](https://github.com/deepseek-ai/deepseek-harness/blob/master/native/system/docs/support-matrix.md)
  列明 linux-arm64 官方构建与 Landlock 探针语义；
  [SAFETY.md](https://github.com/deepseek-ai/deepseek-harness/blob/master/SAFETY.md) 的 developer-preview 告诫；
  [文档站](https://deepseek-harness.github.io/deepseek-harness/)）
- DSH 安装审计：`app.asar/dsh/package.json`、`@deepseek-ai/dsh@0.2.2-rc.2` manifest、
  `dsh-web-app/README.md`、`dsh-bash-sandbox/README.md`（fail-closed 条款）、
  `node-addon-system/README.md`（"unsupported kernels/platforms probe unusable"）；
  npm registry：`@deepseek-ai/dsh`（public, engines node≥24.9.0）、
  `@deepseek-ai/node-addon-system-linux-arm64@0.1.2`（os=linux/cpu=arm64 已发布）。
- [DSH for Android（先例，MIT）](https://github.com/kai6666666/deepseek-harness)：
  targetSdk≤28 与 15 字符包名两条硬约束、bionic 垫片层、双 WebView 内核、救援模式、
  "bash 需要沙箱后端"的 Android 现实、node-pty/PTY 真机可用性。
- [termux/proot-distro](https://github.com/termux/proot-distro)：默认 proot 参数
  （`--kill-on-exit --link2symlink --sysvipc -0`）、DNS/hosts/aid_ 用户自动修复、
  OCI/裸 rootfs/URL 安装、`/proc` 替身、无 systemd/无 namespace 限制、性能口径。
- Landlock 在 Android 内核缺失的独立实测：[openomnibot DSH 沙箱验收（5.15-android13 GKI，probe 退出 125）](https://github.com/omnimind-ai/openomnibot/blob/455cb5f8b3f75d63b5a17bd7f69776673bf027fc/docs/testing/dsh-sandbox-acceptance-2026-09-08.md)、
  [Android 16 / Linux 6.12 设备内核配置核查](https://github.com/mekhontsev/magicdesk/blob/e351b056365ca9dca21ae9d925be370/native/guest-exec-lab/chromium-isolation.md)。
- [Termux:Boot 官方 wiki](https://wiki.termux.dev/wiki/Termux:Boot)（boot 脚本目录与 wake-lock 用法）。
- [nodejs.org 官方 linux-arm64 发行物](https://nodejs.org/dist/latest-v24.x/)、
  [npmmirror（node 镜像/二进制镜像）](https://npmmirror.com/)、
  [ripgrep arm64 发布物](https://github.com/BurntSushi/ripgrep/releases)。

*方案版本 1.1 · 2026-11-13 · 基于 DSH 0.2.2-rc.2 与官方仓库 deepseek-ai/deepseek-harness（master @ 2026-11-11）*

---

## 14. 实现进度（随开发更新）

| 日期 | 交付 | 验证状态 |
|---|---|---|
| 2026-11-13 | `mobile-comfort/` 移动端 UX 适配 bundle v0.1（composer 下工具片：侧栏收展/整体字号/舒适样式开关 + 触摸人体工学样式层） | ✅ `install_bundle → applied`；live 槽位树 occupant `dsh-mobile-comfort active=true`；JS 语法/ESM/manifest/locales 全过 |
| 2026-11-13 | `mobile-comfort` **v0.2.0**：经子代理 UX 评审（M1–M7 必改全清）——点按高亮改品牌色弱反馈+`:active`（不再制造反馈真空）、语义进标签+首启可关提示条（不再只靠手机上失效的 title）、44px 触摸目标+字号对间隔、能力探测+4s 行内状态取代静默 catch、字号到界提示（复位指引到宿主外观设置）、桌面隐藏与舒适层均以指针类型为主判据、持久化边界如实改写 | ✅ reinstall `applied` v0.2.0 无 warning；occupant `active=true`。⚠️ 本会话页面仍是 v0.1 模块代际，新代码需页面刷新后生效；视觉/真机验证（触摸判定、键盘态、下拉刷新拦截）列为 M1 真机清单项 |
| 2026-11-13 | `android-shell/` 壳 APK 工程骨架（M2）：`DshEngineService`（proot 托管+保活+退避重启）、`MainActivity`（WebView+origin 围栏）、`BootReceiver`、仅 loopback 明文白名单、targetSdk 28 / 15 字符包名注释固化 | ⚠️ 未编译（本机无 Android SDK）；救援/诊断导出为 M2 待办 |
| 2026-11-13 | `android-shell` 资产管线：`AssetInstaller`（收件目录扫描、SHA-256 校验、rootfs.tmp 原子换树、`runtime.stamp` 版本戳、proot 就位+exec 位）+ `TarExtractor`（ustar/prefix、GNU 'L' 长名、pax 'x' path 覆盖、base-256、硬链接→复制、设备/FIFO 跳过、越界路径拒绝、流式 SHA）+ 服务接线 + `scripts/b2-stage-shell-assets.ps1` | Kotlin **未编译**（无 Android SDK）；解析器分支经 **JS 等价副本双测**：`tar-gnu-branches-test.mjs` GNU/pax/硬链接/设备 4 项 OK + bsdtar 真归档 parity 逐哈希一致。真机首启解包为 M2 待办 |
| 2026-11-13 | `android-shell` 救援与诊断：`RescueLadder`（两级阶梯：连续早退 3 次搁置 web profile、6 次 HALT 停自动重试；≥5min 稳定运行重置；`rescue.json` 持久化，全程可逆零删除）+ `DiagnosticsExporter`（zip：env/stamp/ladder/双日志 tail/收件清单，**token 导出前脱敏**）+ 通知三按钮（导出/恢复/重试）+ `startForegroundService` 统一入口（O 后台限制对 targetSdk 28 同样适用）。独立静态评审子进程中途崩溃，仅确证并修复 1 处编译错误（`AssetInstaller.StageException` 裸引用），其余按其评审提纲人工自查通过（阶梯时机/HALT 语义/wait-notify 竞态上界 60s） | ⚠️ Kotlin 编译通过性**仍未证**（无 SDK）；真机行为待 M2 实测 |
| 2026-11-13 | appliance 插件预置与诊断分享：CLI 实测确认 `dsh plugin` 为 pnpm 转发薄壳、bundle 激活=profile `dsh.profile.bundles` 数组（真实 profile 取证）→ `seed-mobile-comfort.mjs`（幂等 + 缺依赖拒激活 + 三路径本地测试通过）接入 `Dockerfile.appliance`（`plugin add` → seed → `--dump-config | grep` 构建即断言）；b1 构建上下文改仓库根；`DiagShareActivity`（NoDisplay 同步 hop）+ FileProvider（仅 exports/ 授权）接通「导出诊断」按钮 | seed 工具 ✅ 实测；Docker 构建需 arm64 环境未跑；NoDisplay/FileProvider 待真机 |
| 2026-11-13 | `mobile-comfort` v0.2.1：开态 ✓ 前缀（S1 收尾）。**状态更新：已按用户要求从 desktop profile 卸载回退**（live 复核：profile 条目 0、dock 槽位仅剩官方 activity/usage）。源码与安装方法保留；**今后任何 profile 安装只在用户明确同意后执行** | ✅ 卸载 applied + 双通道验证；插件装载期的槽位/样式验证记录保留在上方 v0.2.0 行 |
| 2026-11-13 | `scripts/` 路线 A 装机 + appliance 构建脚本、方案文档 v1.1（官方仓库证据并入） | bash 静态审查通过（WSL 不可用，未做 `bash -n`） |
| 2026-11-13 | CI `.github/workflows/build.yml`：appliance（arm runner 双通道 release/canary + **容器真实起服 curl token URL + mobile-comfort 激活断言**）、shell-apk（wrapper→assembleDebug→aapt2 三条硬约束校验）、tooling（tar parity diff -r + seed 幂等/拒绝）。自查修复 3 处（step-id 误写、token URL 应接受 3xx、单行 JSON 用 grep 计数失效） | YAML js-yaml 校验 ✔；断言脚本 Windows 本地干跑 4 项全过 ✔；GitHub Actions 实跑待仓库上线 |
| 2026-11-13 | 壳无 adb 首启引导：`SetupActivity`（SAF 选择 rootfs/SHA256SUMS/proot 三入口、实时清单点+大小标注、`.part` 原子落位、拷贝即触发服务 staging、就绪自动跳转 GUI）+ MainActivity 无资产自动重定向 + Manifest 登记；引导页深色大目标（44–48px）风格与 Web GUI 一致 | XML 三件良构 ✔（MainActivity/DiagShare/Setup/Service/FileProvider 全注册）；页内 JS **mock-DOM 行为测试 5 断言全过**；Kotlin 编译仍待 CI |
| 2026-11-13 | 路线 A 一键安装器 `scripts/install.sh`：预检（Termux 环境/arm64/磁盘 ≥4GB/ptrace 探针，各失败路径给人话+出路）→ 版本化拉取核心脚本 + SHA-256 强制校验（缺 sums 则黄字警示）→ a1 核心安装（幂等透传）→ 容器内 node/dsh 完整性验证 → 下一步指引（电池白名单/Termux:Boot/PWA）。契约退出码 10/11/12/13；CI tooling 新增**全脚本 `bash -n` 门 + exit-10 契约测试**（本机无 bash，语法门交给 CI 兑现） | YAML js-yaml 复验 ✔（tooling 7 步含新门）；install.sh 逻辑走查修复 1 处（proot 探测条件绕弯）；bash -n 实跑待 CI |
| 2026-11-13 | 发布闭环 `.github/workflows/release.yml`：tag → appliance（arm runner + 容器起服冒烟）/ 壳 APK（aapt2 硬约束门禁）/ installer 四件套+SHA256SUMS 三 job 并行 → `action-gh-release` 汇总发布，发布说明内置一行装机命令（`DSH_ANDROID_BASE` 直指本 tag 资产）。生产签名策略如实标注（CI=debug，release 签名走本地 keystore） | 两工作流 js-yaml 复验 ✔（build 3 job/release 4 job，needs 链正确）；heredoc `$` 转义、`--ignore-missing` 与四行 sums 兼容性自查过；Actions 实跑待仓库上线 |
| 2026-11-13 | `mobile-comfort` **v0.3.0**：S9 图标（16px currentColor 线性 SVG，aria-hidden）+ **无浏览器行为测试套件**（React/document/localStorage 垫片，真实 apply/渲染/点击路径，12 组断言）+ CI tooling 挂入。套件当场抓出并修复 2 个 v0.2 真实缺陷：① `ROOT_CSS` 定义未挂载（工具片完全无样式）② 开关反馈依赖 useEffect 回环（改 handler 同步生效，effect 保留为 remount 兜底）。proot CI 化侦查子任务并行进行中（外部 fetch 通道对该仓库持续 404/500，如实挂起） | 行为套件 12/12 绿 ✔；版本/README/CI 登记 ✔ |
| 2026-11-13 | **proot 供应线落地**：侦查报告（docs/proot-build-recipe-report.md，全程当日原文取证；8 项未证实如实列明）→ 发布线选型「Termux 官方 deb 可验证重打包」（Packages 索引 SHA256 强校验；NDK 编译配方留作后续实验，不以未证实项充当已验证）。新 `scripts/b3-fetch-proot.sh`（取包→校验→arm64 原生冒烟 → bundle tar）挂 release.yml `proot` job（publish 改 4-needs）；壳端三契约 env（`PROOT_TMP_DIR` 必须可执行 / `PROOT_LOADER` / `LD_LIBRARY_PATH`，enter.c 原文推导）+ AssetInstaller bundle 解包（复用 TarExtractor）+ SetupActivity proot 行接受 bundle/单文件 + b2 推 bundle。自查修复：3 处 `set -e`+`&&` 短路误杀、1 处 SAF 行语义返工 | release.yml js-yaml ✔（5 job，needs 链正确）；b3 结构平衡 ✔；真取包 + bash -n 待 CI 首跑 |
| 2026-11-13 | **交收物自洽审计 + 真机门禁**：新 [docs/device-acceptance.md](docs/device-acceptance.md)（设备矩阵 D1–D4、路线 A `A1–A10`、壳 `S1–S10`（含救援阶梯主动触发、诊断 token 泄漏 P0 检查）、保活专项 K1–K4、UX 真机项 U1–U8，全项「动作→期望→失败分流」+ 通过标准）。一致性修复 3 处过时表述（壳 README 的 proot 获取段→b3 主路径、scripts/README b2 行、§13 增一行式 install.sh 入口）；§4.4 挂验收文档指针；历史进度行不回改（审计只增不改） | 工作区全景 glob 核对 42 文件 ✔；三处修复均为「文档落后于实现」类 |
| 2026-11-13 | **全量回归快照（Round 12）**：跨 11 轮改动后统一复跑全部本地可执行验证——mobile-comfort 行为套件、tar GNU 分支、tar parity（bsdtar + diff -r）、双工作流 YAML、全仓 JSON 解析、三 XML。**8/8 全绿，零回退**。补 GitHub 落地面缺件：根 [README.md](README.md)（双路线快速开始、仓库地图、三条硬约束、质量策略、许可与来源） | 快照结果如上；README 链接由绝对路径修正为仓库相对 |
| 2026-11-13 | **等待页动态化（UX 黑盒等待消除）**：MainActivity 重写——引擎启动等待从单行静态文案升级为四步进度（资产就位→校验解包→引擎启动→就绪），状态源为真实管线信号（`isReady`/`runtime.stamp`/`rescue.json` level+failures+parked）；救援模式（HALT）给可操作指引且**如实标注「搁置不会删除」**；状态机 JS 重构为「先复位后按分支表置位」。新增 `scripts/dev/wait-page-behavior.test.mjs`（提取页内真实 JS：5 组断言含跨调用无残留、异常输入不抛、**静态防回归断言——等待页禁止出现 token/URL**）并挂入 CI tooling | 等待页套件 5/5 绿 ✔；build.yml js-yaml 复验 ✔；collectFacts 的 Kotlin 侧待 CI 编译 |
| 2026-11-13 | **mobile-comfort v0.4.0（二轮复审全量落地）**：第二轮子代理复审（M1–M5 必改 + S1–S12）→ M1 无障碍名去重读拼接（sr 仅增义时渲染）、M2 提示收进 ⓘ 折叠片 + 固定高状态行（消除每次调字号的双重建高跳变；× 微按钮随之消失）、M3 提示写盘失败如实播报、M4 两颗字号 chip 按能力探测**可见置灰**、M5 稳定 React key（开关不再重建丢焦点）+ S1 `visualViewport` 键盘退让（Chrome PWA 媒体查询失效的分叉消除）、S2 按方向限流（纠错反拍不被吞）、S3 chip 字号 clamp 封顶、S4 去 ✓ 文本通道（宽度稳定）、S5/S6 localStorage 单写者 + storage/visibility 跨标签收敛、S7/S8 上限判据交还宿主（空/坏快照不再谎报「已到最大」）、S9 老 WebView 灰档回退 + 输入下限 `!important`、S10 DETAIL 拆英文主档+中文档、S11 文案终稿（字号+/−、失败带出路）、S12 回调身份校验卸载。**行为套件重写为有状态 hook（T0）+ T1–T8 全落实**。另修正全仓 4 处文档的坏 `VAR=x curl \| bash` 用法（env 不穿管道 → 统一 export 形态）+ 新增 docs/first-push.md、.gitignore | 套件 10/10 绿 ✔（M/S 项全部有守卫断言）；等待页套件复跑 ✔；「未达可发布」→ M1–M5 清零后达到（待真机 U1–U8） |
| 2026-11-13 | **壳纯逻辑等价副本测试**（tar 双测同模式，补齐 Kotlin 无编译器下的最后可测面）：`dev/shell-logic-parity.test.mjs`——RescueLadder 状态机（升级曲线、**搁置窗口只开一次**、HALT 对稳定退出的粘性与下次早退再推导、restore/手动重装填）、SHA256SUMS 解析（标准/`hash *binary` 标记/CRLF/`dir/` 前缀/十六进制大小写/坏行跳过/未声明即容忍）、findPlan 选新与拒绝规则、stamp「名+哈希双匹配否则重解包」。首跑即抓出**我 fixture 的格式误解**（`*` 标记在 hash 后而非前），Kotlin 正则原本正确——测试反向教育了测试者 | 8/8 绿 ✔；CI tooling 升至 10 步（js-yaml 复验 ✔） |
| 2026-11-13 | **v0.4.0 真实装载验证（零用户目录改动）**：用户拒绝 `~/.dsh` 提权后改用 `DSH_HOME` 重定向到 workspace 内一次性 `verify-home`，跑**标准形态** `dsh plugin --profile mobile-verify add file:…`——pnpm 11 实装成功，profile `package.json` 证据：`bundles=["@deepseek-ai/dsh-base","@local/dsh-mobile-comfort"]` + deps 同步。三条新知识入册：① **`plugin add` 原生双写依赖与激活数组**（Dockerfile 的 seed 从「必须」降级为「防御兜底」，保留）；② `DSH_HOME` 是合法隔离验证通道；③ `runCli` 子命令成功即 `process.exit(0)`，宿主脚本不能连跑（测试工装教训）。verify-home 用后即删，复核 `~/.dsh/profiles/mobile-verify` **不存在**——被拒绝的写入从未发生 | 装载+激活断言 ✔；隔离复核 ✔；desktop profile 全程未触碰 |
| 2026-11-13 | **appliance 插件预置三层链逐环实证**（隔离 `DSH_HOME=verify-home2`，Dockerfile RUN 同构命令）：① `dsh plugin --profile web add file:…` 实装成功且 web 模板 bundles 初始化为 `base+web-app`；② **`--dump-config` 真实输出末层确认 `# == @local/dsh-mobile-comfort` + `id: mobile-comfort`**——Dockerfile 的 `grep -q` 断言依据首次见到真输出，成立；③ seed 对真实 pnpm 产物双跑幂等（bundles=3 不重复、comfort 激活在列）。appliance 镜像的插件面风险清零（剩余未知仅 npm/Docker 流程本身）。过程：一次沙箱嵌套 spawn EPERM（文档化边界），重构为 pwsh 顶层三连调用通过；两处 verify-home 用后即删并复核 | 三环全过 ✔；用户目录零写入 ✔ |
| 2026-11-13 | **v0.4.0 运行时投递层证据（第四级）**：隔离实例（`DSH_HOME=verify-home3` + `--port 3081`）真实起服，HTTP 200 的 GUI HTML 中 **`@local/dsh-mobile-comfort/client.js` 出现在与官方 60+ 模块同批的模块包 URL 列表里**——插件不再只是「profile 里激活」，而是进入客户端模块图、随页面投递执行。工装三坑如实记录并绕过：沙箱 job 收割子进程（改单调用内起服+抓取+清理）、`Start-Process` 空格路径引号、`??` 批量模块 URL 不能直接 GET（枚举本身即证据）。事后清理复核：verify-home* 零残留；`~/.dsh/profiles/web` 系 11-10 预存在（早于本会话 3 天），真实 home 零写入**有时间戳为证** | 投递层断言 ✔（HTML 含模块引用 + `html direct match mobile-comfort: True`）；隔离性四重复核 ✔ |
