// scripts/dev/tar-gnu-branches-test.mjs
// Unit test for the GNU 'L' long-name / pax 'x' path= / hardlink '1' / device-skip
// branches shared by the parser replica (tar-parity-lib.mjs) and TarExtractor.kt.
// Hand-crafts a GNU/pax tar.gz, extracts it, and asserts the resulting tree.
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { extractBuffer } from './tar-parity-lib.mjs';

const BLOCK = 512;
const TMP = process.argv[2] || path.join(process.env.TEMP || '/tmp', 'dsh-tar-gnu');
fs.rmSync(TMP, { recursive: true, force: true });
fs.mkdirSync(TMP, { recursive: true });

function header({ name, mode = 0o644, size = 0, type = '0', link = '' }) {
  const h = Buffer.alloc(BLOCK);
  h.write(name.slice(0, 99), 0, 'utf8');
  h.write(mode.toString(8).padStart(7, '0') + '\0', 100);
  h.write('0000000\0', 108); h.write('0000000\0', 116);
  h.write(size.toString(8).padStart(11, '0') + '\0', 124);
  h.write('00000000000\0', 136);
  h.write('        ', 148); // blank chksum during sum, then fill
  h.write(type, 156, 'utf8');
  h.write(link.slice(0, 99), 157, 'utf8');
  h.write('ustar\0', 257); h.write('00', 263);
  let sum = 0; for (const b of h) sum += b;
  h.write(sum.toString(8).padStart(6, '0') + '\0 ', 148);
  return h;
}
function entry(meta, payload = Buffer.alloc(0)) {
  const pad = Buffer.alloc((BLOCK - (payload.length % BLOCK)) % BLOCK);
  return Buffer.concat([header(meta), payload, pad]);
}
// pax record: "<len> <key>=<value>\n" where len counts the whole record.
function paxRecord(key, value) {
  let body = `${key}=${value}\n`;
  let len = body.length + String(body.length).length + 1;
  while (String(len).length + body.length + 1 > len) {
    len = body.length + String(len).length + 1;
  }
  return `${len} ${body}`;
}

const longName = 'rootfs/usr/local/lib/node_modules/@deepseek-ai/' +
  'dsh-fs-sandbox/lib/types/a-name-that-is-definitely-longer-than-two-hundred-characters-' +
  'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.txt';
const paxName = 'rootfs/中文/pax-override-文件.txt';

const parts = [];
parts.push(entry({ name: 'rootfs/', type: '5', mode: 0o755 }));
// GNU long name: '@LongLink' record carrying the real path as payload.
const longBuf = Buffer.from(longName + '\0', 'utf8');
parts.push(entry({ name: '././@LongLink', type: 'L', size: longBuf.length }, longBuf));
const longPayload = Buffer.from('gnulong');
parts.push(entry({ name: longName.slice(0, 99), type: '0', size: longPayload.length }, longPayload));
// pax header overriding path (unicode + override branch), then its file.
const paxBuf = Buffer.from(paxRecord('path', paxName), 'utf8');
parts.push(entry({ name: 'PaxHeaders.0/x', type: 'x', size: paxBuf.length }, paxBuf));
const paxPayload = Buffer.from('pax!');
parts.push(entry({ name: 'placeholder', type: '0', size: paxPayload.length }, paxPayload));
// hardlink to the pax file → must materialize as a copy.
parts.push(entry({ name: 'rootfs/zh-link.txt', type: '1', link: paxName }));
// character device must be skipped without creating anything.
parts.push(entry({ name: 'rootfs/null-dev', type: '3' }));
// non-block-aligned file payload (700 bytes) followed by another member —
// catches pad/round-up desync (the device-verified Kotlin skipBytes bug).
const odd = Buffer.from('x'.repeat(700));
parts.push(entry({ name: 'rootfs/odd.bin', type: '0', size: odd.length }, odd));
const afterOdd = Buffer.from('survivor');
parts.push(entry({ name: 'rootfs/after-odd.txt', type: '0', size: afterOdd.length }, afterOdd));
parts.push(Buffer.alloc(BLOCK * 2)); // EOF marker of archive #1
// POSIX archive concatenation (our b1b Windows builder relies on this): a
// non-zero header AFTER an EOF trailer starts another archive; readers must
// keep going (GNU tar / bsdtar semantics).
const secondPayload = Buffer.from('second');
parts.push(entry({ name: 'concat-second.txt', type: '0', size: secondPayload.length }, secondPayload));
// Root-write guard: './' as a DIRECTORY member is a benign no-op…
parts.push(entry({ name: './', type: '5' }));
// …and './' as a FILE member must be rejected outright (EISDIR / traversal class).
{
  const bad = [];
  const badPayload = Buffer.from('x');
  bad.push(entry({ name: 'rootfs/', type: '5', mode: 0o755 }));
  bad.push(entry({ name: './', type: '0', size: badPayload.length }, badPayload));
  const badTar = Buffer.concat([...bad, Buffer.alloc(BLOCK * 2)]);
  let rejected = false;
  try {
    extractBuffer(badTar, path.join(TMP, 'out-rootwrite'));
  } catch (e) {
    rejected = /root-write|escape/.test(String(e.message));
  }
  console.log(rejected ? 'OK   root-write file member rejected' : 'FAIL root-write file member NOT rejected');
  if (!rejected) process.exitCode = 1;
}
parts.push(Buffer.alloc(BLOCK * 2)); // EOF marker of archive #2

const tar = Buffer.concat(parts);
const archive = path.join(TMP, 'gnu-branches.tar.gz');
fs.writeFileSync(archive, zlib.gzipSync(tar));

const out = path.join(TMP, 'out');
fs.mkdirSync(out, { recursive: true });
const entries = extractBuffer(zlib.gunzipSync(fs.readFileSync(archive)), out);

const want = {
  [longName]: 'gnulong',
  [paxName]: 'pax!',
  'rootfs/zh-link.txt': 'pax!',
  'concat-second.txt': 'second',
  'rootfs/after-odd.txt': 'survivor',
};
let fail = 0;
for (const [rel, content] of Object.entries(want)) {
  const f = path.join(out, rel);
  const got = fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : null;
  if (got === content) console.log(`OK   ${rel}`);
  else { console.log(`FAIL ${rel} (got=${JSON.stringify(got)} want=${JSON.stringify(content)})`); fail++; }
}
if (fs.existsSync(path.join(out, 'rootfs/null-dev'))) { console.log('FAIL device created (must skip)'); fail++; }
else console.log('OK   device skipped');
const skipped = entries.filter((e) => e.kind.startsWith('skip-'));
console.log(`parsed entries=${entries.length} skippedDevices=${skipped.length}`);
console.log(fail === 0 ? 'GNU/pax BRANCHES OK' : `BRANCH TEST FAILED (${fail})`);
process.exit(fail === 0 ? 0 : 1);
