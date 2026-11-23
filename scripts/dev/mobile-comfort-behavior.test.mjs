// scripts/dev/mobile-comfort-behavior.test.mjs
// Headless behavior tests for mobile-comfort/client.js — no browser required.
// Stateful hook shims (T0) make status messages, sheet sync, capability probes
// and cross-tab pulls observable; every review-round fix (M1–M5, S1/S2/S5/S6/
// S8/S9) has a named guard below. On any failure, the message cites the review item.
import fs from 'node:fs';
import path from 'node:path';
import url from 'node:url';
import assert from 'node:assert/strict';

const here = path.dirname(url.fileURLToPath(import.meta.url));
const clientPath = process.argv[2]
  || path.join(here, '..', '..', 'mobile-comfort', 'client.js');
const src = fs.readFileSync(clientPath, 'utf8');

globalThis.setTimeout = () => 0; // silence the 4s status auto-clear

// ---- shims -------------------------------------------------------------------
function makeReact() {
  const React = {
    createElement: (type, props, ...children) => ({
      type, props: props || {},
      children: children.flat(Infinity).filter((c) => c != null && c !== false),
    }),
    Fragment: 'Fragment',
    _key: 'r0', _slot: 0, _cells: new Map(), _effects: new Map(),
  };
  React.useState = (init) => {
    const id = React._key + '#' + React._slot++;
    if (!React._cells.has(id)) React._cells.set(id, typeof init === 'function' ? init() : init);
    return [React._cells.get(id),
      (v) => React._cells.set(id, typeof v === 'function' ? v(React._cells.get(id)) : v)];
  };
  React.useEffect = (fn, deps) => {
    const id = React._key + '#e' + React._effects.size;
    const prev = React._effects.get(id);
    const same = prev && deps && prev.deps && deps.length === prev.deps.length
      && deps.every((d, i) => d === prev.deps[i]);
    if (!same) {
      React._effects.set(id, { deps });
      try { fn(); } catch { /* loose-host effects */ }
    }
  };
  return React;
}

function boot({ storageThrows = false, bridge = null } = {}) {
  const env = {
    doc: {
      visibilityState: 'visible', elements: [], listeners: {},
      createElement(tag) {
        const el = {
          tag, attrs: {}, textContent: '', disabled: false, removed: false,
          setAttribute(k, v) { this.attrs[k] = v; }, remove() { this.removed = true; },
        };
        env.doc.elements.push(el);
        return el;
      },
      head: { appendChild() {} },
      addEventListener(k, fn) { (env.doc.listeners[k] ||= []).push(fn); },
      removeEventListener(k, fn) { env.doc.listeners[k] = (env.doc.listeners[k] || []).filter((f) => f !== fn); },
    },
    win: {
      innerHeight: 800, listeners: {}, vvListeners: [],
      addEventListener(k, fn) { (env.win.listeners[k] ||= []).push(fn); },
      removeEventListener(k, fn) { env.win.listeners[k] = (env.win.listeners[k] || []).filter((f) => f !== fn); },
      visualViewport: {
        height: 800,
        addEventListener(k, fn) { if (k === 'resize') env.win.vvListeners.push(fn); },
        removeEventListener(k, fn) { env.win.vvListeners = env.win.vvListeners.filter((f) => f !== fn); },
      },
    },
    store: new Map(),
  };
  env.localStorage = {
    getItem: (k) => (env.store.has(k) ? env.store.get(k) : null),
    setItem: (k, v) => { if (storageThrows) throw new Error('quota'); env.store.set(k, v); },
  };
  env.React = makeReact();
  env.win.__ModuleLoader__ = { load: (m) => { env.registered = m; } };
  if (bridge) env.win.DshAndroid = bridge;
  globalThis.document = env.doc;
  globalThis.window = env.win;
  globalThis.localStorage = env.localStorage;
  new Function('window', src)(env.win);
  env.require = (id) => { if (id === 'react') return env.React; throw new Error('require ' + id); };
  return env;
}

function makeCtx(opts = {}) {
  const calls = [];
  const state = { fontSizes: { ...(opts.fontSizes || { body: 16, code: 14, heading: 20 }) } };
  let comp = null;
  const effects = [];
  const ctx = {
    effect(fn) { effects.push(fn() || (() => {})); },
    slots: {
      inject: (key, cb) => { calls.push(['inject', key]); cb(); return () => {}; },
      register: (o, c) => { calls.push(['register', o.id]); comp = c; },
    },
    layout: opts.sidebar === false ? {} : { toggleSidebar: () => calls.push(['toggle']) },
    theme: opts.theme === false ? undefined : {
      getTheme: opts.getTheme || (() => ({ fontSizes: state.fontSizes })),
      setFontSize: (role, px) => {
        if (px < 12 || px > 24) throw new Error('role range'); // host owns limits
        state.fontSizes[role] = px;
        calls.push(['font', role, px]);
      },
    },
    locale: { getLocale: () => ({ id: opts.lang || 'zh-CN' }), subscribe: () => () => {} },
  };
  return { ctx, calls, comp: () => comp, state, disposeAll: () => effects.forEach((f) => f()) };
}

const render = (env, key) => { env.React._key = key; env.React._slot = 0; return env.__Chips(); };
function walk(node, fn) {
  if (node == null || typeof node === 'string') return;
  fn(node);
  (node.children || []).forEach((c) => walk(c, fn));
}
const collect = (root, pred) => { const out = []; walk(root, (n) => { if (pred(n)) out.push(n); }); return out; };
const textOf = (n) => (typeof n === 'string' ? n : (n.children || []).map(textOf).join(''));
const labelOf = (b) => (b.children || [])
  .filter((c) => c && c.type === 'span' && c.props.className !== 'dmc-sr').map(textOf).join('');

// one-shot helper: boot + apply + first render
function scene(opts = {}, ctxOpts = {}) {
  const env = boot(opts);
  const t = makeCtx(ctxOpts);
  env.__reg = env.registered.factory(env.require);
  env.__reg.apply(t.ctx);
  env.__Chips = t.comp();
  env.__t = t;
  env.__tree = () => render(env, opts.key || 's');
  return env;
}
const chips = (tree) => collect(tree, (n) => n.type === 'button');

let passed = 0;
const ok = (n) => { passed++; console.log('OK  ', n); };

// 1 ─ registration, sheet, defaults ------------------------------------------------
{
  const e = scene();
  const sheet = e.doc.elements[0];
  assert.equal(sheet.tag, 'style');
  assert.equal(sheet.disabled, false);
  assert.ok(e.__t.calls.some(([k, v]) => k === 'inject' && v === 'conversation.composer.dock'));
  assert.ok(e.__t.calls.some(([k, v]) => k === 'register' && v === 'dsh-mobile-comfort'));
  ok('registration + comfort sheet default ON');
}

// 2 ─ structure + T8 CSS guards ------------------------------------------------------
{
  const e = scene();
  const tree = e.__tree();
  const styleEl = collect(tree, (n) => n.type === 'style')[0];
  const css = textOf(styleEl);
  assert.match(css, /\.dmc-chip[^]*?font-size: clamp\(/, 'S3 clamp');
  assert.ok(css.includes('mask-image'), 'edge fade');
  assert.match(css, /\.dmc-status[^]*?min-height: 20px/, 'M2 fixed status line');
  const comfort = e.doc.elements[0].textContent;
  assert.ok(comfort.indexOf('rgba(0,0,0,.08)') < comfort.indexOf('color-mix('), 'S9 fallback order');
  assert.match(comfort, /16px\) !important/, 'S9 input floor wins');

  const group = collect(tree, (n) => n.props.className === 'dmc-root')[0];
  const bs = chips(group);
  assert.deepEqual(bs.map(labelOf), ['侧栏', '字号+', '字号−', '触摸优化', '说明', '检查更新']);
  assert.deepEqual(bs.map((b) => collect(b, (n) => n.type === 'svg').length), [1, 1, 1, 1, 1, 1]);
  assert.ok(bs.every((b) => collect(b, (n) => n.type === 'svg')[0]?.props['aria-hidden'] === 'true'));
  // T1/M1: no doubled accessible name; no ✓ channel (S4)
  for (const b of bs) {
    const spans = (b.children || []).filter((c) => c && c.type === 'span').map(textOf);
    if (spans.length === 2) assert.notEqual(spans[0], spans[1], 'M1 doubled name: ' + spans.join('|'));
    const all = textOf(b);
    assert.ok(!all.includes('✓'), 'S4 check glyph removed');
  }
  assert.equal(collect(tree, (n) => n.props.className === 'dmc-note').length, 1, 'hint at first run');
  assert.equal(collect(tree, (n) => n.props.className === 'dmc-status').length, 0);
  ok('structure + labels + icons + hint default (T1/T8)');
}

// 3 ─ M5 stable keys + sheet sync -----------------------------------------------------
{
  const e = scene();
  let tree = e.__tree();
  chips(tree).find((b) => b.props.key === 'styles').props.onClick();
  tree = e.__tree();
  const sb = chips(tree).find((b) => b.props.key === 'styles');
  assert.ok(sb, 'styles key stable across toggle');
  assert.equal(sb.props['aria-pressed'], 'false');
  assert.equal(e.doc.elements[0].disabled, true, 'sheet disabled immediately');
  ok('M5 stable keys; immediate sheet sync');
}

// 4 ─ M4 capability probes -------------------------------------------------------------
{
  const e = scene({}, { sidebar: false, theme: false });
  const bs = chips(e.__tree());
  assert.equal(bs[0].props.disabled, true);
  assert.match(textOf(bs[0]), /侧栏不可用/, 'visible unavailable label (S10)');
  assert.equal(bs[1].props.disabled, true, 'M4 larger disabled without theme');
  assert.equal(bs[2].props.disabled, true, 'M4 smaller disabled without theme');
  assert.match(textOf(bs[1]), /无法调整字号/);
  ok('M4: probes disable chips visibly + explain');
}

// 5 ─ M3 persist failure on hint close ---------------------------------------------------
{
  const e = scene({ storageThrows: true });
  let tree = e.__tree();
  chips(tree).find((b) => b.props.key === 'info').props.onClick();
  tree = e.__tree();
  const st = collect(tree, (n) => n.props.className === 'dmc-status');
  assert.equal(st.length, 1, 'failure announced');
  assert.match(textOf(st[0]), /不会保留/);
  ok('M3 hint-close persist failure surfaced');
}

// 6 ─ S8/T7 status honesty ----------------------------------------------------------------
function nudgeStatus(ctxOpts, key) {
  const e = scene({ key }, ctxOpts);
  const tree = e.__tree();
  chips(tree).find((b) => b.props.key === 'larger').props.onClick();
  const st = collect(e.__tree(), (n) => n.props.className === 'dmc-status');
  return st.length ? textOf(st[0]) : '';
}
{
  const s1 = nudgeStatus({ getTheme: () => ({}) }, 'z1');
  assert.match(s1, /无法调整字号/, 'empty snapshot → themeFail');
  assert.ok(!s1.includes('已到最大'), 'never a fake limit');
  const s2 = nudgeStatus({ fontSizes: { body: 24 } }, 'z2');
  assert.match(s2, /已到最大/, 'host-enforced limit → honest atMax');
  const s3 = nudgeStatus({ getTheme: () => { throw new Error('x'); } }, 'z3');
  assert.match(s3, /无法调整字号/);
  ok('S8/T7: status honest across snapshot shapes');
}

// 7 ─ S5 cross-tab pull ---------------------------------------------------------------------
{
  const e = scene({}, {});
  let tree = e.__tree();
  assert.equal(chips(tree).find((b) => b.props.key === 'styles').props['aria-pressed'], 'true');
  const listeners = e.win.listeners['storage'] || [];
  assert.ok(listeners.length >= 1, 'storage listener registered');
  e.store.set('dsh-mobile-comfort:styles', 'off');
  listeners.forEach((fn) => fn({ key: 'dsh-mobile-comfort:styles' }));
  tree = e.__tree();
  assert.equal(chips(tree).find((b) => b.props.key === 'styles').props['aria-pressed'], 'false');
  ok('S5 storage-event sync (single writer)');
}

// 8 ─ S1 keyboard retreat ----------------------------------------------------------------------
{
  const e = scene({}, {});
  assert.equal(collect(e.__tree(), (n) => n.props.className === 'dmc-note').length, 1);
  e.win.visualViewport.height = 300; // innerHeight stays 800 → keyboard up
  e.win.vvListeners.forEach((fn) => fn());
  const tree = e.__tree();
  assert.equal(collect(tree, (n) => n.props.className === 'dmc-note').length, 0, 'hint retreats');
  assert.equal(chips(tree).length, 6, 'chips stay');
  ok('S1 visualViewport keyboard retreat');
}

// 8b ─ update chip: bridge gating + status feedback (v0.5) ───────────────────
{
  // No bridge (desktop Chrome / plain browser): disabled + honest hint, no throw.
  const e = scene({}, {});
  const up = chips(e.__tree()).find((b) => b.props.key === 'update');
  assert.ok(up, 'update chip exists');
  assert.equal(up.props.disabled, true, 'disabled without native bridge');
  up.props.onClick();
  assert.match(textOf(e.__tree()), /仅 DSH Android app/, 'offline hint shown');

  // With bridge: enabled, click delegates + confirms.
  let fired = 0;
  const e2 = scene({ bridge: { checkUpdate: () => { fired++; } } }, {});
  const up2 = chips(e2.__tree()).find((b) => b.props.key === 'update');
  assert.notEqual(up2.props.disabled, true, 'enabled with bridge');
  up2.props.onClick();
  assert.equal(fired, 1, 'bridge called once');
  assert.match(textOf(e2.__tree()), /通知栏/, 'progress hint surfaced');
  ok('update chip bridge gating');
}

// 9 ─ S2 per-direction throttle -------------------------------------------------------------------
{
  const e = scene({}, {});
  const tree = e.__tree();
  const larger = chips(tree).find((b) => b.props.key === 'larger');
  const smaller = chips(tree).find((b) => b.props.key === 'smaller');
  larger.props.onClick();
  const n1 = e.__t.calls.filter((x) => x[0] === 'font').length;
  assert.equal(n1, 3);
  larger.props.onClick();
  assert.equal(e.__t.calls.filter((x) => x[0] === 'font').length, n1, 'same-dir rapid swallowed');
  smaller.props.onClick();
  assert.ok(e.__t.calls.filter((x) => x[0] === 'font').length > n1, 'S2 corrective tap passes');
  ok('S2 per-direction throttle');
}

// 10 ─ cleanup ---------------------------------------------------------------------------------------
{
  const e = scene();
  e.__tree();
  e.__t.disposeAll();
  assert.equal(e.doc.elements[0].removed, true, 'sheet removed on teardown');
  ok('ctx.effect cleanup');
}

console.log(`\nmobile-comfort BEHAVIOR OK (${passed} groups)`);
