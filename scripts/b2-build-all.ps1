# scripts/b2-build-all.ps1 — one-shot appliance artifact chain:
#   b1b (concatenate base+node+overlay, bundle w/ proot patch) ->
#   b1c (normalize single tar.gz) -> uncompressed .tar -> SHA256SUMS.txt
# Outputs in $dist: dsh-appliance-rootfs.tar, proot-bundle.tar.gz, SHA256SUMS.txt
$ErrorActionPreference = 'Stop'
$root  = Split-Path -Parent $PSScriptRoot
$assets = if ($env:DSH_ASSETS_DIR) { $env:DSH_ASSETS_DIR } else { "$root\emu-assets" }
$dist   = "$root\dist"
# The asset dir is gitignored on purpose (fetched upstream tarballs, not source).
# Populate it with scripts/b0-fetch-assets.sh before building.
if (-not (Test-Path $assets)) {
  throw "assets dir not found: $assets - run 'bash scripts/b0-fetch-assets.sh' or set DSH_ASSETS_DIR"
}
# Python interpreter resolution, in order:
#   1) $env:DSH_PYTHON                     (explicit override)
#   2) DSH bundled runtime under $env:DSH_HOME
#   3) python / py launcher on PATH
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

& $py "$root\scripts\b1b-build-rootfs-win.py" $assets $dist | Select-Object -Last 4
# b1b now emits dsh-appliance-rootfs.tar DIRECTLY (concat base+node+overlay+
# preload, uncompressed, single trailer). No gzip/normalize steps.
& $py -c @"
import hashlib
r = open(r'$dist\SHA256SUMS-rootfs.txt').read().strip()
b = open(r'$dist\SHA256SUMS-proot.txt').read().strip()
open(r'$dist\SHA256SUMS.txt','w').write(r + chr(10) + b + chr(10))
print('rootfs:', r[:40])
print('bundle:', b[:40])
"@
# In-app UPDATE payload: the preload layer IS the DSH engine module tree —
# ship it as dsh-update-<ver>.tar.gz so devices already on any appliance can
# update the engine natively (AssetInstaller.applyUpdateIfAny) without ever
# running guest npm/pnpm.
& $py -c @"
import json, os, tarfile, gzip, hashlib
nm = r'$root\dist\guest-preload\node_modules'
ver = json.load(open(os.path.join(nm, '@deepseek-ai', 'dsh', 'package.json')))['version']
out = r'$dist' + '\\\\dsh-update-' + ver + '.tar.gz'
with gzip.open(out, 'wb', compresslevel=1) as gz, tarfile.open(fileobj=gz, mode='w') as t:
    def adir(name):
        ti = tarfile.TarInfo(name); ti.type = tarfile.DIRTYPE; ti.mode = 0o755; t.addfile(ti)
    adir('usr'); adir('usr/local'); adir('usr/local/bin'); adir('usr/local/node_modules')
    ti = tarfile.TarInfo('usr/local/bin/dsh'); ti.type = tarfile.SYMTYPE
    ti.linkname = '../node_modules/@deepseek-ai/dsh/lib/bin.js'; t.addfile(ti)
    for root_, dirs, files in os.walk(nm):
        rel = os.path.relpath(root_, nm).replace('\\\\', '/')
        base = 'usr/local/node_modules' if rel == '.' else 'usr/local/node_modules/' + rel
        for dd in dirs: adir((base if rel != '.' else 'usr/local/node_modules') + '/' + dd)
        for fn in files:
            sp = os.path.join(root_, fn)
            arc = (base if rel != '.' else 'usr/local/node_modules') + '/' + fn
            ti = t.gettarinfo(sp, arcname=arc)
            ti.mode = 0o755 if (fn.endswith('.node') or arc.endswith('lib/bin.js')) else 0o644
            with open(sp, 'rb') as fh: t.addfile(ti, fh)
h = hashlib.sha256(open(out,'rb').read()).hexdigest()
print('update pkg:', os.path.basename(out), h[:16])
open(os.path.join(r'$dist', 'SHA256SUMS-update.txt'), 'w').write(h + '  ' + os.path.basename(out) + chr(10))
"@
"artifacts ready in $dist"
