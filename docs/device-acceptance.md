# 真机验收清单（M1 / M2 门禁）

> 目的：把方案里所有「待真机验证」收敛为**可打勾、可分流**的测试项。
> 每项含：动作 → 期望 → 失败分流。记录模板在文末；任何 FAIL 请附诊断包（壳：通知「导出诊断」；路线 A：`/root/logs/dsh-web.log`）。

## 0. 设备矩阵与前置

| # | 设备要求 | 用途 |
|---|---|---|
| D1 | Pixel（Android 14+，GKI 内核） | 基线 |
| D2 | 小米/Redmi（HyperOS，省电最激进） | 保活压力 |
| D3 | 三星 OneUI | WebView 主流形态 |
| D4 | Android Go / 4GB 内存 / 老 WebView | 下限 + gecko flavor 候选 |

前置（全部设备）：F-Droid 或 GitHub Releases 装 **Termux**（勿用旧商店版）；开发者选项；`adb` 可用（b2 推资产用）。
记录内核版本：`adb shell cat /proc/version`（Landlock 探针结果也体现在 dsh 启动日志）。

## 1. M1 · 路线 A（Termux + proot-distro）

| # | 动作 | 期望 | 失败分流 |
|---|---|---|---|
| A1 | `proot -0 -r / /system/bin/echo ok` | 输出 `ok` | ROM 禁 ptrace → 方案 §12-R1 备选路线 |
| A2 | `bash install.sh`（或 a1 直跑） | 预检 4 绿 → 装机完成 → 完整性验证 ✓ | 按 install.sh 契约码分流（10/11/12/13） |
| A3 | `proot-distro login ubuntu -- dsh-serve` | 日志出现 `dsh web: http://127.0.0.1:…` | 看 `/root/logs/dsh-web.log`；端口占用→改 `DSH_PORT` |
| A4 | Chrome 打开 token URL → 添加到主屏幕 | 全屏 PWA，聊天可用 | WebView 太旧 → 壳 gecko flavor 或升级系统 WebView |
| A5 | 会话内 bash 工具：`echo hi > /root/t && rg hi /root/t` | 命中 `hi` | rg 不在 → apt 已兜底；检查 npm 安装期网络 |
| A6 | persistent shell 连续两命令保持 cwd；终端面板跑 `htop` | cwd 保持；htop 渲染 | PTY 失败 → 查 `/dev/ptmx`（proot 应 bind /dev） |
| A7 | 附件图片上传 → 模型收到缩放图 | sharp 无报错 | `@img/sharp-linuxarm64` 缺失 → 重装 npm 依赖 |
| A8 | 会话搜索（历史关键词） | FTS5 命中 | node:sqlite 异常 → node 版本 <24.9？`node -v` |
| A9 | 息屏 10 分钟 + 切后台 30 分钟 | 会话在线 | 关电池优化/厂商自启白名单；见 D2 专项 §3 |
| A10 | 重启手机（装了 Termux:Boot） | 服务自恢复，URL 换新 token | boot 脚本目录权限；Termux:Boot 需打开过一次 |

## 2. M2 · 壳 APK

| # | 动作 | 期望 | 失败分流 |
|---|---|---|---|
| S1 | CI 产物 `dsh-android-<tag>.apk` 安装（允许未知来源） | 图标「DSH」出现 | `INSTALL_FAILED_OLDER_SDK` → minSdk 26 |
| S2 | 首启（无资产） | 直达引导页：三行清单全灰点 | 无引导 → MainActivity 重定向逻辑 |
| S3 | 引导页选入 `dsh-appliance-*-rootfs.tar.gz` + `SHA256SUMS.txt` + `proot-bundle.tar.gz` | 灯逐一亮；通知栏「解包中…条目数」 | 校验失败红字 → 重新下载（对比 SHA256SUMS） |
| S4 | 解包完成 | `files/runtime/runtime.stamp` 存在；通知「资产就绪」 | 空间不足 → 内核日志 `no space left` |
| S5 | 引擎启动 | 通知「DSH 引擎运行中」；WebView 自动进 GUI | `files/runtime/host-stdout.log`；proot 报错多为 `PROOT_TMP_DIR` noexec |
| S6 | GUI 内跑 A5/A6/A7 同款 | 同上 | 同上 |
| S7 | 下拉通知：导出诊断 | 分享面板弹出 zip；解开后 `url-redacted.txt` **不含完整 token** | 含 token = P0 安全缺陷，立即上报 |
| S8 | 故意装坏插件制造崩溃（GUI 内安装启动即崩的 bundle）→ 连续 3 次异常退出 | 通知「已暂存可疑插件配置（未删除任何数据）」→ 干净配置恢复启动 → 「恢复插件」按钮把 `web.disabled-*` 还原 | `rescue.json` 内容与通知一致；6 次 → 停止自动重试（救援模式） |
| S9 | 「重试」按钮 | 阶梯重置、立即重启引擎 | worker 已死场景：提示并重启监督循环 |
| S10 | 重启手机 | `BOOT_COMPLETED` → 通知出现 → 引擎回「运行中」 | ROM 杀自启：厂商白名单 |

## 3. 保活专项（D2 小米必测）

| # | 场景 | 期望 |
|---|---|---|
| K1 | 前台服务通知常驻 2h（息屏） | 引擎不掉；掉线记录 phantom-kill：`adb logcat | grep -i phantom` |
| K2 | 电池优化关闭前后对照（同场景各 30min） | 差异数据入记录表 |
| K3 | 锁屏后发起会话、5 分钟流式输出 | 流完整；wake-lock 未提前释放 |
| K4 | 低内存（D4）并行 3 子代理 | 无 OOM kill；node 堆上限生效 |

## 4. UX 插件（mobile-comfort）真机项

| # | 动作 | 期望 |
|---|---|---|
| U1 | 手机竖屏进会话 | composer 下方出现四工具片（带图标）；提示条一行可读 |
| U2 | 点「侧栏」 | 左列收起/展开即时生效 |
| U3 | 点「放大」×3、「缩小」×1 | 全局字号 visibly 变化；到界出现行内提示 |
| U4 | 切「触摸优化」 | 按下反馈色变化可感知；再开回来 |
| U5 | 横屏 / 360px 窄宽 | 工具片一行横滑不换行不挤压对话区；提示条横屏退让 |
| U6 | 系统切深/浅色 | 工具片颜色跟随（无硬编码色） |
| U7 | 桌面 Chrome（宽屏+鼠标）打开同 URL | **看不到**工具片与样式差异（桌面零感知承诺） |
| U8 | 卸载插件（profile 移除）后刷新 | 无任何残留样式/注册 |

## 5. 记录模板

```
设备:        内核:          Android:      WebView 版本:
构建: release tag/commit   appliance sha256   proot bundle sha256
项:   A1..A10 / S1..S10 / K1..K4 / U1..U8   （PASS/FAIL/SKIP + 备注）
诊断包: （失败项附 zip 路径或文件名）
```

> **诊断包会暴露设备身份**：`DiagnosticsExporter` 写出的 zip 里含设备型号、系统版本、
> 引擎 URL 与进程日志。S7 已保证引擎 token 被抹掉，但型号/系统信息仍在。
> **对外分享前先过一遍**：删掉 zip 里的 `device-info` 段、替换具体机型名、
> 检查日志里有没有你自己的路径或账号信息。贴到公开 issue 前务必自查。

**通过标准**：M1 = A1–A10 全 PASS（D1+D2）；M2 = S1–S10 + K1–K3 全 PASS（D1+D2+D3），K4/U5 允许带已知问题进 M3。
