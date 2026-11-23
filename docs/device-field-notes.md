# Device Field Notes — 设备实测档案与踩坑录

> 目的：下次会话直接读本文件选设备、避坑。所有条目均来自真机/真环境实测（带证据），
> 未验证的推测一律标注 (未验证)。最近更新：2026-11-17。

## 一、设备矩阵（选谁干什么）

| 设备 | 身份 | 系统/ABI | 适用 | 关键注意 |
|---|---|---|---|---|
| **测试平板 A** | adb guid / serial 前缀（已隐去） | Android 16 / SDK 36 / **arm64-v8a**，kernel 6.1.157 GKI | **路线 B 全链首选**：S1-S5 + 引擎实跑 + Landlock 探针（GKI 6.1 可能有 `/sys/kernel/security/landlock`，未实测） | 无线调试**息屏/离开设置页即掉线**；port 漂移重连即可（tls 配对密钥已持久化，guid 相同免再配对）；DHCP 段会变（同段/跨段互斥） |
| **测试手机 B** | adb guid（已隐去） | Android 16 / SDK 36 / **arm64-v8a** | 路线 B 备用 + 路线 A（Termux）候选，push 17MB/s | **定制系统 `INSTALL_FAILED_USER_RESTRICTED`**：开发者选项须开「USB 安装」并过弹框；用 `_adb._tcp:5555` legacy 直连（`adb connect ip:5555`），不走 pair |
| **官方 AVD `wd36`** | emulator-5554 | SDK 36 / **x86_64** | **只用于 ABI 无关管线**（解包/引导/等待页/UI 迭代），push 快(5-50MB/s)，`run-as` 全开 | 本机资源紧张时会进 system_server watchdog 循环（trace 里 31 个 GOODBYE）；-wipe-data + 释放内存后可恢复；**引擎 ABI 门会正确拒绝 x86（设计行为，不是 bug）** |
| **LDPlayer 9** | 127.0.0.1:5555 | Android 9 x86_64 | **不可用，放弃** | Hyper-V 主机上 VBox 嵌套虚拟化 → 磁盘 32-64KB/s、system_server 连环 ANR、`input` Broken pipe、adb 1.0.31 无 exec-out/pair |

> 设备身份一律用占位名登记：真实机型、adb guid、serial 前缀与局域网地址不进公开仓库。
> 下文「测试平板 A」「测试手机 B」按此约定指代。

**网络拓扑坑**：路由器把 2.4G/5G 切成两个不同网段，**跨段 mDNS 可见但 TCP 全被拦**（adb connect/pair 超时、`protocol fault`）。设备与电脑必须同段。诊断：`Get-NetIPAddress` 看本机段 + `Test-NetConnection ip -Port p`。

## 二、踩坑录（每条都是真实事故 → 修复 → 守卫）

### Tar 解包器三连（TarExtractor.kt，真机取证定案）
1. **填充跳变**：读完 payload 后用 `skipBytes(BLOCK - size%BLOCK)` 跳填充，而 skipBytes 内部**再次向上取整到整块** → pax 头 size=60 时把 452 跳成 512，**错位 60 字节，整条流从第 2 块起全崩**（幻影成员 name 空 type 0x00）。修：新增 `skipExact`/`padAfter`。守卫用例：`after-odd.txt`（非 512 对齐文件后接成员）。
2. **Art GZIPInputStream 零字节读**：`read(buf,..)` **和**尾部 `read()` 都可能返回 0 而非 -1（libcore 怪癖，桌面 JVM 不复现）→ 读循环无限自旋（线程 state=R wchan=0，**AVD 两次不同深度的死锁**）。修：所有循环单字节回退 + 连续零单字节 1MiB cap（合法零块 ≤1KB trailer 不误伤）。
3. **安全级**：`File.canonicalFile` 会**真实解析符号链接**，成员解析到目标根时 `f != dest` 豁免放行 → 以文件打开目录（EISDIR）/symlink traversal。修：严格包含，仅 `./` 目录成员豁免。副本(JS)用 `path.resolve` 不解析符号链接——**parity 差异本身曾是漏洞**，守卫用例双侧同步。

### 资产构建（无 Docker Windows 路线，已沉淀脚本）
- `scripts/b1b-build-rootfs-win.py`：ubuntu-base.tar.gz + node.tar.xz + overlay **二进制拼接**（tar 拼接=POSIX overlay 语义，**零解包**保 symlink/mode——Windows 上解 Linux 树必毁符号链接）→ 单 gzip。
- `scripts/b1c-normalize-rootfs.py`：拼接流 → **规范化单归档**（去重 last-wins、单一 EOF trailer、**尾部不要写 10240 padding**——多段零块正是设备端解包器最少处理的一种结构）。
- Termux deb 提取：bsdtar 能开 ar（deb）但**无 xz 滤镜**（`unable to run program "xz"`）→ 内层 `data.tar.xz` 用捆绑 Python tarfile。
- **deb 里有 `usr/libexec/proot/loader`** → Termux proot 是 unbundled 构建（PROOT_UNBUNDLE_LOADER），`PROOT_LOADER` env 覆盖路线有事实依据。
- ubuntu-base 版本：24.04.2 已 404，**用 24.04.5**；下载源 cdimage 目录页可列出现存版本。
- 推送前后用**设备端 `sha256sum`** 对 `SHA256SUMS.txt`（防 FUSE/push 损坏假象）。

### 设备侧操作
- **pwsh `>` 重定向毁二进制**（`exec-out screencap -p > x.png` 幸好用 platform-tools 新 adb + 原生重定向 OK；`shell cat file > local` 会把文件写坏导致"资产损坏"假象——真机上 sha 才是准的）。
- 平板截图 ~250KB PNG；`run-as <pkg>`（debuggable）是黑盒取证主力：`ls/du/cat /proc/<pid>/task/*`。
- `dumpsys notification` 的 grep 轮盘效率极低；**中文异常文本在 logcat grep 不可靠**（用英文 tag `dsh-stage`/`dsh-loop`）。
- 复杂 sh 循环内嵌 pwsh 会被引号地狱拆碎（`\$(basename …)` 本机执行）→ **写成 .sh 推到 /data/local/tmp 再跑**。
- **worker 线程必须命名**（默认 `Thread-N` 在 task/comm 里隐身，白白多考古数轮）。
- stage 异常要在 catch 处 `Log.e` + 消息带上下文（`after-members=` 一个字段直接锁定"第一个块就错位"）。
- AVD 首次启动 dex2oat 满载 → Service 冷启动超 FGS 20s 班車（`ForegroundServiceDidNotStartInTime`）——第二次启动即好，环境性。

### 主机工具链事实
- 网络全通（JVM/curl.exe；pwsh Invoke-WebRequest 曾 SSL 失败——**用 curl.exe**）。npmmirror / packages.termux.dev / cdimage.ubuntu.com ✔。
- gradle：`<GRADLE_HOME>/bin/gradle.bat`（wrapper 或独立安装皆可），`JAVA_HOME` 指向 JDK 21，`GRADLE_USER_HOME` 自定，Android SDK 路径写在 `android-shell/local.properties` 的 `sdk.dir`。
- 新 adb：`<ANDROID_SDK>/platform-tools/adb.exe`（1.0.41，支持 pair/exec-out/mdns）。
- node 任务：`ELECTRON_RUN_AS_NODE=1` + `$env:DSH_NODE_EXECUTABLE`（长 `-e` 脚本用 ProcessStartInfo.ArgumentList，别拼命令行）。
- npm 深路径 Windows 删除：`cmd /c rd /s /q "\\?\full\path"`（Remove-Item 会在 picomatch 层失败）。

### 真机全链贯通日（2026-11-18，平板实测）新增坑录
1. **`readBlock` EOF 语义反转是"尾部死锁"唯一真凶**：`return off == 0` 在干净 EOF 返回 **true** → 主循环拿旧 buf 无限自旋（AVD 与平板两处"卡 354804KB/R 态空转"全为此）。**EOF 永远 false**。修后解包 307MB→1.3s。
2. **Android `rename(2)` EXDEV 静默浅拷贝**：app 域 /data 混合挂载（proot --bind 制造 FUSE 绑定）后 `File.renameTo(目录)` **返回 true 但只做浅拷贝，顶层符号链接全丢**（bin/lib/sbin 蒸发 → guest `#!/bin/bash` not found）。**结论：rootfs 就地解包（in-place），tmp+rename 模式在 Android 上是陷阱**。
3. **proot `--bind=/dev` 会在 rootfs 建 `d---------` 目录**——事后 ls 会吓一跳，属正常。
4. **GNU 'L' 长名成员不可拆对**：任何"按名字去重/重排 tar"的工具（b1c 已废）会把 `L`↔body 拆散 → 解出 100 字节截断文件名（`index.j`）。**保持拼接流字节序**，解包器已证明能正确读拼接归档（concat 测试）。
5. **Android 无 `/etc/resolv.conf` 可 bind**（DNS 走 netd）：guest DNS 要**写进 rootfs/etc/resolv.conf**（stage 注入 8.8.8.8/1.1.1.1）。
6. **Android 16 bionic 其实认 LD_LIBRARY_PATH**（早前"被忽略"是 pwsh 内联 `$PWD` 转义失败的假象）——但正式方案仍是 **b1d 就地改写 DT_RUNPATH 为 `/data/data/<pkg>/lib`**（34B→30B 定长替换；**bytearray 切片赋值会扩容**，35 写进 34 槽 = 整个 ELF 右移一字节损坏，"empty/missing DT_HASH"就是这个事故）。
7. **Termux proot 依赖闭包 = proot + loader + libtalloc + libandroid-shmem**（少 shmem 报 CANNOT LINK 逐个暴露）。bundle 必须带全部 `lib/*.so*`，stage 泛化落位 dataDir/lib/。
8. **设备端 guest npm install 撞 proot ptrace 并发 fork 竞态**（tail/npm 随机 not found，limits 充裕）：**npm 预装在 PC 交叉完成**：`npm install --os=linux --cpu=arm64 --libc=glibc`（libc 必需否则 notsup）+ 平台子包（`node-addon-require-builtin-linux-arm64-gnu` 等 optionalDeps npm 不给装就手工下 tarball 解入）+ **`--ignore-scripts`** → b1b 把 preload 层直接拼进 rootfs tar（1.13GB，设备 12s 推完）——**appliance 预载形态，零设备端 npm**。
9. **等待页→WebView 的就绪信号链**：a2 必须 `tee` 双写（引擎 stdout→hostLog pump 抓 `dsh web:` 行写 engine-url.txt；guest 日志保留）。redirect 不进文件的脚本 = URL_FILE 永空。
10. **stage 资产自检要全在前**：bundle 校验置于 rootfs 解包前， sums 不同步（推送漏文件）会反复 wipe 好树（曾两次清掉 708MB 烘焙）。**部署=bundle+rootfs+sums 三件套同一轮**。
11. 息屏期截图全黑/AOD 半黑——先 `dumpsys power | grep mWakefulness` + `dumpsys window | grep mCurrentFocus` 判前台，再 WAKEUP 后截。
12. **b1d/b1b 顺序陷阱**：b1b 每次从 px 重拷 proot 覆盖补丁 → 补丁必须**在 b1b 内联执行**（runpy）+ **打包后读回终验**（`packed[7080:7110]` 断言）。

13. **`dsh plugin add` 内部调 pnpm**（guest 必须有 pnpm 在 PATH，否则 `spawn pnpm ENOENT`）。pnpm 12 也是平台分包（npm 交叉装要 `--os=linux --cpu=arm64 --libc=glibc`），最省事：**直下 tgz 解入 node_modules + bin 链 `../node_modules/pnpm/pnpm`（根文件非 bin/ 子目录）**。已进 preload 层（下次 b2 重打自动带上——本次只增量推了设备）。
14. 真机 UI 验收（用户实测确认"基本可用"）：侧栏收起/字号±/触摸优化 toast/权限模式菜单（仅可查看/工作区内修改/完全权限）/模型选择器均正常；**菜单弹层会盖住 composer 输入区**（点 textarea 先命中菜单触发器，ESC 关闭后焦点正常）——后续可给 mobile-comfort 状态条让位优化。

### 文件映射（工程目录 ↔ 真实环境）实测结论
1. **FUSE 挂载是 per-mount-namespace + per-uid 双重门**：`run-as` 拿得到 app uid 但身处 **shell mountns**，`/storage/emulated/0` 永远 Permission denied——**共享存储映射只能在 app 进程真实 spawn 的 proot 里测**，run-as 复现一律假阴性（差点误判方案失败）。正确验证通道=引擎内自检（dsh-serve 启动 maptest 写 dsh-web.log）。
2. **`--change-id=0:0` 与共享存储不可共存**：换 uid=0 即失去 FUSE 视图（`can't sanitize binding /sdcard` 根因）。**app uid 直接跑 guest**：rootfs 文件本就是 app uid 解出来的，guest root 是装扮的，DSH/node/npm 均无真 root 需求——去掉后一切照常 + `/sdcard` 双向读写通过。
3. **appops 双层**：`MANAGE_EXTERNAL_STORAGE` 有 **Uid mode 与 package 两行**，MediaProvider/FUSE 看 **package 行**；`appops set <pkg> <op> 1` 只改 uid 行=无效假象。正确命令 `cmd appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`（真机 UI=设置→所有文件访问）。**重装 APK 会重置授权**，测试脚本每次 set。
4. **授权翻转要重启引擎**（无广播可收）：服务内 5s 轮询 `isExternalStorageManager()` 翻转即 destroy proot → ladder 重 spawn 带新 binds（`dsh-storage-watcher` 线程）。
5. **L2 目录别建错**：文件管理器可达的是 **`getExternalFilesDir("projects")`**（=/storage/emulated/0/Android/data/<pkg>/files/projects）；`runtimeDir/projects` 是内部目录，外部不可见（曾建错，PROJ 测试照样绿——**绿要绿在对的地方**）。`adb shell ls Android/data/` 被 LSFS 拦属正常，不代表 app/文件管理器不可用。
6. FUSE 交换区**不支持符号链接/chmod**：git 仓库（.git 含 link）勿直接 clone 到 /projects——README.txt 已写明「交换区编辑、workspace 运行」。

### 工作区 ↔ 物理目录（2026-19 上午实测闭环）
1. **DSH 工作区注册表 realpath 校验拒绝符号链接**（home 里放 `sdcard -> /sdcard` 后侧栏 dsh-workspace 消失/选择器 EACCES）——正解=**home 下建真目录，把 Android 路径 bind 进去**：`--bind=<ext>/projects:/root/projects`、`--bind=/storage/emulated/0:/root/sdcard`（canonical path 恒在 home 内，注册表/选择器/会话全兼容）。
2. **workspace.json 可直接播种**（schema v2：`global.workspaceIds` + `tables.workspaces.<id>={workspaceId,path,title,sessionIds,createdAt,updatedAt}`）——stage 时写入，侧栏即出现「共享存储/工程交换区」，触屏用户无需碰 hover 型目录选择器。
3. **官方 GUI 桌面版选择器/下拉依赖 hover**：composer「选择工作区」chip 触屏无响应是 DSH 桌面 UI 在 touch 下的真实缺口（非移植 bug）——短期靠 sidebar 工作区点选（点行=选中并建会话 ✔），长期归 mobile-comfort hover→tap 适配范畴。
4. **adb 注入文本**：`input text` **不解码 %20**（要 `%20` 先经 URL-decode 的 shell）；用 `input text 'a'; input text ' '; input text 'b'` 分段打空格。textarea 焦点 tap 要打在文字行上，chip 行 tap 会误切权限模式。
5. **app 内更新 DSH 链路（已 E2E）**：`dsh-update-*.tar(.gz)` 丢进 incoming → `applyUpdateIfAny`（sha 校验 sums 可选行 + 原生 TarExtractor 解进 rootfs + `update.stamp` 防重放 + 读 package.json 报版本）→ watcher 5s 轮询发现 → `proc.destroy()` 下轮 spawn 新版。b2 自动产出 `dsh-update-<ver>.tar.gz`。**full-stage 顺带消费 update 包**（stamp 连写）。guest 内 npm/pnpm 更新路径正式废弃（ptrace fork 竞态不可靠）。

### 产品行为备忘（勿误判为 bug）- x86 设备通知「引擎需要 arm64 设备（本机 …）— 验证请走路线 A」= ABI 门设计行为，路由 30s 慢重试而非救援阶梯。
- 救援阶梯 halted 时 worker 每 60s 醒一次重读阶梯；ACTION_RETRY 唤醒并复位。
- 主界面=DSH 官方 web（WebView 只是浏览器）；壳内仅引导/等待/通知三页胶水 + mobile-comfort 官方插件。

## 三、验收进度快照（2026-18 傍晚）
- **S1-S5 全链贯通（测试平板真机）**：安装✅ 引导✅ 资产✅ 解包✅（1.13GB 预载 rootfs，1.3s 级流解）→ **DSH 官方 GUI 上屏**（`proot→node24→dsh web→3080→WebView`，证据 [`images/gui-on-device.png`](images/gui-on-device.png)）
- 产物形态定型：**b2-build-all.ps1 一键** = b1b（拼接 base+node+overlay+**preload 724 包**，内联 b1d 补丁+终验）→ 非压缩 rootfs.tar + bundle + sums；设备推送 12s
- 回归基线：5 套件 31 组全绿
- 待做：U1-U8 功能验收（工作区/会话/插件页/mobile-comfort 工具片/息屏恢复/救援阶梯演练）；CI 工作流同步预载形态；x86 路线 A（Termux）补测

## 引擎 npm 更新日（2026-11-19 晚 · link2symlink 血泪）

17. **`--link2symlink` 在 /data（ext4/f2fs，原生硬链接）上是纯负债**。它把 guest 硬链文件**永久重写**成指向 `$PROOT_TMP_DIR/.l2s.*` 的符号链接，正常退出才还原；任何 `kill -9`（force-stop/rescue/manual reap）都会把 rootfs 留成**悬空链接地雷阵**。pnpm store 全硬链 → 更新后的 dsh 树必踩。修复 = 引擎 spawn/guestExec 全部去掉该 flag。**症状识别**：`dsh --version` 一度能跑后来必死 + bin.js 是 `-> …/.l2s.<hash>` 链接 + purge tmp 后全线 MODULE_NOT_FOUND。
18. **pnpm global 三连坑**：`--prefix` 是 npm 语法，pnpm 忽略并触发 `ERR_PNPM_GLOBAL_BIN_DIR_NOT_IN_PATH` 守卫；`--node-linker` 对 global **无效**（v11 content-addressed links 结构固定）；重装同版本**复用 store 不换 shim**（cmd-shim-target 指旧 hash）。设备上装 CLI 的可靠姿势 = **项目级 `pnpm add --node-linker=hoisted` 自足目录 + 手写 shim**（真实目录树，零链接魔法）。
19. **guest 更新链验收标准必须是 "serve 起来 LISTEN"，不是 `--version` 通过**——l2s 残留下 --version 与 web 启动的成败时刻不同，用 --version 判存活会误判。
20. 检查更新 UX：pnpm view 官方 registry 约 3-40s；结果必须走 `updateNotification → lastStatus`（顶栏+通知同源）；时间闸 2s 只挡连发，最终结果行必达。

## 结案：storage bind 事故（2026-11-20 全天取证 → 同步桥定案）

## 最小化回来界面重载（2026-11-23 下午定案：Activity 重建 + 快路径恢复）

## 后台存活（2026-11-23 定案：定制系统杀组 vs 卡片锁定）

> 本节现象出自某厂商定制系统（定制 launcher + 定制内存清理器）。为免泄露测试机指纹，
> 全文只描述行为，不记品牌与机型。

- **现象定位**：定制系统在 app 后台时销毁 Activity（`wm_restart_activity` + `wm_on_create_called`），进程和引擎不重启——用户看到的"重启 DSH"其实是 **Activity 重建 -> WebView 重新 loadUrl -> GUI 全页重载**（白屏几秒、可能丢输入框草稿）。adb 复现闭环：home 15s -> 重进 -> events buffer 显示 create 而非 restart。
- **快路径方案**（已实现，MainActivity）：onPause 把当前 URL 存 prefs `last_gui_url`；onCreate 的 loadEngine 优先用它（cookie 会话仍有效 → 直接回到原页面），否则退回 engine-url。**关键守卫**：只存引擎源 URL（127.0.0.1/localhost）——否则重建瞬间的 `about:blank` 会把好 URL 覆盖掉（第一版踩过，验证时 prefs 被写成 about:blank）。
- **实测**：修复后两轮 home→重进循环，prefs 稳定保留 `http://127.0.0.1:3080/…`，引擎日志零新增 "starting dsh"，LISTEN=1 持续。
- 局限：GUI 页内 JS 状态（滚动位置、composer 草稿）不跨 Activity 重建——那要 onSaveInstanceState + WebView 状态包，收益低；会话数据全在引擎侧，不受影响。
- **升级修复（同日下午）**：URL 快路径仍有 1-2s 白屏（重建后 WebView 必须重新渲染整页）。加上 WebView 状态序列化：`onSaveInstanceState` 里 `web.saveState(outState)`，onCreate 里 `restoreState(savedInstanceState)` 命中则**完全跳过 loadEngine**——恢复的是整条 back-forward 列表 + 渲染上下文，重进不重载。restoreState 返回 WebBackForwardList（非 Boolean），判空处理。定制系统表现待用户确认：理论上重进无白屏、无加载画面。
- **终极根因（14:07 取证闭环）**：用户从**桌面图标**重进 -> 定制 launcher 发 `FLAG_ACTIVITY_CLEAR_TOP` 语义 intent -> standard launchMode 下旧 Activity **finish-destroy**（`wm_destroy_activity finish-imm` + `wm_on_destroy_called`）-> 新 Activity 全新走 onCreate -> WebView 全页重载。**进程/引擎全程不死**（同 pid），纯 Activity 层销毁。
- **修复**：MainActivity 加 `android:launchMode="singleTask"` → 图标重进路由到已存在实例（onNewIntent），events 铁证：`wm_on_restart_called` 无 create/destroy。**三层修复叠加后所有重进路径（HOME/多任务/图标/切app）均为 restart+resume，零重载**。
- 教训：复现用户路径要用**用户的入口方式**——`am start -n` 直启动与桌面图标 intent 语义不同（clear-top vs new-task），同一 app 两条生命周期分支。
- **等待页卡死竞态（14:17 现场抓到）**：install -r 重启进程后，Activity onCreate 读到**旧引擎的 URL 文件**（旧 token）→ loadUrl 死链接 → 停在等待页；poller 读到过非空 URL 就退出，**没有二次校验**。修复：poller 记录自身启动时间，只采用 mtime 距今 ≤30s 的"新鲜"URL 文件；陈旧 URL 视同无引擎继续轮询。与之前的"删除 URL 文件再 spawn"措施互补（那招只保 deploy 场景，保不住"引擎自愈重启但 URL 未更新"的窗口）。
- 同窗口还暴露：等待页"正在确认引擎状态…"文案对应的 poller 是 Activity 级线程，Activity 销毁（waitActive=false）后旧 poller 死、新 poller 由新 onCreate 再起——竞态链是**旧 URL 文件 mtime 从未更新**导致的单点误判。
- force-stop/重装仍会全量重启引擎（预期）；定制系统「全部清除」仍是进程组级杀（见后台存活章节，锁卡兜底）。

- **事实**：定制系统的多任务「全部清除」会 SIGKILL **整个进程组**——包括前台服务（FGS）、onTaskRemoved 回调、START_STICKY 重启**全部失效**（am_kill 事件可见定制内存清理器进程名）；`stopWithTask=false`、deviceidle 白名单、电池豁免（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 非 changeable 类型无法 grant）统统挡不住。
- **真正有效的用户动作**：多任务界面点 **DSH 卡片右上角锁图标**（锁定 = 清理豁免，与 Podroid/ServerBox 同款机制）。实测锁定后「全部清除」进程存活、引擎 LISTEN 持续。
- **代码侧双兜底**（已实现，onTaskRemoved 自重启 + 3s AlarmManager 兜底重kick；`stopWithTask=false`）在定制系统下只能覆盖"单任务上滑"等温和清理；"全部清除"只能靠锁卡。**用户引导**：首启等待页/顶栏提示加一句"多任务里锁定 DSH 卡片防清理"。
- adb 复现路径：`KEYCODE_APP_SWITCH` → 截图找 DSH 卡片锁坐标（锁≈标题右侧，卡片 X 偏移 +500，y≈343）→ tap 锁 → tap 全部清除 → `pidof` 验证存活。
- 事故记录：本轮删 MainActivity 的 onTaskRemoved 误切掉类头/onCreate/imports（文件 358→272 行），靠 grep 结构断点逐段重建复原（教训：**无 git 状态下不做跨块删除；删除前先 dump 全文结构**）。

- **真根因**：proot 的 fuse-bind 在 A16 定制系统上**静默失效**（bind 源 open(O_PATH) 被 SELinux 拒 → warn 后跳过 bind，目标点保留 rootfs 原内容）。历史"WRITEBACK-OK/SDCARD-OK"全是**假阳性**（ls 列表相同因为 bind 失效时目标点恰是 rootfs 目录本身）。
- **铁证三件**：① guest 内 `stat /root/sdcard` Device=254,61（=rootfs 的 f2fs）而非 fuse 的 77；② 运行中 proot 的 fd 表无任何 fuse/storage 打开；③ INTERNAL-MARK 对照实验（ext4 源 bind 正常、fuse 源必死）。proot 5.1.107 与 .102 双版本一致；`/mnt/user`、`/mnt/pass_through`、`/data/media`、`/storage/emulated` 四候选路径全试全败；与 kill -9、link2symlink、appops 均无关（逐一 A/B 排除）。
- **教训**：验证 bind 生效必须 `stat -c %D` 设备号或 host↔guest 落点交叉取证；"ls 相同"是假阳性重灾区。
- **定案架构（22 条）**：存储同步桥——host 侧双向 mtime 差量同步（10s 周期，`DshEngineService.startStorageBridge/syncDir`）：guest 树（rootfs 内 ext4，快）⇄ host 侧（app external projects + FUSE 共享存储）。实测 host→guest ≤25s、guest→host ≤35s，双向可用。spawn 侧同时保留：目标目录兜底 mkdir（fresh stage 由旧 APK 完成时防"No such file"）+ awaitFuseView 启动门。
- **pm clear 代价（23 条）**：profile/API key + Android/data 全清；**先备份再 clear**（铁律，备份产物别留在仓库里）；rootfs 三件套重推。
- 设备（24 条）：重启后无线调试自动关，重开需手动 + 新配对码 + mdns 取新端口（端口每次漂移，脚本里别硬写）。
