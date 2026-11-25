# b4-pack-assets.ps1 (v2) — bundle engine assets into the APK as RAW tars.
# AGP's asset pipeline expands *.gz assets at merge time anyway (device-proven:
# assets/dsh-appliance-rootfs.tar appeared from a .gz input), so we ship raw
# tars with exact names that findPlan/verify expect. APK ~370MB (zip-level
# deflate) - the user explicitly required bundling over size savings.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$dist = Join-Path $root 'dist'
$assets = Join-Path $root 'android-shell\app\src\main\assets'

# Python interpreter: $env:DSH_PYTHON, else the DSH bundled runtime, else PATH.
$py = $null
if ($env:DSH_PYTHON -and (Test-Path $env:DSH_PYTHON)) {
  $py = $env:DSH_PYTHON
} elseif ($env:DSH_HOME) {
  $py = Get-ChildItem -Path (Join-Path $env:DSH_HOME 'dsh-runtimes') -Filter 'python.exe' `
        -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'dependencies\\python\\python\.exe$' } |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $py) {
  foreach ($cand in @('python', 'python3', 'py')) {
    if (Get-Command $cand -ErrorAction SilentlyContinue) { $py = $cand; break }
  }
}
if (-not $py) { throw "python not found - set DSH_PYTHON to a python.exe path" }

New-Item -ItemType Directory $assets -Force | Out-Null
# clean previous shape (.gz variants) so assets.list is unambiguous
Get-ChildItem $assets -Filter '*.tar.gz' -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem $assets -Filter '*.progress' -ErrorAction SilentlyContinue | Remove-Item -Force

$srcTar = Join-Path $dist 'dsh-appliance-rootfs.tar'
if (-not (Test-Path $srcTar)) { throw "missing $srcTar - run b2-build-all.ps1 first" }
Copy-Item $srcTar (Join-Path $assets 'dsh-appliance-rootfs.tar') -Force

# proot bundle: expand the dist .gz into the raw tar for name coherence
$code = @'
import gzip, shutil, sys
src, dst = sys.argv[1], sys.argv[2]
with gzip.open(src, 'rb') as fin, open(dst, 'wb') as fout:
    shutil.copyfileobj(fin, fout, 8 << 20)
print('bundle expanded')
'@
$code | & $py - (Join-Path $dist 'proot-bundle.tar.gz') (Join-Path $assets 'proot-bundle.tar')

$code2 = @'
import hashlib, sys
def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(8 << 20), b''):
            h.update(chunk)
    return h.hexdigest()
base, out = sys.argv[1], sys.argv[2]
rows = []
for name in ('dsh-appliance-rootfs.tar', 'proot-bundle.tar'):
    rows.append(f'{sha(base + chr(92) + name)}  {name}')
open(out, 'w').write('\n'.join(rows) + '\n')
print('sums written')
'@
$code2 | & $py - $assets (Join-Path $assets 'SHA256SUMS.txt')
Get-Content (Join-Path $assets 'SHA256SUMS.txt')
Get-ChildItem $assets | ForEach-Object { "  {0}  {1:N0} MB" -f $_.Name, ($_.Length / 1MB) }