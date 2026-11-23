// scripts/dev/tar-parity-lib.mjs
// Shared parser replica for the branch logic in android-shell TarExtractor.kt.
// SAME byte offsets and branch semantics: ustar+prefix, GNU 'L' long names,
// pax 'x' path= override, hardlink-as-copy, device/FIFO skip, zero-block EOF.
// If TarExtractor.kt changes shape, update this replica too.
export const BLOCK = 512;
import nodeFs from 'node:fs';
import nodePath from 'node:path';

/**
 * @param {Buffer} buf uncompressed tar bytes
 * @param {string} outDir destination root (paths escaping it are rejected)
 * @returns {Array<{name:string,kind:string}>}
 */
export function extractBuffer(buf, outDir) {
  const fs = nodeFs;
  const path = nodePath;
  const dest = path.resolve(outDir);
  let off = 0;
  const entries = [];
  let pendingLongName = null;
  let pendingPaxPath = null;

  const strAt = (h, o, len) => {
    let e = h + o; while (e < h + o + len && buf[e] !== 0) e++;
    return buf.subarray(h + o, e).toString('utf8');
  };
  const numAt = (h, o, len) => {
    if (buf[h + o] === 0x80) { // GNU base-256
      let v = 0n;
      for (let i = 1; i < len; i++) v = (v << 8n) | BigInt(buf[h + o + i]);
      return Number(v);
    }
    const s = strAt(h, o, len).trim();
    return parseInt(s || '0', 8) || 0;
  };
  const safeJoin = (name, t) => {
    const f = path.resolve(dest, name);
    // Strict containment mirroring TarExtractor.kt: resolving to dest itself is
    // a root-write unless it is a benign './' directory member (null = no-op).
    if (f === dest) {
      if (t === '5') return null;
      throw new Error('root-write: ' + name);
    }
    if (!f.startsWith(dest + path.sep)) throw new Error('escape: ' + name);
    return f;
  };

  while (off + BLOCK <= buf.length) {
    const h = off;
    // Zero blocks are trailer/padding between concatenated archives: skip until
    // the next header or buffer end (mirrors the verified Kotlin loop).
    if (buf.subarray(h, h + BLOCK).every((b) => b === 0)) { off += BLOCK; continue; }
    off += BLOCK;
    let name = pendingLongName ?? strAt(h, 0, 100);
    pendingLongName = null;
    if (pendingPaxPath) { name = pendingPaxPath; pendingPaxPath = null; }
    const prefix = strAt(h, 345, 155);
    if (prefix && !name.startsWith('/')) name = `${prefix}/${name}`;
    const mode = numAt(h, 100, 8);
    const size = numAt(h, 124, 12);
    const type = String.fromCharCode(buf[h + 156] || 0);
    const linkName = strAt(h, 157, 100);
    const dataStart = off;
    const dataEnd = dataStart + size;
    off = dataStart + Math.ceil(size / BLOCK) * BLOCK;

    if (type === 'L') {
      pendingLongName = buf.subarray(dataStart, dataEnd).toString('utf8').replace(/\0+$/, '').trim();
      continue;
    }
    if (type === 'x') {
      const m = /\d+ path=(.*)/.exec(buf.subarray(dataStart, dataEnd).toString('utf8'));
      if (m) pendingPaxPath = m[1].trim();
      continue;
    }
    if (type === 'g' || type === 'X') continue;
    if (type === '5') {
      const d = safeJoin(name, '5');
      if (d) { fs.mkdirSync(d, { recursive: true }); entries.push({ name, kind: 'dir' }); }
      else entries.push({ name, kind: 'dir-root-noop' }); // './' member: no-op
      continue;
    }
    if (type === '2') { // symlink best-effort (parity with Kotlin runCatching)
      const f = safeJoin(name, '2');
      fs.mkdirSync(path.dirname(f), { recursive: true });
      // Target stored VERBATIM (relative stays relative — must survive tmp→rootfs rename).
      try { fs.symlinkSync(linkName, f); } catch { /* platform */ }
      entries.push({ name, kind: 'symlink' }); continue;
    }
    if (type === '1') { // hardlink → copy of earlier member addressed by linkName
      const f = safeJoin(name, '1');
      let target = null;
      try { target = safeJoin(linkName, '1'); } catch { /* escaping link target: Kotlin leaves src=null (no copy) */ }
      fs.mkdirSync(path.dirname(f), { recursive: true });
      if (target && fs.existsSync(target)) fs.copyFileSync(target, f);
      entries.push({ name, kind: 'hardlink' }); continue;
    }
    if (type === '0' || type === '\u0000' || type === '7') {
      const f = safeJoin(name, type);
      fs.mkdirSync(path.dirname(f), { recursive: true });
      fs.writeFileSync(f, buf.subarray(dataStart, dataEnd));
      if (mode & 0o100) fs.chmodSync(f, 0o755);
      entries.push({ name, kind: 'file' });
      continue;
    }
    entries.push({ name, kind: 'skip-' + type }); // devices/FIFOs/whiteouts
  }
  return entries;
}
