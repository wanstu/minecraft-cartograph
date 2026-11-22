# Cartograph V3.1 frontend/backend architecture

Cartograph separates world/map backend services from every presentation frontend.

The current V3.1 QA build only ships the WebUI frontend because the in-game frontend
is intentionally cut down for this stage. That is a temporary product state, not
the architecture: WebUI and an in-game frontend are separate adapters that can
coexist and consume the same backend.

## Backend ownership

`CartographRuntime` is the client-side composition root.

It owns:

- one `MinecraftCartographBackend`
- one `CartographBackendRuntime` session manager
- the frontend registry/router
- frontend adapters such as WebUI

Frontend clients consume `CartographBackendApi`. The NeoForge implementation is
`MinecraftCartographBackend`.

The backend owns:

- chunk/surface collection
- world identity
- facts-only persistent cache
- visible-region request scheduling
- live surface updates
- player state
- structure discovery
- marker persistence
- semantic actions such as teleport and marker CRUD
- runtime settings, including which frontend handles the generic map-open intent

No frontend may create its own Minecraft backend instance.

## Frontend routing

The M key does not know what WebUI, a browser, or a Minecraft Screen is.

It emits one generic intent:

`CartographRuntime.openConfiguredFrontend()`

`CartographFrontendRouter` resolves that intent from the backend setting
`frontendTarget`.

Known frontend targets are:

- `web`
- `in_game`

Only `web` is registered in the current cut-down QA build. `in_game` is reserved
for the future game frontend. Adding that frontend must only require registering a
new `CartographFrontend` adapter; the M-key code and map backend must not change.

Explicit frontend opens are separate from the generic shortcut. For example, QA can
explicitly open the WebUI even if the configured M-key target is different.

## Shared backend sessions

`CartographBackendRuntime` owns the single backend instance and reference-counts
frontend sessions.

The first active frontend opens the backend map session. The last active frontend
closes it. This prevents WebUI and a future in-game frontend from independently
creating/closing competing backend instances.

Frontend-specific lifecycle remains in each adapter. For example, the WebUI adapter
can become idle without changing shortcut routing.

## WebUI transport

`WebCartographFrontend` is only a presentation adapter.

It owns a `CartographWebUiServer` with two distinct responsibilities:

- loopback HTTP serves only `webui/index.html` and `webui/app.js` from the JAR
- one authenticated WebSocket at `/api/v1/ws` carries all runtime traffic

The server binds only to `127.0.0.1`, uses a random per-session token, and
receives an injected `CartographBackendApi`. It never creates a
`MinecraftCartographBackend` itself. Minecraft access is marshalled onto the
Minecraft client thread, and the shared backend is acquired/released through
`CartographBackendRuntime`.

The WebSocket protocol is the Web frontend transport adapter:

- the initial `state` message contains markers, structure presets, player and runtime settings
- `viewport` replaces the previous visible tile subscription and updates the backend viewport
- map pixels use binary WebSocket frames carrying the existing CTB1 tile-batch format
- each viewport requests at most 512 visible chunks, ordered outward from the viewport centre
- unchanged chunks use ETags; unavailable chunks are not periodically polled
- `MapClientCache` publishes tile-change events; only changed tiles in the active viewport are pushed
- player/cache state is pushed only when it changes
- marker, teleport, point-query and runtime-setting actions use request/ack messages on the same socket
- viewport updates are latest-wins: only one request may be in flight, with at most one latest update queued

There is no runtime `/state` polling, `/tiles` HTTP traffic, or SSE connection.
The browser composes incoming tiles into 32x32-chunk region atlases. While the
user drags the map, it renders cached atlases only; after release it sends one
debounced viewport update. Missing tiles wait for a backend cache-change event
instead of repeatedly asking the server.

This transport remains Web-frontend-specific. The in-game frontend can call
`CartographBackendApi` directly; neither the shared backend nor frontend routing
depends on WebSocket.

When WebUI is active in singleplayer, Cartograph temporarily disables
`pauseOnLostFocus` so opening the system browser does not freeze the integrated
server. The active WebSocket session owns frontend liveness. The previous pause
setting is restored after the WebUI session becomes idle or closes.

## Facts-only persistence

`PersistentTileStore` stores only world facts:

- block state id
- biome id
- surface height
- relief height
- water depth

Rendered pixels are not authoritative data and are not persisted in the world fact
cache. Satellite, modern hand-drawn, ancient hand-drawn and future themes derive
their presentation from the same facts.

## Frontend themes

A theme is a complete presentation package, not only a map-style switch. A theme may
change layout, controls, icons, typography, panels, overlays, HUD treatment and map
composition while consuming the same `CartographBackendApi` contract.

The built-in theme identities are `satellite`, `modern` and `ancient`. Theme
selection is frontend-only state and must not rescan the world, rebuild the persistent
facts cache, reconnect transport or create a second backend instance.

The current theme runtime enables all three UI themes. Map composition dispatches
through a frontend `MAP_RENDERERS` registry; only the satellite renderer is complete.
Modern and ancient currently resolve to that satellite renderer until their own derived
hand-drawn renderers are implemented. Theme switching therefore does not need to change
the terrain/cache pipeline. See `docs/FRONTEND_THEME_ARCHITECTURE.md` for the contract.

## Frontend responsibility

A frontend is responsible for presentation and interaction only:

- layout
- controls
- icons
- map composition
- player / structure / marker presentation
- panels, dialogs and settings UI
- pan / zoom / pointer interaction
- frontend-only visual preferences

It must not directly read Minecraft memory, chunks, save files, network packets or
persistent map storage.

The current WebUI uses HTML/CSS/SVG/Canvas. A future in-game frontend can use
Minecraft rendering APIs while consuming the same backend contract.

## Current cut-down build

For this QA stage:

- `frontendTarget=web` is the default
- WebUI is registered
- no in-game map Screen is packaged

This is deliberately temporary. It must not be interpreted as “Cartograph is a
browser-only map”.

## Build guards

`verifyV31WebUiBoundary` verifies the current cut-down package and architectural
boundaries:

- the client key entry must route through `CartographFrontendRouter`, not call
  `CartographWebUiServer` / `openInBrowser()` directly
- `CartographWebUiServer` must not instantiate `MinecraftCartographBackend`
- the current QA JAR must not contain the removed old in-game/Panes implementation
- the persistent cache must remain facts-only
- the WebUI assets must be packaged
