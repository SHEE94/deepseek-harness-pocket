# DSH on Android

把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 完整跑在**未 root 的 Android 手机**（arm64-v8a）上：引擎 = npm 原版 `@deepseek-ai/dsh` 零补丁，运行环境 = proot 模拟的 glibc Linux，界面 = 官方 Web GUI + 移动端 UX 适配层。

**不是阉割版聊天框**：bash/PTY/文件工具/子代理/会话搜索全量可用，装进口袋。

## 快速开始

**路线 A · Termux（今天就能用）**

```bash
# Termux（F-Droid / GitHub Releases）内：
export DSH_ANDROID_BASE=https://github.com/<owner>/<repo>/releases/download/<tag>
curl -fsSL "$DSH_ANDROID_BASE/install.sh" | bash
proot-distro login ubuntu -- bash /usr/local/bin/dsh-serve
# Chrome 打开打印的 dsh web URL → 添加到主屏幕 = 全屏 PWA
```

无发布地址时可直接下载 [`scripts/a1-termux-bootstrap.sh`](scripts/a1-termux-bootstrap.sh) 传到手机执行（离线可用，幂等）。

**路线 B · 壳 APK（独立系统形态）**

下载 Release 里的 `dsh-android-<tag>.apk`（侧载）；首启引导页用系统文件选择器导入
`dsh-appliance-<tag>-rootfs.tar.gz` + `SHA256SUMS.txt` + `proot-bundle.tar.gz`，无需命令行。

## 仓库地图

| 路径 | 内容 |
|---|---|
| [DSH-Android-移植方案.md](DSH-Android-移植方案.md) | 完整方案（依赖审计、平台硬约束、安全模型、里程碑）+ §14 实现进度登记 |
| [scripts/](scripts/README.md) | `install.sh` 一键安装器 · b0 拉资产 · a1/a2 装机与守护 · b1 appliance 镜像 · b2 推资产 · b3 proot 供应 · seed · dev/ 测试件 |
| [mobile-comfort/](mobile-comfort) | 移动端 UX 插件（composer 下工具片 + 触摸人体工学样式层；有头无浏览器行为测试） |
| [android-shell/](android-shell/README.md) | 壳 APK 工程（前台服务托管、资产管线、救援阶梯、诊断导出、无 adb 引导） |
| [docs/](docs) | [真机验收清单](docs/device-acceptance.md) · [设备实测档案与踩坑录](docs/device-field-notes.md) · [proot 构建配方报告](docs/proot-build-recipe-report.md) · [首推清单](docs/first-push.md) |
| [docs/images/](docs/images) | 已脱敏的验收证据截图 |
| [.github/workflows/](.github/workflows) | `build.yml`（含 nightly 金丝雀）· `release.yml`（tag → 全产物发布） |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 贡献指南：硬约束、改动须知、测试要求、隐私红线 |

## 三条平台硬约束（改前必读）

1. **targetSdk ≤ 28**：API 29+ 禁止 execve() 应用私有目录（node/bash 全灭）→ 只能侧载，无法上架 Google Play
2. **`com.dsh.android` 恰好 15 字符**：Termux 前缀 ELF 等长补丁约束（保留兼容）
3. **仅 arm64-v8a**；Landlock 文件沙箱在 Android 内核不可用 → 安全模型是「Android 应用沙箱 + DSH 审批流」（方案 §7）

## 质量与验证策略

- 上游破坏性变更：nightly 金丝雀构建 + 镜像内 `--dump-config` 激活断言（漂移 = 构建红）
- 解析器/插件：JS 等价副本双测 + 无浏览器行为套件（CI 常开）
- Kotlin/镜像：CI 编译与容器冒烟为最终门
- 已知边界如实登记在方案 §12 风险册与 §14 每行的「验证状态」列——已通过项才打勾

## 隐私说明

本仓库公开，实测记录里的**技术结论全部保留**，但**设备身份信息已脱敏**：

- 真实机型名、adb guid、serial 前缀 → 统一替换为「测试平板 A / 测试手机 B」
- 局域网 IP、路由器网段分配 → 不入库
- 维护者本机的绝对路径、用户名 → 改为仓库相对路径与环境变量
- 真机截图、logcat dump、profile 备份 → 全部留在 gitignored 的 `emu-assets/`，仓库只保留 `docs/images/` 里人工筛过的脱敏证据图
- 诊断导出包（`DiagnosticsExporter` 产出的 zip）含设备型号与日志，**贴到公开 issue 前请先自查脱敏**（见 [验收清单 §5](docs/device-acceptance.md)）

新增内容请遵守 [CONTRIBUTING.md](CONTRIBUTING.md) 的隐私红线。

## 许可与来源

DSH 引擎 MIT（deepseek-ai/deepseek-harness）；本仓库产物 MIT（见 [LICENSE](LICENSE)）；
第三方组件与各自许可见 [NOTICE.md](NOTICE.md)；proot 供应线复用 Termux 官方包（构建配方见
[docs/proot-build-recipe-report.md](docs/proot-build-recipe-report.md)）；架构先例参考
[kai6666666/deepseek-harness](https://github.com/kai6666666/deepseek-harness)（MIT）。

「DeepSeek」及相关标识归其权利人所有；本项目为非官方社区移植。
