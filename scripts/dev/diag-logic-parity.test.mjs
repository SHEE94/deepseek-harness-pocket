// scripts/dev/diag-logic-parity.test.mjs
// JS-equivalent replicas of DiagnosticsExporter's two safety kernels, in the
// parity pattern (see shell-logic-parity.test.mjs header). The redaction rule
// is the P0 gate in docs/device-acceptance.md item S7: the exported bundle
// must never carry the one-shot engine token.
//
//   replica ← DiagnosticsExporter.kt url redaction (lines 61-67)
//   replica ← DiagnosticsExporter.kt putTail byte window (lines 82-92)
//
// ⚠️ If you change the Kotlin logic, mirror it HERE and keep these green.
import assert from 'node:assert/strict';

// ── redaction replica (mirrors DiagnosticsExporter.kt after the //? fix) ──────
function redact(url) {
  const base = url.split('?')[0]; // Kotlin substringBefore('?')
  return url.includes('?') ? base.replace(/\/+$/, '') + '/?…token-redacted' : base;
}

let passed = 0;
const ok = (n) => { passed++; console.log('OK  ', n); };

// R1: real-shape URL (observed live: http://127.0.0.1:PORT/?token=<high-entropy>)
{
  const token = 'FAKE-TEST-TOKEN-NOT-REAL-0000000000000000000000';
  const out = redact(`http://127.0.0.1:3081/?token=${token}`);
  assert.ok(!out.includes(token), 'token MUST be gone');
  assert.equal(out, 'http://127.0.0.1:3081/?…token-redacted');
  ok('R1 query token fully removed (S7 P0 gate)');
}
// R2: no query → origin+path verbatim
{
  assert.equal(redact('http://127.0.0.1:3080/gui'), 'http://127.0.0.1:3080/gui');
  ok('R2 no-query url passes through unchanged');
}
// R3: multiple params, token not first
{
  const out = redact('http://h:1/x?a=1&token=SECRET&b=2');
  assert.ok(!out.includes('SECRET'));
  assert.ok(!out.includes('SECRET'.toLowerCase()), 'case-insensitive absence too');
  ok('R3 token anywhere in query is removed');
}
// R4: empty query / lone '?' — the trimEnd fix keeps exactly one slash
{
  assert.equal(redact('http://h:1/?'), 'http://h:1/?…token-redacted');
  ok('R4 lone-? normalized (no double-slash artifact)');
}
// R5: known boundary — path-embedded tokens are OUT of scope (dsh serves query
// form only; if a future server ever ships /token/… URLs this replica and the
// Kotlin BOTH need updating — that coupling is the point of pinning it here).
{
  const pathForm = 'http://127.0.0.1:3081/t/SECRET123/gui';
  assert.equal(redact(pathForm), pathForm, 'boundary pinned: no path scrubbing today');
  ok('R5 path-form boundary pinned (dsh uses query form)');
}
// R6: idempotent after the trimEnd fix — redacting the marker again is stable.
{
  const once = redact('http://h:1/?token=AAA');
  assert.equal(redact(once), once, 'redaction stable under re-application');
  ok('R6 idempotence pinned');
}

// ── tail byte-window replica ─────────────────────────────────────────────────
const TAIL = 200 * 1024;
const skipFor = (size) => Math.max(0, size - TAIL);

// T1: window math
{
  assert.equal(skipFor(0), 0);
  assert.equal(skipFor(1024), 0, 'small file verbatim');
  assert.equal(skipFor(TAIL), 0, 'exactly-tail file verbatim');
  assert.equal(skipFor(TAIL + 1), 1, 'off-by-one boundary');
  assert.equal(skipFor(TAIL * 10), TAIL * 9, 'huge file keeps last TAIL bytes');
  ok('T1 tail window: never negative, exact boundary');
}
// T2: exported entry-set contract (docs + acceptance S7 reference this list)
{
  const entries = (hasUrl) => {
    const e = ['env.json', 'runtime.stamp.txt', 'rescue.json', 'incoming.txt',
      'engine-log.tail.txt', 'host-log.tail.txt'];
    if (hasUrl) e.push('url-redacted.txt');
    return e;
  };
  assert.deepEqual(entries(true), [
    'env.json', 'runtime.stamp.txt', 'rescue.json', 'incoming.txt',
    'engine-log.tail.txt', 'host-log.tail.txt', 'url-redacted.txt']);
  assert.ok(!entries(false).includes('url-redacted.txt'), 'no url file before first boot');
  // The bundle must have no *other* file that could carry the token:
  assert.ok(!entries(true).some((n) => /stamp|rescue|incoming|env/.test(n) && /url|token/i.test(n)));
  ok('T2 entry set matches documented 7-piece contract');
}

console.log(`\ndiag-logic PARITY OK (${passed} groups)`);
