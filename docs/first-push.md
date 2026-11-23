# 首次 push 检查单（GitHub 首跑前 10 分钟）

> 目标：让 `build.yml` 首跑就把三块硬石头（Kotlin 编译 / arm64 镜像 / proot 取包）砸出第一份证据。

## 1. 建仓库

- 建议名 `dsh-android`，可见性二选一：
  - **公开**：Actions 免费额度（Linux/ARM runner 公开仓库免费）、Release 任何人可下、`install.sh` 的 raw URL 免认证。推荐。
  - **私有**：Actions 需计费额度；且 **Release 资产的 `curl` 直链在私有仓库需要 token**（`Authorization: token` 头）——install.sh 场景要么公开仓库、要么改用 `gh release download`。这是私有化的最大摩擦点。
- 把本工作区作为仓库根 push（README/CI 已就位）：
  ```bash
  git init -b main && git add -A && git commit -m "DSH on Android: plan+scripts+shell+ux+ci"
  git remote add origin https://github.com/<owner>/dsh-android.git && git push -u origin main
  ```
  注：`.gitignore` 已就位（见「6」），`dist/`、`.gradle/`、`local.properties`、`emu-assets/` 都不会进索引。

## 2. 仓库设置（Settings 页）

| 项 | 值 | 原因 |
|---|---|---|
| Actions → General → Workflow permissions | **Read and write permissions** | `release.yml` 的 publish 需要 contents:write（workflow 已声明 permissions，组织级策略可能覆盖） |
| Actions → General → 允许 GitHub Copilot/非本机 action？ | 默认可 | `softprops/action-gh-release`、`android-actions/setup-android` 需要拉取 |
| Environments/secrets | **可不配** | 首跑不依赖任何 secret（签名 debug、proot 走官方公开仓库） |

### 2.1 提交身份（推送前确认一次）

commit 的作者名与邮箱会**永久公开**在 GitHub 上，并可能被用来关联你的账号。

```bash
# 看当前身份
git config user.name
git config user.email

# 只想留真名、用 GitHub 的隐私邮箱（推荐给不想暴露主邮箱的人）
git config user.email "<你的GitHub数字ID>+<你的GitHub用户名>@users.noreply.github.com"

# 只对本仓库生效，不动全局配置
git config user.email "..."     # 在仓库根目录执行即写入 .git/config
```

> 仓库里已经有一条初始提交，push 前如果想改作者，用
> `git commit --amend --reset-author --author="Name <mail@example.com>"`
> （改完再 `git push -u origin main`）。

## 3. 首跑预期（push 后 ~15–25 min）

| Job | 首过概率评估（据已证/未证） | 失败时第一落点 |
|---|---|---|
| `tooling` | 高（全部逻辑已 Windows 干跑） | bash -n 报错行号直给 |
| `shell-apk` | **中——Kotlin 首次真编译，预期会有一轮小错**（本轮评审/修复后仍可能有 import、API 细节） | Actions 日志搜 `e:` 行 → 报给我逐条修 |
| `appliance` | 中高（Dockerfile 断言设计就是首跑排雷器）；注意 `--load` 在 GitHub arm runner（containerd image store）应可用 | `npm install` 网络重试 / buildx 断言 grep 失败 = 上游激活机制漂移（R9 预警生效） |
| build.yml canary | 可能因 latest 破坏而红，属**预期预警**，不阻塞发布线 | 看金丝雀日志记录上游变更点 |

## 4. 发首个 Release（首跑绿后）

```bash
git tag v0.1.0 && git push origin v0.1.0
```
`release.yml` 产出：appliance OCI/rootfs + `dsh-android-v0.1.0.apk`（debug 签名）+ installer 四件套 + `proot-bundle.tar.gz`（arm 冒烟过）+ SHA256SUMS 两式。
手机侧即：
```bash
export DSH_ANDROID_BASE=https://github.com/<owner>/dsh-android/releases/download/v0.1.0
curl -fsSL "$DSH_ANDROID_BASE/install.sh" | bash
```
必须用 `export` 形态：行首 `VAR=x curl … | bash` 只把变量给 `curl`，**不会**穿过管道进
`bash`（install.sh 会因 DSH_ANDROID_BASE 缺失以契约码 11 拒跑——行为正确，文档此前写错已修）。
等价替代：`curl -fsSL <url> | DSH_ANDROID_BASE=<url> bash`（管道右段前缀赋值）。

## 5. 失败处置分工

- 编译错误（`e: file:line`）→ 把日志段贴回来，Kotlin 修复是我的活
- 网络/镜像超时 → 重试（Actions 里 rerun failed jobs 即可，全部步骤幂等）
- proot 冒烟失败 → 报告 F 节未证实项命中，切「SKIP_SMOKE=1 取包 + 真机验证」降级并记录
- 验收清单里任何 A/S/K/U 项失败 → 按 docs/device-acceptance.md 分流列走

## 6. `.gitignore` 覆盖范围（已就位）

仓库根的 `.gitignore` 已排除以下内容，**首次 push 前请确认下面这些目录确实没进索引**
（`git status --ignored` 可核对）：

| 路径 | 是什么 | 为什么要排除 |
|---|---|---|
| `emu-assets/` | 抓取的上游 tarball、临时探针脚本、真机截图、logcat dump、profile 备份 | **含真实设备数据与个人日志，绝不能公开** |
| `dist/` | b1b/b2 产出的 appliance、bundle、SHA256SUMS | 可由脚本重建，体积大 |
| `.pnpm-store/` `node_modules/` | 包管理器缓存 | 体积大、可重建 |
| `android-shell/build/` `.gradle/` `.kotlin/` | Gradle/Kotlin 本地产物 | 可重建 |
| `local.properties` `*.jks` `*.keystore` | 本机 SDK 路径与签名密钥 | **签名密钥绝不能进仓库** |
| `brand/_scratch/` `brand/__pycache__/` | 品牌图渲染草稿与字节码缓存 | 中间产物 |
| `scripts/dev/*.class` | Java 复现件的编译产物 | 源码 `TarRepro.java` 保留，class 不保留 |

自查命令：

```bash
git ls-files | grep -Ei 'emu-assets|\.apk$|\.jks$|local\.properties' && echo "STOP: 敏感文件进了索引"
git ls-files | wc -l   # 规模应在数百量级，而不是数万
```

> 资产目录被排除后，新克隆的仓库要自己拉一遍上游 tarball 才能跑 b1b：
> `bash scripts/b0-fetch-assets.sh`（自带 sha256 强校验）。
