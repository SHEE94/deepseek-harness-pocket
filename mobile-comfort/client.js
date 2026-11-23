// Mobile comfort — Client module for DSH Web GUI on touch devices (v0.4).
// Chip row in `conversation.composer.dock` + opt-out comfort stylesheet.
// v0.4 (review round 2): honest a11y names (no double-speak), stable React keys,
// ⓘ-collapsible hint (no layout jumps, persist failures surfaced), capability
// probes on every chip, localStorage as the single writer of the sheet state
// (+ cross-tab sync), visualViewport keyboard retreat, per-direction nudge
// throttle, host-owns-limits, low-WebView fallbacks.
window.__ModuleLoader__.load({
  id: '@local/dsh-mobile-comfort',
  factory(require) {
    const React = require('react');
    const h = React.createElement;

    // Primary gate is pointer/hover capability, not width — a narrow desktop
    // window keeps desktop behavior.
    const TOUCH = '@media (pointer: coarse), (hover: none)';

    const COMFORT_CSS = [
      TOUCH + ' {',
      '  html { -webkit-text-size-adjust: 100%; }',
      '  html, body { overscroll-behavior-y: contain; }',
      '  button, a, [role="button"], [role="menuitem"] {',
      '    touch-action: manipulation;',
      // Keep a PRESS FEEDBACK (transparent would leave a vacuum). Neutral
      // fallback first, brand-tinted upgrade where color-mix exists (S9).
      '    -webkit-tap-highlight-color: rgba(0,0,0,.08);',
      '    -webkit-tap-highlight-color:',
      '      color-mix(in srgb, var(--dsw-alias-brand-primary, currentColor) 18%, transparent);',
      '  }',
      '  textarea, input:not([type="checkbox"]):not([type="radio"]), select {',
      '    font-size: max(1em, 16px) !important;', /* iOS focus-zoom guard; !important beats host class rules (S9) */
      '  }',
      '  * { scrollbar-width: thin; }',
      '  ::-webkit-scrollbar { width: 6px; height: 6px; }',
      '}',
    ].join('\n');

    const ROOT_CSS = [
      '.dmc-root { display: flex; gap: 8px; align-items: center; flex-wrap: nowrap;',
      '  overflow-x: auto; scrollbar-width: none; max-width: 100%;',
      '  padding: 2px 6px calc(4px + min(env(safe-area-inset-bottom, 0px), 8px));',
      '  -webkit-mask-image: linear-gradient(to right, #000 calc(100% - 20px), transparent);',
      '  mask-image: linear-gradient(to right, #000 calc(100% - 20px), transparent); }',
      '.dmc-root::-webkit-scrollbar { display: none; }',
      '.dmc-chip { display: inline-flex; align-items: center; gap: 6px; flex: none;',
      '  min-height: 44px; padding: 10px 16px; border-radius: 999px;',
      // clamp caps reflow when the user enlarges text (S3): chips stop growing at 18px.
      '  font-size: clamp(14px, .85em, 18px); line-height: 1; cursor: pointer;',
      '  background: transparent; border: 1px solid transparent;',
      '  color: var(--dsw-alias-label-secondary, currentColor); }',
      '.dmc-chip:active { opacity: .55; transform: scale(.97); }',
      '.dmc-chip:focus-visible { outline: 2px solid var(--dsw-alias-brand-primary, currentColor);',
      '  outline-offset: 2px; }',
      // On/pressed state is a FILL, not only a border recolor.
      '.dmc-chip[aria-pressed="true"] { color: var(--dsw-alias-brand-primary, currentColor);',
      '  background: var(--dsw-alias-bg-layer-1, transparent);',
      '  border-color: var(--dsw-alias-border-l2, currentColor); }',
      '.dmc-chip[disabled] { opacity: .45; cursor: default; }',
      // ≥18px effective separation between the opposite-direction Aa pair.
      '.dmc-gap { flex: none; width: 10px; }',
      // Fixed-height status line: it never replaces the hint panel and never jumps layout (M2).
      '.dmc-status { padding: 0 10px 4px; font-size: max(12px, .8em);',
      '  color: var(--dsw-alias-label-secondary, currentColor); min-height: 20px; }',
      '.dmc-note { padding: 0 10px 8px; font-size: max(12px, .8em);',
      '  color: var(--dsw-alias-label-secondary, currentColor); line-height: 1.55; }',
      '.dmc-sr { position: absolute; width: 1px; height: 1px; overflow: hidden;',
      '  clip: rect(0 0 0 0); white-space: nowrap; }',
      // Desktop (fine pointer WITH hover) hides everything — width never re-enables.
      '@media (pointer: fine) and (hover: hover) { .dmc-root, .dmc-status, .dmc-note { display: none; } }',
      // Static fallback for engines without visualViewport (JS check does the real work).
      '@media (max-height: 500px) { .dmc-note { display: none; } }',
    ].join('\n');

    const LS_STYLE = 'dsh-mobile-comfort:styles';
    const LS_HINT = 'dsh-mobile-comfort:hint';
    const readFlag = (key, dflt) => { try { const v = localStorage.getItem(key); return v === null ? dflt : v === 'on'; } catch { return dflt; } };
    const writeFlag = (key, on) => { try { localStorage.setItem(key, on ? 'on' : 'off'); return true; } catch { return false; } };

    const DICTS = {
      en: {
        title: 'Mobile comfort',
        sidebar: 'Sidebar', sidebarUnavailable: 'Sidebar unavailable',
        larger: 'Text +', largerTitle: 'Increase all text sizes',
        smaller: 'Text −', smallerTitle: 'Decrease all text sizes',
        styles: 'Touch boost', stylesOn: 'Touch boost on', stylesOff: 'Touch boost off',
        info: 'Help', infoOn: 'Hide help', infoOff: 'Show help',
        hint: '“Sidebar” folds the left column; “Text +/−” scales all text (the host owns each limit); “Touch boost” toggles press feedback, focus-zoom guard and thin scrollbars.',
        atMax: 'Maximum text size reached — use “Text −” to step back',
        atMin: 'Minimum text size reached — use “Text +” to step up',
        partialNote: 'Some text hit their own limits',
        themeFail: 'Text sizing is unavailable here — try Settings → Appearance',
        persistFail: 'This setting will not persist (storage unavailable)',
        update: 'Check update', updateTitle: 'Check for a newer DSH engine',
        updateSent: 'Update check started — watch the notification',
        updateNoBridge: 'In-app updates are only available in the DSH Android app',
        updateFail: 'Could not reach the app updater — retry from the notification',
      },
      zh: {
        title: '移动端舒适包',
        sidebar: '侧栏', sidebarUnavailable: '侧栏不可用',
        larger: '字号+', largerTitle: '整体调大文字',
        smaller: '字号−', smallerTitle: '整体调小文字',
        styles: '触摸优化', stylesOn: '触摸优化已开启', stylesOff: '触摸优化已关闭',
        info: '说明', infoOn: '收起说明', infoOff: '查看说明',
        hint: '「侧栏」收起/展开左列；「字号+/−」调节整体字号（各角色上限由系统外观设置决定）；「触摸优化」开关点按反馈、输入防跳动与细滚动条。',
        atMax: '字号已到最大，可用「字号−」回调',
        atMin: '字号已到最小，可用「字号+」回调',
        partialNote: '部分文字已达各自上限',
        themeFail: '当前无法调整字号，可到 设置→外观 调整',
        persistFail: '本次设置不会保留（浏览器存储不可用）',
        update: '检查更新', updateTitle: '检查新版 DSH 引擎（app 内更新）',
        updateSent: '已开始检查，进度见系统通知栏',
        updateNoBridge: '仅 DSH Android app 内支持检查更新',
        updateFail: '未能连接到 app 更新器，可从通知栏重试',
      },
    };

    function langOf(locale) {
      try {
        const snap = locale && typeof locale.getLocale === 'function' ? locale.getLocale() : null;
        const id = snap && (snap.id || snap.locale || snap.language);
        if (typeof id === 'string' && id) return id.toLowerCase().startsWith('zh') ? 'zh' : 'en';
      } catch { /* fall through */ }
      const nav = (typeof navigator !== 'undefined' && navigator.language) || 'en';
      return nav.toLowerCase().startsWith('zh') ? 'zh' : 'en';
    }

    return {
      inject: ['slots', 'layout', 'theme', 'locale'],
      apply(ctx) {
        // Comfort stylesheet element (owned + cleaned by ctx.effect).
        let sheet = null;
        ctx.effect(() => {
          sheet = document.createElement('style');
          sheet.setAttribute('data-dsh-mobile-comfort', '');
          sheet.textContent = COMFORT_CSS;
          sheet.disabled = !readFlag(LS_STYLE, true);
          document.head.appendChild(sheet);
          return () => { sheet.remove(); sheet = null; };
        });

        // Re-render bridges (components register their hooks).
        const bump = { fn: null };
        const sync = { fn: null };
        const kbSync = { fn: null };

        ctx.effect(() => {
          try {
            const off = ctx.locale && typeof ctx.locale.subscribe === 'function'
              ? ctx.locale.subscribe(() => { if (bump.fn) bump.fn(); })
              : null;
            return () => { try { if (off) off(); } catch { /* gone */ } };
          } catch { return () => {}; }
        });

        // localStorage is the single writer of the sheet state (S6); other tabs
        // and app-foregrounding pull it back in sync (S5).
        ctx.effect(() => {
          const onStore = (e) => { if ((!e || !e.key || e.key === LS_STYLE) && sync.fn) sync.fn(); };
          const onVis = () => { if (document.visibilityState === 'visible' && sync.fn) sync.fn(); };
          window.addEventListener('storage', onStore);
          document.addEventListener('visibilitychange', onVis);
          return () => {
            window.removeEventListener('storage', onStore);
            document.removeEventListener('visibilitychange', onVis);
          };
        });

        // Keyboard state via visualViewport (S1): media queries alone miss Chrome PWA.
        ctx.effect(() => {
          const vv = typeof window !== 'undefined' ? window.visualViewport : null;
          if (!vv || typeof vv.addEventListener !== 'function') return () => {};
          const onResize = () => { if (kbSync.fn) kbSync.fn(window.innerHeight - vv.height > 150); };
          vv.addEventListener('resize', onResize);
          onResize();
          return () => { try { vv.removeEventListener('resize', onResize); } catch { /* gone */ } };
        });

        // Capability probes once at apply — unavailable actions render disabled.
        const sidebarOk = !!(ctx.layout && typeof ctx.layout.toggleSidebar === 'function');
        const themeOk = !!(ctx.theme && typeof ctx.theme.getTheme === 'function'
          && typeof ctx.theme.setFontSize === 'function');
        // Native update bridge (shell WebView addJavascriptInterface 'DshAndroid').
        // Desktop browsers / plain Chrome: absent → chip renders disabled with hint.
        const bridgeOk = typeof window !== 'undefined' && !!window.DshAndroid
          && typeof window.DshAndroid.checkUpdate === 'function';
        // Per-direction throttle (S2): a corrective opposite tap is never swallowed.
        let lastNudge = { dir: 0, at: 0 };

        function Chips() {
          const [stylesOn, setStylesOn] = React.useState(readFlag(LS_STYLE, true));
          const [infoOpen, setInfoOpen] = React.useState(() => !readFlag(LS_HINT, false));
          const [status, setStatus] = React.useState('');
          const [kb, setKb] = React.useState(false);
          const [, setTick] = React.useState(0);

          React.useEffect(() => {
            const rerender = () => setTick((x) => x + 1);
            bump.fn = rerender;
            return () => { if (bump.fn === rerender) bump.fn = null; }; // S12 identity check
          }, []);
          React.useEffect(() => {
            const pull = () => setStylesOn(readFlag(LS_STYLE, true));
            sync.fn = pull;
            pull(); // mount-time re-read: state re-converges onto storage (S6)
            return () => { if (sync.fn === pull) sync.fn = null; };
          }, []);
          React.useEffect(() => {
            kbSync.fn = (on) => { setKb((prev) => (prev === on ? prev : on)); };
            return () => { if (kbSync.fn) kbSync.fn = null; };
          }, []);
          // Sheet state flows one way: storage → state → sheet.
          React.useEffect(() => { if (sheet) sheet.disabled = !stylesOn; }, [stylesOn]);
          React.useEffect(() => {
            if (!status) return;
            const timer = setTimeout(() => setStatus(''), 4000);
            return () => clearTimeout(timer);
          }, [status]);

          const lang = langOf(ctx.locale);
          const dict = DICTS[lang] || DICTS.en;
          const t = (k) => dict[k] || DICTS.en[k] || k;

          const toggleStyles = () => {
            const next = !stylesOn;
            setStylesOn(next);
            if (sheet) sheet.disabled = !next; // immediate; sync effect is the guard
            if (!writeFlag(LS_STYLE, next)) setStatus(t('persistFail'));
            else setStatus(next ? t('stylesOn') : t('stylesOff'));
          };

          const toggleInfo = () => {
            const next = !infoOpen;
            setInfoOpen(next);
            if (!next && !writeFlag(LS_HINT, true)) setStatus(t('persistFail')); // M3
          };

          const nudge = (delta) => {
            const dir = delta > 0 ? 1 : -1;
            const now = Date.now();
            if (lastNudge.dir === dir && now - lastNudge.at < 150) return; // S2
            lastNudge = { dir, at: now };
            if (!themeOk) { setStatus(t('themeFail')); return; }
            try {
              const snap = ctx.theme.getTheme();
              const sizes = (snap && snap.fontSizes) || {};
              let applied = 0, limited = 0;
              for (const role of Object.keys(sizes)) {
                const next = Math.round(sizes[role]) + delta;
                // The host owns the real per-role limits (S7): try and let it throw.
                try { ctx.theme.setFontSize(role, next); applied++; } catch { limited++; }
              }
              if (applied === 0) {
                // S8: only claim a limit when the host actually enforced one.
                setStatus(limited > 0 ? (dir > 0 ? t('atMax') : t('atMin')) : t('themeFail'));
              } else if (limited > 0) {
                setStatus(t('partialNote'));
              } else {
                setStatus('');
              }
            } catch { setStatus(t('themeFail')); }
          };

          // Stable React keys (M5); sr span only when it adds meaning (M1);
          // icons aria-hidden; labels carry meaning on their own.
          const chip = (key, label, srTitle, onClick, opts) => {
            const o = opts || {};
            const srExtra = srTitle && srTitle !== label ? srTitle : null;
            return h('button', {
              key,
              className: 'dmc-chip',
              type: 'button',
              onClick,
              disabled: o.disabled || undefined,
              'aria-pressed': o.pressed !== undefined ? String(o.pressed) : undefined,
            },
              o.icon
                ? h('svg', {
                    viewBox: '0 0 16 16', width: 16, height: 16, 'aria-hidden': 'true',
                    style: { flex: 'none' },
                  }, (Array.isArray(o.icon) ? o.icon : [o.icon]).map((d, i) => h('path', {
                    key: i, d, fill: 'none', stroke: 'currentColor',
                    'stroke-width': 1.6, 'stroke-linecap': 'round', 'stroke-linejoin': 'round',
                  })))
                : null,
              h('span', null, label),
              srExtra ? h('span', { className: 'dmc-sr' }, srExtra) : null,
            );
          };

          return h(React.Fragment, null,
            // Component-local style element: mounts with the chips, removed with them.
            h('style', { 'data-dsh-mobile-comfort-root': '' }, ROOT_CSS),
            h('div', { className: 'dmc-root', role: 'group', 'aria-label': t('title') },
              chip('sidebar',
                sidebarOk ? t('sidebar') : t('sidebarUnavailable'),
                sidebarOk ? t('sidebar') : t('sidebarUnavailable'),
                () => {
                  try { ctx.layout.toggleSidebar(); } catch { setStatus(t('sidebarUnavailable')); }
                },
                { disabled: !sidebarOk, icon: 'M2.5 2.5h11v11h-11zM6 2.5v11' }),
              chip('larger', t('larger'), themeOk ? t('largerTitle') : t('themeFail'),
                () => nudge(+2), { disabled: !themeOk, icon: 'M8 3.5v9M3.5 8h9' }), // M4
              h('span', { key: 'gap', className: 'dmc-gap', 'aria-hidden': 'true' }),
              chip('smaller', t('smaller'), themeOk ? t('smallerTitle') : t('themeFail'),
                () => nudge(-2), { disabled: !themeOk, icon: 'M3.5 8h9' }), // M4
              chip('styles', t('styles'), stylesOn ? t('stylesOn') : t('stylesOff'),
                toggleStyles, { pressed: stylesOn, icon: ['M8 8.01v.01', 'M5.1 5.1a4.1 4.1 0 0 1 5.8 0', 'M2.9 2.9a7.2 7.2 0 0 1 10.2 0'] }),
              chip('info', t('info'), infoOpen ? t('infoOn') : t('infoOff'),
                toggleInfo, { pressed: infoOpen, icon: ['M8 7.4v4', 'M8 4.6v.01'] }),
              h('span', { key: 'gap2', className: 'dmc-gap', 'aria-hidden': 'true' }),
              chip('update', t('update'), bridgeOk ? t('updateTitle') : t('updateNoBridge'),
                () => {
                  if (!bridgeOk) { setStatus(t('updateNoBridge')); return; }
                  try {
                    window.DshAndroid.checkUpdate();
                    setStatus(t('updateSent'));
                  } catch { setStatus(t('updateFail')); }
                },
                { disabled: !bridgeOk, icon: ['M8 2.5v7', 'M5 6.5 8 9.5l3-3', 'M3 11.5v2h10v-2'] }),
            ),
            // Hint panel: collapsible via ⓘ, hidden under the keyboard (S1).
            (infoOpen && !kb) ? h('div', { className: 'dmc-note' }, h('span', null, t('hint'))) : null,
            // Status: fixed-height line, independent of the hint (M2).
            status ? h('div', { className: 'dmc-status', role: 'status', 'aria-live': 'polite' }, h('span', null, status)) : null,
          );
        }

        ctx.slots.inject('conversation.composer.dock', () => ctx.slots.register({
          name: 'conversation.composer.dock',
          id: 'dsh-mobile-comfort',
          order: 50,
          label: () => (DICTS[langOf(ctx.locale)] || DICTS.en).title,
        }, Chips));
      },
    };
  },
});
