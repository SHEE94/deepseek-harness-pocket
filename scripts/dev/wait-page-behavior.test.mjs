// scripts/dev/wait-page-behavior.test.mjs
// Headless tests for MainActivity's WAIT page state machine (embedded JS).
// Extracts the real <script> from MainActivity.kt and drives setFacts()
// through every stage, asserting step classes, status/note text and stamp.
import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';
import assert from 'node:assert/strict';

const here = path.dirname(url.fileURLToPath(import.meta.url));
const ktPath = process.argv[2]
  || path.join(here, '..', '..', 'android-shell', 'app', 'src', 'main', 'java', 'com', 'dsh', 'android', 'MainActivity.kt');
const kt = fs.readFileSync(ktPath, 'utf8');

const html = kt.split('const val WAIT_HTML = """')[1].split('"""')[0];
assert.ok(html.includes('<!DOCTYPE'), 'wait page html present');
const js = html.split('<script>')[1].split('</script>')[0];
// No URL/token may ever be rendered from this page: the only data keys allowed:
assert.ok(!/dsh-token|lastUrl|URL_FILE/i.test(js), 'wait page js must not touch tokens/urls');

const els = {};
const mk = (id) => (els[id] ??= { id, className: 'st', textContent: '' });
['s1', 's2', 's3', 's4', 'status', 'note', 'stamp'].forEach(mk);
const doc = { getElementById: mk };
const { setFacts } = new Function('document', js + '\nreturn { setFacts };')(doc);

let passed = 0;
const ok = (n) => { passed++; console.log('OK  ', n); };

// stage 2 — extraction in flight
setFacts({ stage: 2, failures: 0, parked: 0, stamp: 'rootfs=dsh-appliance-release-20261113-rootfs.tar.gz sha256=ab…' });
assert.equal(els.s1.className, 'st on');
assert.equal(els.s2.className, 'st act');
assert.match(els.status.textContent, /解包/);
assert.ok(els.stamp.textContent.startsWith('rootfs='), 'stamp shown');
ok('stage 2: s1 done, s2 active, extraction copy, stamp line');

// stage 3 — plain booting
setFacts({ stage: 3, failures: 0, parked: 0, stamp: null });
assert.deepEqual([els.s1.className, els.s2.className, els.s3.className], ['st on', 'st on', 'st act']);
assert.match(els.status.textContent, /就绪信号/);
assert.equal(els.note.textContent, '');
ok('stage 3: steps reset cleanly between calls (no residue)');

// stage 31 — retrying with parked profile
setFacts({ stage: 31, failures: 4, parked: 1, stamp: null });
assert.match(els.status.textContent, /重试 4 次/);
assert.match(els.note.textContent, /暂存/);
ok('stage 31: retry count + parked hint');

// stage 9 — HALT
setFacts({ stage: 9, failures: 6, parked: 1, stamp: null });
assert.equal(els.s3.className, 'st err');
assert.match(els.status.textContent, /救援/);
assert.match(els.note.textContent, /导出诊断|恢复插件/);
assert.notEqual(els.s2.className, 'st err', 'unpack step stays done, not error');
ok('stage 9: rescue framing, actionable note, honest step colors');

// hostile/odd input must not throw
setFacts({});
setFacts({ stage: 42, failures: undefined });
ok('unknown stages degrade without throwing');

console.log(`\nwait-page BEHAVIOR OK (${passed} groups)`);
