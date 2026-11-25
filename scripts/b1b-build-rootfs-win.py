#!/usr/bin/env python3
# scripts/b1b-build-rootfs-win.py
# Docker-free appliance rootfs builder (Windows/Linux CI-less environments).
#
# Strategy: POSIX tar archives are CONCATENABLE — extracting yields last-wins
# overlay semantics. So we never unpack the Linux tree on Windows (which would
# destroy symlinks/modes); we concatenate:
#   ubuntu-base.tar  +  node.tar  +  overlay.tar(custom)  ->  gzip  ->  rootfs.tar.gz
#
# overlay.tar carries (written explicitly, unix modes + symlink members):
#   /usr/local/bin/dsh-serve          0755 guest supervisor (mirrors a2)
#   /node          -> node-vX.Y.Z-linux-arm64   symlink for a stable PATH
#
# Also assembles proot-bundle.tar.gz from already-verified Termux deb contents:
#   proot, loader, lib/libtalloc.so.2   (0755 on executables)
#
# Usage: python b1b-build-rootfs-win.py <assetsDir> <outDir>
import gzip
import hashlib
import io
import lzma
import os
import shutil
import sys
import tarfile


def read_tar(path):
    """Return the raw (decompressed) tar bytes of a .tar/.tar.gz/.tar.xz file."""
    if path.endswith('.tar.gz'):
        with gzip.open(path, 'rb') as f:
            return f.read()
    if path.endswith('.tar.xz'):
        with lzma.open(path, 'rb') as f:
            return f.read()
    with open(path, 'rb') as f:
        return f.read()


def build_overlay(dest_dir, out_path):
    """tar with: dsh-serve script + /node symlink. Modes explicit unix."""
    with tarfile.open(out_path, 'w') as t:
        for name, mode in (('usr', 0o755), ('usr/local', 0o755), ('usr/local/bin', 0o755)):
            ti = tarfile.TarInfo(name)
            ti.type = tarfile.DIRTYPE
            ti.mode = mode
            t.addfile(ti)
        serve = os.path.join(dest_dir, 'scripts', 'a2-guest-dsh-serve.sh')
        with open(serve, 'rb') as f:
            data = f.read()
        ti = tarfile.TarInfo('usr/local/bin/dsh-serve')
        ti.mode = 0o755
        ti.size = len(data)
        t.addfile(ti, io.BytesIO(data))
        ti = tarfile.TarInfo('node')
        ti.type = tarfile.SYMTYPE
        ti.linkname = 'node-v24.9.0-linux-arm64'
        t.addfile(ti)


repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
assets = sys.argv[1] if len(sys.argv) > 1 else os.environ.get(
    'DSH_ASSETS_DIR', os.path.join(repo, 'emu-assets'))
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(repo, 'dist')
os.makedirs(out, exist_ok=True)

# The asset dir is gitignored on purpose (it holds fetched upstream tarballs,
# not source). Populate it with scripts/b0-fetch-assets.sh before building.
if not os.path.isdir(assets):
    sys.exit(
        f'[b1b] assets dir not found: {assets}\n'
        f'       run: bash scripts/b0-fetch-assets.sh\n'
        f'       or set DSH_ASSETS_DIR to an existing directory')
for required in ('ubuntu-base-arm64.tar.gz', 'node-v24-arm64.tar.xz'):
    if not os.path.isfile(os.path.join(assets, required)):
        sys.exit(
            f'[b1b] missing asset: {required} in {assets}\n'
            f'       run: bash scripts/b0-fetch-assets.sh')

print('[b1b] decompress base + node tars (stream, no extraction)')
base_tar = read_tar(os.path.join(assets, 'ubuntu-base-arm64.tar.gz'))
node_tar = read_tar(os.path.join(assets, 'node-v24-arm64.tar.xz'))
print(f'  base={len(base_tar)/1e6:.0f}MB node={len(node_tar)/1e6:.0f}MB')

overlay_path = os.path.join(assets, 'overlay.tar')
build_overlay(repo, overlay_path)
with open(overlay_path, 'rb') as f:
    overlay_tar = f.read()
print(f'  overlay={len(overlay_tar)}B (dsh-serve 0755 + /node symlink)')

# PRELOAD layer: node_modules of @deepseek-ai/dsh (cross-installed for
# linux-arm64 via npm --os=linux --cpu=arm64 --libc=glibc) baked straight
# into the appliance so devices never run guest-side npm (device npm hits
# proot ptrace fork races) and re-staging can never wipe a baked tree.
preload_src = os.path.join(repo, 'dist', 'guest-preload', 'node_modules')
# pnpm is NOT npm-installable on a win host for a linux target (platform pkgs
# are native). Land it the way b2/device tests proved: standalone tarball tree
# next to preload (pnpm/ subdir with root bin "pnpm"). Missing → warn loudly:
# `dsh plugin add` needs pnpm on device (ENOENT incident, field-notes #13).
pnpm_src = os.path.join(preload_src, 'pnpm')  # merged into the preload tree itself (rc5 refresh)
if not os.path.isdir(pnpm_src):
    print('[b1b] WARN: preload 缺 pnpm/（plugin add 将不可用）— 跑 b2 的 pnpm 落地步骤')
preload_path = os.path.join(assets, 'preload.tar')
if os.path.isdir(preload_src):
    n_files = 0
    with tarfile.open(preload_path, 'w') as pt:
        def add_dir(name):
            ti = tarfile.TarInfo(name)
            ti.type = tarfile.DIRTYPE
            ti.mode = 0o755
            pt.addfile(ti)
        add_dir('usr/local')
        add_dir('usr/local/node_modules')
        add_dir('usr/local/bin')
        # bin link: /usr/local/bin/dsh -> ../node_modules/@deepseek-ai/dsh/lib/bin.js
        ti = tarfile.TarInfo('usr/local/bin/dsh')
        ti.type = tarfile.SYMTYPE
        ti.linkname = '../node_modules/@deepseek-ai/dsh/lib/bin.js'
        pt.addfile(ti)
        # pnpm lands as a node_modules package + root bin link (device-proven shape)
        if os.path.isdir(pnpm_src):
            for root, dirs, files in os.walk(pnpm_src):
                rel_root = os.path.relpath(root, pnpm_src).replace('\\', '/')
                base_p = 'usr/local/node_modules/pnpm' if rel_root == '.' else f'usr/local/node_modules/pnpm/{rel_root}'
                for d in dirs:
                    add_dir(base_p + '/' + d if rel_root != '.' else 'usr/local/node_modules/pnpm')
                for fn in files:
                    src_path = os.path.join(root, fn)
                    arc = base_p + '/' + fn
                    ti = pt.gettarinfo(src_path, arcname=arc)
                    ti.mode = 0o755 if (fn in ('pnpm', 'pn', 'pnpx', 'pnx') or fn.endswith('.mjs')) else 0o644
                    with open(src_path, 'rb') as fh:
                        pt.addfile(ti, fh)
                    n_files += 1
            ti = tarfile.TarInfo('usr/local/bin/pnpm')
            ti.type = tarfile.SYMTYPE
            ti.linkname = '../node_modules/pnpm/pnpm'
            pt.addfile(ti)
            print('  pnpm folded into preload layer')
        for root, dirs, files in os.walk(preload_src):
            rel_root = os.path.relpath(root, preload_src).replace('\\', '/')
            base = 'usr/local/node_modules' if rel_root == '.' else f'usr/local/node_modules/{rel_root}'
            for d in dirs:
                add_dir(f'{base}/{d}' if rel_root != '.' else f'usr/local/node_modules/{d}')
            for fn in files:
                src_path = os.path.join(root, fn)
                arc = (f'{base}/{fn}' if rel_root != '.' else f'usr/local/node_modules/{fn}')
                ti = pt.gettarinfo(src_path, arcname=arc)
                # exec bit on entry points and .node binaries
                if fn.endswith('.node') or arc.endswith('lib/bin.js'):
                    ti.mode = 0o755
                else:
                    ti.mode = 0o644
                with open(src_path, 'rb') as fh:
                    pt.addfile(ti, fh)
                n_files += 1
    print(f'  preload layer: {n_files} files -> {os.path.basename(preload_path)}')
else:
    print('  (no preload dir — appliance will require device-side install)')

# POSIX: concatenating tars = later members overlay earlier ones on extract.
# FINAL FORM: uncompressed .tar — gzip of a 1 GB stream costs minutes and the
# wireless transport (75 MB/s) makes compression pointless; stage() reads the
# raw tar with EOF-correct FileInputStream semantics.
rootfs_name = 'dsh-appliance-rootfs.tar'
rootfs_out = os.path.join(out, rootfs_name)
with open(rootfs_out, 'wb') as raw:
    raw.write(base_tar)
    raw.write(node_tar)
    raw.write(overlay_tar)
    if os.path.exists(preload_path):
        with open(preload_path, 'rb') as pf:
            while chunk := pf.read(1 << 22):
                raw.write(chunk)
    raw.write(b'\x00' * 1024)  # single POSIX trailer
sha = hashlib.sha256(open(rootfs_out, 'rb').read()).hexdigest()
open(os.path.join(out, 'SHA256SUMS-rootfs.txt'), 'w').write(f'{sha}  {rootfs_name}\n')
print(f'[b1b] {rootfs_name}  sha256={sha[:16]}…')

# proot bundle ---------------------------------------------------------------
bundle_dir = os.path.join(assets, 'proot-bundle')
os.makedirs(os.path.join(bundle_dir, 'lib'), exist_ok=True)
px = os.path.join(assets, 'px', 'data', 'data', 'com.termux', 'files', 'usr')
tx = os.path.join(assets, 'tx', 'data', 'data', 'com.termux', 'files', 'usr')
shutil.copyfile(os.path.join(px, 'bin', 'proot'), os.path.join(bundle_dir, 'proot'))
# b1d RUNPATH patch must run AFTER the px copy (fresh overwrite) — the patched
# copy would otherwise be clobbered (this exact ordering bug shipped an
# unpatched proot once; run inline now and verify after packing).
import runpy
sys.argv = ['b1d', os.path.join(bundle_dir, 'proot')]
try:
    runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'b1d-patch-proot-runpath.py'), run_name='__main__')
except SystemExit:
    pass  # b1d exits 0 on already-patched
shutil.copyfile(os.path.join(px, 'libexec', 'proot', 'loader'), os.path.join(bundle_dir, 'loader'))
# ensure libtalloc is staged (idempotent) before the general *.so* sweep
so = [n for n in os.listdir(os.path.join(tx, 'lib')) if n.startswith('libtalloc.so.2')]
if so and not os.path.exists(os.path.join(bundle_dir, 'lib', 'libtalloc.so.2')):
    shutil.copyfile(os.path.join(tx, 'lib', so[0]), os.path.join(bundle_dir, 'lib', 'libtalloc.so.2'))
# EVERY runtime .so the proot binary needs ships in the bundle (libtalloc from
# the tx deb extract; libandroid-shmem and any future deps from sx/ etc.):
# any *.so* present in bundle/lib is included.
for so_file in sorted(os.listdir(os.path.join(bundle_dir, 'lib'))):
    if '.so' in so_file:
        print(f'  bundle lib: {so_file}')
bundle_tgz = os.path.join(out, 'proot-bundle.tar.gz')
with tarfile.open(bundle_tgz, 'w:gz') as t:
    for name, mode in (('proot-bundle', 0o755), ('proot-bundle/lib', 0o755)):
        ti = tarfile.TarInfo(name)
        ti.type = tarfile.DIRTYPE
        ti.mode = mode
        t.addfile(ti)
    for rel in (['proot-bundle/proot', 'proot-bundle/loader'] +
                ['proot-bundle/lib/' + f for f in sorted(os.listdir(os.path.join(bundle_dir, 'lib'))) if '.so' in f]):
        p = os.path.join(assets, *rel.split('/'))
        ti = t.gettarinfo(p, arcname=rel)
        ti.mode = 0o755
        with open(p, 'rb') as f:
            t.addfile(ti, f)
sha2 = hashlib.sha256(open(bundle_tgz, 'rb').read()).hexdigest()
# FINAL GATE: the artifact that ships must carry the patch (never trust the
# pipeline ordering again — an unpatched proot once reached the device).
with tarfile.open(bundle_tgz) as chk:
    packed = chk.extractfile('proot-bundle/proot').read()
assert packed[7080:7110] == b'/data/data/com.dsh.android/lib', 'BUNDLE SHIPS UNPATCHED PROOT'
print('[b1b] packed proot RUNPATH verified: /data/data/com.dsh.android/lib')
open(os.path.join(out, 'SHA256SUMS-proot.txt'), 'w').write(f'{sha2}  proot-bundle.tar.gz\n')
print(f'[b1b] proot-bundle.tar.gz  sha256={sha2[:16]}…')
print('[b1b] done ->', out)
