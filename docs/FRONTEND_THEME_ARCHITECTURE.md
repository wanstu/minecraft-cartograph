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

The CI theme-contract verifier enforces the built-in theme registry, required frontend hooks, renderer registry declaration, dynamic current-theme labels, theme-aware UI icon registry, and the presentation-only boundary of `applyFrontendTheme()`. A headless Chromium smoke test switches all built-in themes through the real settings flow at desktop, narrow, and small-window viewports. It verifies persistence, labels, UI glyph families, density/typography effects, modal bounds, map viewport geometry, and collision-free layers/markers/controls.

## Current runtime

`frontendTheme` is persisted once in Minecraft-owned `CartographSettings` and shared by the in-game settings UI and WebUI. WebUI localStorage keeps only a temporary/fallback copy for startup and disconnected rendering; when connected, the Minecraft runtime value is authoritative.

The loopback server intentionally remains a thin transport and currently serves the two
frontend bundles (`index.html` and `app.js`). The existing `runtime_settings` WebSocket
message carries `frontendTheme` between Minecraft and WebUI; it does not introduce a
theme-specific backend service. Theme presentation therefore stays inside the frontend
bundle while theme identity is a shared client/runtime setting. Shared controls consume
theme surface tokens such as `--theme-control-bg`,
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
- primary frontend UI glyphs are resolved through `THEME_UI_ICON_SYMBOLS` from semantic roles such as `map`, `markers`, `layers`, `settings`, `about`, `locate` and `home`
- semantic marker/structure icons are resolved separately through `THEME_ICON_SYMBOLS`; persisted marker data keeps semantic names such as `village`, `mountain` and `water`
- `themeDensity` selects control spacing/density
- `themeTypography` selects typography semantics and each theme's font stack
- `app.dataset.mapRenderer` exposes the active map renderer identity; renderer state is intentionally named separately from frontend theme state
- About/settings UI reports the active theme and renderer state

`MAP_RENDERERS` is the WebUI renderer registry. `renderTerrain()` is only a dispatcher:
it resolves the active theme's `mapRenderer` and invokes the registered canvas renderer.
Minecraft owns the actual themed tile RGB through `FrontendThemeStyle`, so WebUI and the
native in-game map consume the same theme-rendered SurfaceTile facts.

Current state:

| Theme | UI theme | Map renderer |
| --- | --- | --- |
| 原生 · 卫星 | ready | satellite / resource-pack-aware surface |
| 手绘 · 现代 | ready | modern pastel surface + light relief/contour treatment |
| 手绘 · 古式 | ready | parchment/sepia surface + stronger ink-like relief/contour treatment |

Changing theme invalidates only presentation caches (native GPU atlases / WebUI region canvases and ETags).
Persisted SurfaceTile facts are unchanged and no world rescan is performed.

## Current UI baselines

The satellite theme keeps the technical full-width header used by the current production WebUI.

The modern theme follows the earlier high-fidelity map-first direction in
`preview/cartograph-map-styles-hifi.html`: the terrain occupies the full viewport,
the primary navigation becomes a compact floating toolbar at the upper right, and
markers/layers remain independent floating panels. Responsive rules keep that toolbar
usable on narrow windows.

The ancient theme uses its own atlas composition instead of inheriting the modern
toolbar: a parchment-like framed header, a separately framed map sheet, serif typography
and engraved glyph family. Its map renderer is derived from the same factual surface
tiles as satellite mode, then applies parchment/sepia color treatment and ink-like
height/contour accents. The native in-game frontend uses the same theme palette and
renderer identity.

Marker and structure icon data stays semantic. The modern and engraved icon families
provide independent glyphs for common atlas concepts (pin, home, village, mountain and
water) and safely fall back to the technical symbol set for the rest. Primary frontend
navigation and location controls use a separate semantic UI-icon registry so frontend
chrome can genuinely change glyph family without changing button behavior or persisted
data. Switching themes refreshes existing marker/layer/UI glyphs without mutating marker
definitions.

## Theme renderer contract

Modern and ancient map renderers derive from the same facts-only tiles already used by satellite rendering. The first implementation changes color language and relief/contour treatment only; later versions may add derived semantic presentation such as coastline, water hierarchy, forest masses, roads, labels and decorative symbols.

Derived presentation must remain frontend-owned and a renderer must never require a second world scan merely because the user changes theme.

## Compatibility rule

Theme identity is presentation-only but shared across frontends. It may evolve without changing world facts or `CartographBackendApi`; the in-game selector and WebUI both read/write the same `CartographSettings.frontendTheme` value.

Backend feature additions should expose semantic capability through `CartographBackendApi`; theme implementations decide how that capability looks.

This keeps WebUI, future in-game UI and future themes independent while sharing one map truth.
