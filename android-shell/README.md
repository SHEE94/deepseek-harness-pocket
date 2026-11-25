# android-shell — DSH 独立系统壳 APK（M2 骨架）

路线 B′ 的 Android 工程骨架：前台服务托管 proot→node→`dsh --profile web`，
WebView 加载 loopback 上的官方 Web GUI。设计依据见上级
《[DSH-Android-移植方案.md](../DSH-Android-移植方案.md)》§5-B′ 与 §3。

## 现状（skeleton，未编译验证）

| 组件 | 文件 | 状态 |
|---|---|---|
| 引擎托管 + 保活 + 重启退避 + 救援阶梯 | `app/src/main/java/com/dsh/android/DshEngineService.kt`、`RescueLadder.kt` | 完整（未编译） |
| 资产管线（SHA-256/版本戳/原子换树/tar 解析） | `AssetInstaller.kt`、`TarExtractor.kt` | 解析器分支有 JS 等价副本双测 |
| 诊断导出 + 分享 | `DiagnosticsExporter.kt`、`DiagShareActivity.kt`、`res/xml/file_paths.xml`、Manifest FileProvider | NoDisplay hop 同步导出→系统分享面板 |
| WebView 容器 + origin 围栏 + 等待页 | `MainActivity.kt` | 可用；gecko flavor 未建 |
| 无 adb 资产引导（首启） | `SetupActivity.kt` | SAF 三入口（rootfs/校验清单/proot）+ 实时清单点 + `.part` 原子落位 + 就绪自动跳 GUI；**页内 JS 已过 mock-DOM 行为测试**（解析、亮灯、MB 标注、就绪跳转、状态播报） |
| 开机自启 | `BootReceiver.kt`（startForegroundService）+ Manifest `BOOT_COMPLETED` | 已接 |
| 明文流量白名单（仅 127.0.0.1/localhost） | `res/xml/network_security_config.xml` | 已接 |
| 应用身份（图标 / 名称） | `res/mipmap-anydpi-v26|v33`（adaptive icon + 单色层）、`res/values/strings.xml`（`DSH Pocket`，无本地化译名） | 已接（规范见 [../brand/README.md](../brand/README.md)） |

## 三条硬约束（改动前必读）

1. **`targetSdk = 28` 不可升级**——API≥29 禁止 execve() 应用私有目录（node/bash 全灭）。
2. **`applicationId = com.dsh.android`（恰好 15 字符）**——Termux 前缀 ELF 等长补丁所需；
   纯 proot 路线若不碰补丁可放宽，但保持兼容不亏。
3. **仅 arm64-v8a**；无法上架 Google Play，走 Releases 侧载 + SHA-256。

## 构建

需要 JDK 17 + Android SDK（compileSdk 35）。本仓库无 gradle wrapper：

```sh
gradle wrapper --gradle-version 8.13   # 首次
./gradlew :app:assembleDebug
```

## 资产管线

首启由 `AssetInstaller` 从 app 专属外部收件目录解包（校验 SHA-256、原子换树、写
`files/runtime/runtime.stamp` 版本戳；换资产必须换文件名或先删戳——版本戳纪律防静默失配）：

```
/sdcard/Android/data/com.dsh.android/files/incoming/
  dsh-appliance-<ver>-rootfs.tar.gz   ← scripts/b1-build-appliance.sh 产物
  SHA256SUMS.txt                      ← b1 产物（或 b2 现场生成）
  proot                               ← 静态 aarch64 proot（来源见下）
```

推送一步到位：`./scripts/b2-stage-shell-assets.ps1 -Proot <path-to-proot>`。

proot 运行件（现行主路径 = CI 自动产出）：

```
scripts/b3-fetch-proot.sh   # 从 Termux 官方 apt 仓库可验证重打包（Packages 索引 SHA256 强校验
                            # + arm64 原生冒烟），产物 proot-bundle.tar.gz = proot + loader + libtalloc
```

壳运行契约（enter.c 原文推导，见 [docs/proot-build-recipe-report.md](../docs/proot-build-recipe-report.md) §E）：
exec proot 时注入 `PROOT_TMP_DIR=<files/runtime/tmp>`（必须可执行）、`PROOT_LOADER`、
`LD_LIBRARY_PATH`（三者在 `DshEngineService` 中「存在才设」）。
NDK 自编译配方（8 项未证实，实验用）同报告 §D。

## 下一步清单

- [x] 资产 staging + SHA-256 校验 + 版本戳 + 原子换树（`AssetInstaller`/`TarExtractor`，解析器有 JS 等价副本双测）
- [x] 救援阶梯：两级（连续早退→搁置 web profile→HALT 停自动重试；稳定运行重置计数；`rescue.json` 持久化、可逆、零删除）
- [x] 诊断导出：通知按钮一键出 zip（设备/kernel/空间、版本戳、阶梯状态、双日志 tail、收件清单；**URL token 导出前脱敏**）
- [x] BOOT/入口统一 `startForegroundService`（targetSdk 28 仍受 O 后台启动限制）
- [x] FileProvider 分享诊断包（`exports/` 单独授权路径；通知「导出诊断」→ NoDisplay hop → 系统分享面板）
- [ ] Gradle 真机构建通过 + 首启解包实测（**编译通过性仍未证**；已修 1 处静态评审确证的编译错误 + NoDisplay 同步化 + startForegroundService）
- [ ] gecko flavor（GeckoView，低版本 WebView 设备兜底）
- [x] 移动端插件 `mobile-comfort` 随包预置（appliance 镜像构建期 `dsh plugin --profile web add` 原生激活 + `seed-mobile-comfort.mjs` 兜底 + `--dump-config` 断言，见 scripts/Dockerfile.appliance）
