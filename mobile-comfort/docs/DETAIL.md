# Mobile comfort

Touch ergonomics for the DSH Web GUI on phones and tablets. Designed for the
DSH-on-Android deployment (Termux + proot running `dsh --profile web`, GUI
opened in Chrome/PWA or the shell APK's WebView), and useful in any touch browser.

## Gating

The primary gate is pointer capability, not viewport width: the row appears on
`pointer: coarse` or `hover: none`, and disappears on a desktop pointer
(`pointer: fine` **and** `hover: hover`) — a half-width desktop window never
gets mobile chrome.

## Features

**Chip row (below the composer, touch viewports only)**

| Chip | Behavior |
|---|---|
| Sidebar | Fold/unfold the session column via the official `layout.toggleSidebar`. Renders visibly disabled ("Sidebar unavailable") when the capability probe fails |
| Text + / Text − | Step all text sizes by ±2px through the official `theme.setFontSize`. The host owns every per-role limit; hitting one says so, with the way back ("use Text −"). Disabled when no theme service |
| Touch boost | Toggle the comfort stylesheet; on-state is a filled chip (not a 1px border). Applies instantly and persists per browser |
| Help | Collapsible explanation panel (pressed-state). No tiny × buttons anywhere |

**Comfort stylesheet (touch gating only)**

- press feedback kept but tinted with the brand token (`color-mix`, with a
  neutral `rgba` fallback for WebViews below Chromium 111) instead of the
  system gray flash — never removed entirely
- `touch-action: manipulation` (kills double-tap-zoom delay); `-webkit-text-size-adjust` locked
- 16px floor on form controls (`!important` so it actually wins over host class rules), preventing iOS focus-zoom
- `overscroll-behavior-y: contain` on `html, body`; thin scrollbars

Colors are never overridden: everything references host theme tokens
(`--dsw-alias-*` with `currentColor` fallbacks) and follows light/dark/custom themes.

## Boundaries (honest)

- **Two persistence classes.** Touch boost and the Help panel state are
  browser-local (localStorage, on by default); if storage is unavailable the
  app says so ("this setting will not persist"). Text sizing writes the
  **host theme settings** — it survives reloads and even plugin removal;
  reset lives in Settings → Appearance.
- Per-role font-size ranges differ, so repeated one-directional stepping can
  shift relative sizes; the host limit is reported honestly when reached.
- Keyboard up (measured via `visualViewport`, with a media-query fallback): the
  help panel retreats, chips stay one row.
- Safe-area padding is capped at 8px and depends on the host declaring
  `viewport-fit=cover` (0 otherwise).
- State sync is one-directional: localStorage is the single writer; the chip,
  the stylesheet and other browser tabs all converge on it (storage + visibility events).
- No host DOM mutation, no overlays, no app-root replacement; styles and
  listeners are disposed on unmount.
