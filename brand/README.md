# DSH Pocket — 品牌规范（Brand Spec）

> 状态：定稿 v1.0。所有资产由 [`build_brand_assets.py`](build_brand_assets.py) 生成，
> **几何是唯一事实源**：改形状只改脚本，不手改 SVG。

---

## 1. 名称

| 项 | 值 |
|---|---|
| 母体品牌 | DeepSeek Harness（DSH） |
| 产品名（英文） | **DSH Pocket** |
| 产品名（中文） | **口袋 DSH** |
| 正式全称 | DeepSeek Harness Pocket |
| 定位语（英文） | Complete agent runtime for unrooted Android |
| 定位语（中文） | 未 root Android 上的完整 Agent 运行时 |

**命名理由**

- **口袋** 直接来自项目自己的承诺（README 首句「装进口袋」）：整套 Linux + Agent 运行时装进口袋。
- **Pocket** 与既有工程事实一一对应：`proot` 模拟的 glibc 用户态、`node`/`bash` 二进制、
  官方 Web GUI 前端、subagent 与会话搜索，全部在手机本机完成，不依赖 PC、不依赖 root、不依赖服务端。
- 名字不声称与 DeepSeek 官方有隶属关系：`DSH` 只作为引擎来源标识出现（母体品牌沿用），子品牌独立。

**未采用的备选**（若日后要换名，标志与字标可复用，只需替换字标）

| 备选 | 语义 | 未采用的原因 |
|---|---|---|
| 方舟 DSH / DSH Ark | 密封的舱，载着一整套自持世界 | 中文语境与「方舟编译器 / 方舟 S-APP」撞名，检索噪声大 |
| DSH 舱 / DSH Vessel | 容器化的运行时 | 中文单字「舱」不成词，冷、难叫 |
| DSH 舵 / DSH Helm | 掌控 agent | 只表达「操控」，丢了「整套运行时都在本机」这一核心差异点 |

**禁则**

- 不使用 DeepSeek 鲸鱼标志（商标风险，且暗示官方背书）。
- 不在应用名、商店页、文档里写「官方」「DeepSeek 出品」。
- `applicationId = com.dsh.android` 恰好 15 字符是硬约束（Termux 前缀 ELF 等长补丁），**任何改名都不许碰包名**。

---

## 2. 标志（Symbol）

标志是**刻意的不对称构图**（不是渲染事故）：粗开口的 C 形环重心压向右下，开口朝向左上，
蓝色的引擎芯片嵌在开口里。

```
      ▢ ╭──────── 芯片：引擎 / 模型内核
        │         环：自持闭环（proot -> node -> dsh -> 127.0.0.1）
        │         开口朝左上 = 设备与运行时的接缝（seam）
        ╰───────
```

**语义**：厚重的 C 形环是闭环自持的运行时（终端只跟自己说话），开口是「设备与运行时之间」的接缝，
象征这套系统是被装进手机、而不是长在手机里的；嵌在开口里的芯片是引擎本身。

**几何参数**（64 单位设计网格，全部在 `build_brand_assets.py` 顶部）

| 元素 | 参数 |
|---|---|
| 环 | 圆心 (29.3, 32.9)，中心线半径 19.5，描边 4.6，圆头端帽 |
| 缺口 | 140 度到 250 度（左上方，约 110 度） |
| 核心 | 12 × 12 圆角方块，圆角半径 4.4，中心 (20.3, 23.9) |
| 光学居中 | 按墨迹质心校正到画布中心（偏差 < 0.05 单位）；包围盒占画布 61% × 68% |
| 徽章底板 | 圆角方块，圆角 = 边长 22.5%（Android adaptive 遮罩外的 Web/PWA 用） |

**使用规则**

- 最小使用尺寸 **24 px**（再小改用纯色核心方块，或只用字标）。
- 彩色版只允许两种：白环 + 强调色核心（深底）、墨色环 + 强调色核心（浅底）。
- 彩色核心不可改色为白；单色版请用 `dsh-pocket-symbol-monochrome.svg`。
- 禁止：拉伸变形、旋转、加描边、加投影、改缺口角度、改核心圆角。
- **禁止把环改回同心圆**：偏心构图就是本标志的性格所在（见 §2 开头）。

---

## 3. 字标（Wordmark）

- 自绘**单线几何大写**（cap height 100，笔画 11，平端帽），不依赖任何商业字体，无字体授权包袱。
- 单词结构：`DSH` 用 Ink，`POCKET` 用 Muted；字距 20，词距 30。
- 字标最小宽度 96 px；与标志的最小间距 = 核心方块边长（16 单位）。
- 横版锁定（左标志 + 右字标）与竖版锁定（上标志 + 下字标）见 `logo/`。

---

## 4. 色彩

| 名称 | 值 | 用途 | 占比 |
|---|---|---|---|
| Ink 墨 | `#0B1220` | 徽章底板、主文字 | 60% |
| Ink 2 深墨 | `#16233A` | 次级面板、深色分区 | 10% |
| Accent 强调 | `#38BDF8` | 核心、强调、状态 | 10% |
| Mist 雾白 | `#F4F6FA` | 页面底色 | 15% |
| Muted 灰 | `#64748B` | 次级文字、「POCKET」 | 5% |

- 深底白环 + 强调色核心，对比度远高于 4.5:1；白底用墨色环。
- 强调色只用于核心与「正在运行/活跃」状态，不用于正文。

---

## 5. 排版

- 界面字体：跟随系统（Web GUI 用上游 DSH 主题字体 Montserrat）。
- 文档与终端片段：等宽（Consolas / JetBrains Mono / ui-monospace）。
- 中英文之间留一个空格：`口袋 DSH`、`DeepSeek Harness Pocket`。

---

## 6. 资产清单

```
brand/
  build_brand_assets.py          生成器（唯一事实源，Pillow）
  logo/                          SVG 源
    dsh-pocket-symbol.svg             浅底主标
    dsh-pocket-symbol-inverse.svg     深底主标
    dsh-pocket-symbol-monochrome.svg  单色（主题图标/印刷）
    dsh-pocket-icon.svg               应用图标（墨底徽章）
    dsh-pocket-wordmark.svg           字标（浅底）
    dsh-pocket-wordmark-inverse.svg   字标（深底）
    dsh-pocket-lockup-horizontal.svg  横版锁定
    dsh-pocket-lockup-stacked.svg     竖版锁定
  icons/                         PNG 导出
    icon-16/32/48/72/96/144/180/192/512.png
    maskable-512.png              全出血方形（PWA maskable）
    circle-512.png                圆形图标
    wordmark.png / lockup-horizontal.png
    android/mipmap-*/            各密度传统 PNG（本项目 minSdk 26 用不到，留作兜底）
  preview/brand-sheet.png        品牌总览
  pwa/manifest.fragment.json     PWA 清单片段（供自定义壳使用）
```

`preview/` 下另有几张手工截取的形态示意（非脚本生成，更新时手工替换）：

| 文件 | 内容 |
|---|---|
| `device-drawer.png` | 桌面形态示意（合成渲染，图标为示意用） |
| `device-launch.png` | 等待页：引擎启动中 |
| `device-launch2.png` | GUI 运行态（**侧栏底部的账号名已涂黑脱敏**） |
| `_icon-zoom.png` | 图标放大预览 |

**重新生成**

```sh
python brand/build_brand_assets.py
```

只依赖 Pillow；DSH 自带运行时里就有（`dsh-runtimes/*/dependencies/python`）。
脚本会顺带刷新 `android-shell/app/src/main/res/` 下的自适应图标 XML。

---

## 7. 已接入的位置

| 位置 | 状态 |
|---|---|
| Android 启动器图标（adaptive icon，v26 + v33 单色层） | 已接入 |
| Android 通知栏小图标（`ic_launcher_monochrome` 单色剪影） | 已接入 |
| Android 启动器名称（默认 `DSH Pocket` / 简中 `口袋 DSH`） | 已接入 |
| 通知标题 | 已改为 `DSH Pocket` |
| PWA（Web GUI「添加到主屏幕」）图标与名称 | **未接入**：图标/名称由上游 `dsh-web-frontend` 提供，不在本仓库；自建壳时用 `pwa/manifest.fragment.json` |

---

## 8. 禁则

1. 不用鲸鱼标志、不宣称官方。
2. 不拉伸、不旋转、不加投影、不改缺口角度。
3. 不用强调色写正文。
4. 不在小于 96 px 宽度下使用字标；小于 24 px 不用主标。
5. 不改 `applicationId`（15 字符硬约束）。
