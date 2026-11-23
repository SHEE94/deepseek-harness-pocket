#!/usr/bin/env python3
# scripts/b1d-patch-proot-runpath.py
# Rewrite the DT_RUNPATH string of the Termux proot binary IN-PLACE (shorter
# replacement + zero padding): Android 15+ bionic ignores LD_LIBRARY_PATH, so
# the app-exec'd proot can only find libtalloc via a compiled RUNPATH pointing
# at a path the app can actually create: /data/data/com.dsh.android/lib (30 B
# ≤ original /data/data/com.termux/files/usr/lib 34 B).
#
# Usage: python b1d-patch-proot-runpath.py <proot-file>
#   patches in place; prints before/after.
import struct
import sys

path = sys.argv[1]
data = bytearray(open(path, 'rb').read())
OLD = b'/data/data/com.termux/files/usr/lib'
NEW = b'/data/data/com.dsh.android/lib'
assert len(NEW) < len(OLD)

e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
e_shentsize, e_shnum = struct.unpack_from('<HH', data, 0x3a)
e_shstrndx = struct.unpack_from('<H', data, 0x3e)[0]
so = e_shoff + e_shstrndx * e_shentsize
shstr_off = struct.unpack_from('<Q', data, so + 0x18)[0]

dyn_off = dyn_size = 0
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    if off + e_shentsize > len(data):
        break  # defensive: malformed/trimmed section table
    nm_start = shstr_off + struct.unpack_from('<I', data, off)[0]
    e = nm_start
    while e < len(data) and data[e] != 0:
        e += 1
    if data[nm_start:e] == b'.dynamic':
        dyn_off = struct.unpack_from('<Q', data, off + 0x18)[0]
        dyn_size = struct.unpack_from('<Q', data, off + 0x20)[0]
assert dyn_off, 'no .dynamic'

strtab = None
runpath_val = None
for i in range(0, dyn_size - 15, 16):
    tag, val = struct.unpack_from('<qQ', data, dyn_off + i)
    if tag == 0:
        break
    if tag == 5:
        strtab = val
    if tag == 29:  # DT_RUNPATH
        runpath_val = val
assert strtab and runpath_val is not None, 'missing STRTB/RUNPATH'

# strtab is a vaddr; all Termux segments map vaddr==offset for these files
# (verified: .dynstr addr==offset==5348). Cross-check the string itself.
target = strtab + runpath_val
# idempotent: already patched → exit loudly, never silently
if bytes(data[target:target + len(NEW)]) == NEW:
    print(f'already patched: RUNPATH@{target} = {NEW.decode()}')
    sys.exit(0)
assert bytes(data[target:target + len(OLD)]) == OLD, 'RUNPATH string mismatch'

# FIXED-WIDTH write: bytearray slice assignment RESIZES when lengths differ —
# an earlier 35-into-34 version shifted the whole ELF by one byte (corrupted
# DT sections: "empty/missing DT_HASH"). Length must be preserved exactly.
size_before = len(data)
data[target:target + len(OLD)] = NEW + b'\x00' * (len(OLD) - len(NEW))
assert len(data) == size_before, 'slice assignment resized the file (fixed-width violation)'
open(path, 'wb').write(bytes(data))
# write-back verification (a silent no-write once shipped an UNPATCHED proot)
check = open(path, 'rb').read()
assert len(check) == size_before, 'file size changed on disk'
assert bytes(check[target:target + len(NEW)]) == NEW, 'write-back verification failed'
print(f'RUNPATH @{target}: {OLD.decode()} -> {NEW.decode()} (+{len(OLD)-len(NEW)-1} zero pad) VERIFIED size-preserved')
