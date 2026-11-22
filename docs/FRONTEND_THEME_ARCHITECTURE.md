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

## Current runtime

The WebUI stores `frontendTheme` as a frontend-only preference.

`FRONTEND_THEMES` is the theme registry. Each theme declares:

- stable theme id
- display label
- map renderer id
- whether that map renderer is ready

`applyFrontendTheme()` applies only frontend state:

- `app.dataset.theme` selects UI presentation
- `app.dataset.style` selects the map renderer
- About/settings UI reports the active theme and renderer state

At this stage:

| Theme | UI theme | Map renderer |
| --- | --- | --- |
| 原生 · 卫星 | ready | satellite |
| 手绘 · 现代 | ready | satellite fallback |
| 手绘 · 古式 | ready | satellite fallback |

The fallback is intentional. It keeps theme switching useful without pretending the hand-drawn renderer already exists.

## Future theme renderer contract

Modern and ancient map renderers must derive from the same facts-only tiles already used by satellite rendering.

They may add derived semantic presentation such as coastline, water hierarchy, relief, forest masses, roads, labels, contours and decorative symbols, but derived presentation must remain frontend-owned.

A future renderer must not require a second world scan merely because the user changes theme.

## Compatibility rule

Theme identity is frontend-only and may evolve without changing the backend API.

Backend feature additions should expose semantic capability through `CartographBackendApi`; theme implementations decide how that capability looks.

This keeps WebUI, future in-game UI and future themes independent while sharing one map truth.
