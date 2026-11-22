# Cartograph V3.1 frontend theme architecture

## Product definition

A Cartograph theme is a complete frontend presentation package, not a map-style toggle.

The built-in theme identities are:

- `satellite`: 原生 · 卫星
- `modern`: 手绘 · 现代
- `ancient`: 手绘 · 古式

A theme may own presentation choices such as:

- layout and spacing
- controls and panel treatment
- icon presentation
- typography
- marker / structure presentation
- frontend interaction presentation
- map composition and map renderer
- HUD presentation

All themes consume the same `CartographBackendApi` capabilities.

## Hard boundary

Themes must never own or duplicate:

- chunk scanning
- Minecraft world access
- persistent world-fact storage
- world identity
- cache invalidation
- request scheduling
- WebSocket protocol ownership
- marker persistence
- structure discovery
- teleport semantics

Those stay in the backend/runtime layers.

Switching a theme therefore must not rescan the world, rebuild the facts cache, reconnect transport, or create another backend instance.

The CI theme-contract verifier enforces the built-in theme registry, required frontend hooks, the renderer fallback declaration, the dynamic current-theme label, and the presentation-only boundary of `applyFrontendTheme()`.

## Current runtime

The WebUI stores `frontendTheme` as a frontend-only preference.

The loopback server intentionally remains a thin transport and currently serves the two
frontend bundles (`index.html` and `app.js`). Theme implementation therefore stays
inside those frontend bundles instead of adding theme-specific HTTP routes. Shared
controls consume theme surface tokens such as `--theme-control-bg`,
`--theme-control-border`, `--theme-control-hover` and
`--theme-surface-muted`, so new controls inherit the active theme by default.

`FRONTEND_THEMES` is the theme registry. Each theme declares:

- stable theme id
- display label
- layout family
- icon family
- control density
- typography family
- map renderer id
- whether that map renderer is ready

`applyFrontendTheme()` applies only frontend state:

- `app.dataset.theme` selects the theme identity
- `themeLayout` selects the layout family
- `themeIcons` selects the icon family
- semantic marker icons are resolved through `THEME_ICON_SYMBOLS`; persisted marker data keeps semantic names such as `village`, `mountain` and `water`
- `themeDensity` selects control spacing/density
- `themeTypography` selects typography semantics
- `app.dataset.style` exposes the active map renderer identity
- About/settings UI reports the active theme and renderer state

`MAP_RENDERERS` is the renderer registry. `renderTerrain()` is only a dispatcher:
it resolves the active theme's `mapRenderer` and invokes the registered renderer.
The existing satellite implementation lives in `renderSatelliteTerrain()`.

At this stage:

| Theme | UI theme | Map renderer |
| --- | --- | --- |
| 原生 · 卫星 | ready | satellite |
| 手绘 · 现代 | ready | satellite fallback |
| 手绘 · 古式 | ready | satellite fallback |

The fallback is intentional. It keeps theme switching useful without pretending the hand-drawn renderer already exists.

## Current UI baselines

The satellite theme keeps the technical full-width header used by the current production WebUI.

The modern theme follows the earlier high-fidelity map-first direction in
`preview/cartograph-map-styles-hifi.html`: the terrain occupies the full viewport,
the primary navigation becomes a compact floating toolbar at the upper right, and
markers/layers remain independent floating panels. Responsive rules keep that toolbar
usable on narrow windows.

The ancient theme uses its own atlas composition instead of inheriting the modern
toolbar: a parchment-like framed header, a separately framed map sheet, serif typography
and engraved glyph family. It still displays the satellite renderer as an explicit
fallback until the ancient hand-drawn renderer exists; no sepia filter is applied to
the terrain canvas.

Marker and structure icon data stays semantic. The modern and engraved icon families
currently provide independent glyphs for the most common atlas concepts (pin, home,
village, mountain and water) and safely fall back to the technical symbol set for the
rest. Switching themes refreshes existing marker/layer UI without mutating persisted
marker definitions.

## Future theme renderer contract

Modern and ancient map renderers must derive from the same facts-only tiles already used by satellite rendering.

They may add derived semantic presentation such as coastline, water hierarchy, relief, forest masses, roads, labels, contours and decorative symbols, but derived presentation must remain frontend-owned.

A future renderer must not require a second world scan merely because the user changes theme.

## Compatibility rule

Theme identity is frontend-only and may evolve without changing the backend API.

Backend feature additions should expose semantic capability through `CartographBackendApi`; theme implementations decide how that capability looks.

This keeps WebUI, future in-game UI and future themes independent while sharing one map truth.
