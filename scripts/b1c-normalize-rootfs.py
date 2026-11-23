#!/usr/bin/env python3
# scripts/b1c-normalize-rootfs.py
# Restream a CONCATENATED tar.gz (b1b output) into a single canonical tar.gz.
# Byte-faithful: header blocks and payload blocks are copied verbatim (pax/GNU
# extensions, modes, symlink linknames all preserved), duplicate names are
# de-duplicated last-wins (overlay semantics), and the output carries exactly
# one POSIX trailer. This removes inter-archive zero-block padding, which is
# the structure the Android TarExtractor proved least able to handle.
#
# Usage: python b1c-normalize-rootfs.py <in.tar.gz> <out.tar.gz>
import gzip
import hashlib
import io
import sys

B = 512
src, dst = sys.argv[1], sys.argv[2]
raw = gzip.open(src, 'rb').read()

pos = 0
members = []          # ordered (name_bytes_lookup_key, header_blk, payload_bytes)
order = []            # insertion order of keys
index = {}            # key -> position in order
n = 0

def strf(blk, o, l):
    e = 0
    while e < l and blk[o + e] != 0:
        e += 1
    return blk[o:o + e].decode('utf8', 'replace')

def numf(blk, o, l):
    if blk[o] == 0x80:
        v = 0
        for i in range(1, l):
            v = (v << 8) | blk[o + i]
        return v
    t = strf(blk, o, l).strip(' \x00')
    try:
        return int(t, 8)
    except ValueError:
        return 0

while pos + B <= len(raw):
    h = raw[pos:pos + B]
    pos += B
    if h == b'\x00' * B:
        continue  # trailer / inter-archive padding
    name = strf(h, 0, 100)
    prefix = strf(h, 345, 155)
    if prefix and not name.startswith('/'):
        name = prefix + '/' + name
    size = numf(h, 124, 12)
    need = ((size + B - 1) // B) * B
    payload = raw[pos:pos + need]
    pos += need
    if name == '':
        raise SystemExit(f'FATAL: empty-name member at #{n} (would trip device guard)')
    key = name
    rec = (h, payload)
    if key in index:
        members[index[key]] = rec  # last-wins overlay
    else:
        index[key] = len(order)
        order.append(key)
        members.append(rec)
    n += 1
    if n % 2000 == 0:
        print(f'  ...{n} members')

if pos != len(raw):
    print(f'note: trailing {len(raw) - pos} bytes not block-aligned (gzip padding); ignoring')

out = gzip.open(dst, 'wb', compresslevel=1)
try:
    for key in order:
        h, payload = members[index[key]]
        out.write(h)
        out.write(payload)
    out.write(b'\x00' * (B * 2))  # single POSIX trailer, nothing after it
finally:
    out.close()

sha = hashlib.sha256(open(dst, 'rb').read()).hexdigest()
print(f'members written: {len(order)} (from {n} parsed)  sha256={sha}')
open(dst + '.sha256', 'w').write(f'{sha}  {dst.split(chr(47))[-1].split(chr(92))[-1]}\n')
