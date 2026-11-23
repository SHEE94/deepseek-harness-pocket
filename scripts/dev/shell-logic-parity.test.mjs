// scripts/dev/shell-logic-parity.test.mjs
// JS-equivalent replicas of three pure-logic kernels from android-shell, in the
// proven pattern of tar-parity-lib.mjs: same semantics, executable in CI where
// Kotlin cannot be compiled locally.
//
//   replicas ← RescueLadder.onExit/shouldParkNow/restore  (RescueLadder.kt)
//   replicas ← AssetInstaller sums parsing / findPlan / stampMatches (AssetInstaller.kt)
//
// ⚠️ If you change the Kotlin logic, mirror it HERE and keep these tests green.
import assert from 'node:assert/strict';

// ─────────────────────────── RescueLadder replica ───────────────────────────
// Mirrors: LEVEL1_FAILURES=3, LEVEL2_FAILURES=6, STABLE_MS=5min; level from
// when{failures>=6→HALT; >=3→PARK_PROFILE; else→RUN}; stable exit resets the
// counter, demotes PARK→RUN but keeps HALT armed until the NEXT early exit
// re-derives the level (documented, intentional).
const LEVEL1 = 3, LEVEL2 = 6, STABLE = 5 * 60_000;

class Ladder {
  constructor() { this.failures = 0; this.level = 'RUN'; this.parked = new Map(); }
  onExit(runMillis, exitCode) {
    if (runMillis >= STABLE) {
      this.failures = 0;
      if (this.level === 'PARK_PROFILE') this.level = 'RUN';
      return 'reset';
    }
    this.failures++;
    this.level = this.failures >= LEVEL2 ? 'HALT' : this.failures >= LEVEL1 ? 'PARK_PROFILE' : 'RUN';
    return this.level;
  }
  shouldParkNow() { return this.level === 'PARK_PROFILE' && this.failures === LEVEL1; }
  park(path) { this.parked.set(path + '.disabled', path); }
  restore() {
    let n = 0;
    for (const [p, orig] of [...this.parked]) { void p; void orig; this.parked.delete(p); n++; }
    return n;
  }
  resetForManualRetry() { this.failures = 0; this.level = 'RUN'; }
  isHalted() { return this.level === 'HALT'; }
}

let passed = 0;
const ok = (n) => { passed++; console.log('OK  ', n); };

// L1: three early exits escalate to PARK exactly once (park-once semantics)
{
  const l = new Ladder();
  assert.equal(l.onExit(1000, 1), 'RUN');
  assert.equal(l.onExit(1000, 1), 'RUN');
  assert.equal(l.onExit(1000, 1), 'PARK_PROFILE');
  assert.ok(l.shouldParkNow(), 'park window opens at exactly LEVEL1');
  l.park('/profiles/web');
  assert.equal(l.onExit(1000, 1), 'PARK_PROFILE', 'failures=4 stays PARK');
  assert.ok(!l.shouldParkNow(), 'park does NOT repeat at failures=4');
  ok('ladder: escalation to PARK, park-once window');
}
// L2: sixth early exit halts auto-retry
{
  const l = new Ladder();
  for (let i = 0; i < 5; i++) l.onExit(500, 2);
  assert.equal(l.level, 'PARK_PROFILE');
  assert.equal(l.onExit(500, 2), 'HALT');
  assert.ok(l.isHalted());
  ok('ladder: HALT at 6 consecutive early exits');
}
// L3: stable run resets and demotes PARK→RUN; HALT sticky until next early exit
{
  const l = new Ladder();
  for (let i = 0; i < 3; i++) l.onExit(500, 2);
  assert.equal(l.level, 'PARK_PROFILE');
  l.onExit(30 * 60_000, 1); // stable run then crash
  assert.equal(l.failures, 0);
  assert.equal(l.level, 'RUN', 'PARK demotes after a proven-stable run');

  const h = new Ladder();
  for (let i = 0; i < 6; i++) h.onExit(500, 2);
  h.onExit(10 * 60_000, 1); // stable then crash while HALTed
  assert.equal(h.level, 'HALT', 'HALT survives stable exit (needs operator action)');
  assert.equal(h.onExit(500, 2), 'RUN', 'next early exit re-derives: fresh ladder from failures=1');
  ok('ladder: stable reset / HALT stickiness / re-derivation');
}
// L4: restore clears the parked map; manual retry re-arms
{
  const l = new Ladder();
  for (let i = 0; i < 3; i++) l.onExit(500, 2);
  l.park('/a'); l.park('/b');
  assert.equal(l.restore(), 2);
  assert.equal(l.parked.size, 0);
  l.resetForManualRetry();
  assert.equal(l.level, 'RUN');
  assert.equal(l.failures, 0);
  ok('ladder: restore + manual re-arm');
}

// ─────────────────────── SHA256SUMS parsing replica ─────────────────────────
// Mirrors: Regex("""^([0-9a-fA-F]{64})\s+\*?(.+)$""") on each trimmed line,
// key = substringAfterLast('/'), value lowercased; unparsable lines skipped.
const SUM_RE = /^([0-9a-fA-F]{64})\s+\*?(.+)$/;
function parseSums(text) {
  const out = new Map();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    const m = SUM_RE.exec(line);
    if (m) out.set(m[2].split('/').pop(), m[1].toLowerCase());
  }
  return out;
}
// S1: sha256sum -c standard format + binary marker + windows CRLF + dir prefixes
{
  const H = 'a'.repeat(64);
  const sums = parseSums(
    `${'b'.repeat(64)}  dsh-appliance-1-rootfs.tar.gz\r\n` +
    `${H} *./sub/proot\r\n` +
    `not-a-line\r\n` +
    `\r\n` +
    `${'C'.repeat(64)}  SHA256SUMS.txt\r\n`,
  );
  assert.equal(sums.get('dsh-appliance-1-rootfs.tar.gz'), 'b'.repeat(64));
  assert.equal(sums.get('proot'), 'a'.repeat(64), 'binary marker + path prefix handled');
  assert.equal(sums.get('SHA256SUMS.txt'), 'c'.repeat(64), 'hex case normalized');
  assert.equal(sums.size, 3, 'junk lines skipped');
  ok('sums: standard/binary/CRLF/dir-prefix/case/junk handling');
}
// S2: verify-or-skip semantics (no declared checksum ⇒ tolerant pass)
{
  const sums = parseSums(`${'d'.repeat(64)}  known.bin\n`);
  assert.ok(!sums.has('unknown.bin'), 'undeclared file ⇒ no checksum enforced (tolerant pass)');
  assert.equal(sums.get('known.bin'), 'd'.repeat(64));
  ok('sums: declared-only enforcement matches verifyOrThrow');
}

// ─────────────────────── findPlan / stampMatches replicas ───────────────────
// findPlan: files matching name contains '-rootfs.tar', newest by mtime.
// stampMatches: stamp contains `rootfs=<name>` AND `sha256=<hash>`.
function findPlan(files) {
  const cand = files.filter((f) => f.isFile && f.name.includes('-rootfs.tar'));
  if (!cand.length) return null;
  return cand.reduce((a, b) => (b.mtime > a.mtime ? b : a));
}
function stampMatches(stamp, name, hash) {
  return stamp.includes(`rootfs=${name}`) && stamp.includes(`sha256=${hash}`);
}
// P1: newest rootfs wins; near-miss names rejected
{
  const plan = findPlan([
    { isFile: true, name: 'dsh-appliance-0.9-rootfs.tar.gz', mtime: 100 },
    { isFile: true, name: 'dsh-appliance-1.0-rootfs.tar.gz', mtime: 200 },
    { isFile: true, name: 'rootfs-notes.txt', mtime: 300 },          // no dash prefix
    { isFile: true, name: 'proot-bundle.tar.gz', mtime: 400 },       // different bundle
    { isFile: false, name: 'x-rootfs.tar.gz.dir', mtime: 500 },      // dir rejected
  ]);
  assert.equal(plan.name, 'dsh-appliance-1.0-rootfs.tar.gz');
  assert.equal(findPlan([{ isFile: true, name: 'readme.md', mtime: 1 }]), null);
  ok('findPlan: newest-match selection with rejection rules');
}
// P2: stamp must match BOTH name and hash (version-stamp discipline)
{
  const stamp = 'rootfs=a-rootfs.tar.gz sha256=beef proot=none installedAt=2026-11-13T00:00:00';
  assert.ok(stampMatches(stamp, 'a-rootfs.tar.gz', 'beef'));
  assert.ok(!stampMatches(stamp, 'b-rootfs.tar.gz', 'beef'), 'renamed asset re-stages');
  assert.ok(!stampMatches(stamp, 'a-rootfs.tar.gz', 'dead'), 'changed content re-stages');
  assert.ok(!stampMatches('', 'a-rootfs.tar.gz', 'beef'), 'missing stamp re-stages');
  ok('stamp: both name AND hash must match or re-stage (no stale-asset trap)');
}

console.log(`\nshell-logic PARITY OK (${passed} groups)`);
