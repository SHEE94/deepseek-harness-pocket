# =============================================================================
# b2-stage-shell-assets.ps1 — 把 b1 产物推到壳 APK 的资产收件目录（adb push）
#
# 用法（Windows PowerShell，接好手机并允许 USB 调试）：
#   ./scripts/b2-stage-shell-assets.ps1 -Proot C:\path\to\proot-arm64
# 可选：-Dist dist  -Package com.dsh.android  -Device <adb serial>
#
# 收件目录（app 专属外部目录，adb 无需 root 即可写）：
#   /sdcard/Android/data/<package>/files/incoming/
# 壳 APK 的 AssetInstaller 会在下次启动/重启周期校验 SHA-256、解包 rootfs
# 并写版本戳（files/runtime/runtime.stamp）。
#
# proot 二进制的来源（三选一，M2 工程项，见 android-shell/README.md）：
#   1) 用 NDK 交叉编译 termux/proot（推荐，产物自含 bionic linker）
#   2) 从已 Root 的 Termux 设备 `run-as com.termux cat .../proot` 提取（需自行确认可执行性）
#   3) 复用先例项目发行 APK 解包出的 proot + 垫片（注意其 license MIT）
# =============================================================================
param(
    [string]$Dist = "dist",
    [string]$Proot = "",
    [string]$Package = "com.dsh.android",
    [string]$Device = ""
)
$ErrorActionPreference = "Stop"

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) { throw "未找到 adb（Android platform-tools）。" }
$adbArgs = @(); if ($Device) { $adbArgs += @("-s", $Device) }

$rootfs = Get-ChildItem -Path $Dist -Filter "*-rootfs.tar.gz" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $rootfs) { throw "缺少 rootfs 包：先运行 scripts/b1-build-appliance.sh 生成 $Dist/*-rootfs.tar.gz" }

# 校验和：优先复用 b1 的 SHA256SUMS.txt；缺失则现算
$sums = Join-Path $Dist "SHA256SUMS.txt"
if (-not (Test-Path $sums)) {
    Write-Host "[b2] SHA256SUMS.txt 缺失，现场生成（sha256sum 兼容格式）"
    $lines = Get-ChildItem $Dist -File | ForEach-Object {
        $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$h  $($_.Name)"
    }
    Set-Content -Path $sums -Value $lines -Encoding ascii
}

$remote = "/sdcard/Android/data/$Package/files/incoming"
& adb @adbArgs shell mkdir -p $remote | Out-Null
Write-Host "[b2] push $($rootfs.Name)"
& adb @adbArgs push $rootfs.FullName "$remote/$($rootfs.Name)"
Write-Host "[b2] push SHA256SUMS.txt"
& adb @adbArgs push $sums "$remote/SHA256SUMS.txt"
if ($Proot) {
    if (-not (Test-Path $Proot)) { throw "Proot 路径不存在：$Proot" }
    Write-Host "[b2] push proot"
    & adb @adbArgs push $Proot "$remote/proot"
} else {
    # 推荐：CI 产出的 proot-bundle.tar.gz（b3 脚本产物；含 loader+libtalloc）
    $bundle = Get-ChildItem -Path $Dist -Filter "proot-bundle.tar.gz" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($bundle) {
        Write-Host "[b2] push proot-bundle.tar.gz（推荐路径：CI 冒烟过的完整运行件）"
        & adb @adbArgs push $bundle.FullName "$remote/proot-bundle.tar.gz"
    } else {
        Write-Warning "未提供 -Proot 且 dist 内无 proot-bundle.tar.gz：壳将提示『proot 缺失』。用 scripts/b3-fetch-proot.sh 生成 bundle。"
    }
}

Write-Host "[b2] 完成。下一步：打开 DSH 壳 APK —— AssetInstaller 会校验并解包（通知栏可见进度）。"
