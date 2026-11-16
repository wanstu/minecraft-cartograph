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

It owns a `CartographWebUiServer`, which is a loopback-only HTTP transport bridge:

- binds only to `127.0.0.1`
- uses a random per-session token
- serves `webui/index.html` and `webui/app.js` from the JAR
- marshals Minecraft access back onto the Minecraft client thread
- exposes state, viewport, tile, point, marker, teleport and runtime-setting APIs
- acquires/releases the shared backend through `CartographBackendRuntime`

The HTTP bridge receives an injected `CartographBackendApi`; it never creates a
`MinecraftCartographBackend` itself.

Map transport is deliberately separated from normal state/action traffic:

- normal state, viewport, point, marker, teleport and settings operations use HTTP
- WebUI liveness uses one SSE session connection; SSE does not carry map pixels
- map pixels use `POST /api/v1/tiles`, a compact CTB1 binary batch response
- the browser currently requests up to 96 chunks per batch instead of one HTTP
  request per 16x16 chunk
- ETags are carried inside the batch so unchanged chunks return metadata only
- the browser composes chunks into 32x32-chunk region atlases and draws those
  atlases, rather than maintaining one canvas and draw call per chunk
- while the user drags the map, only already-cached atlases are rendered; viewport
  submission and new map requests resume after the drag ends
- state polling does not resubmit an unchanged viewport or rebuild static DOM lists

This keeps the transport replaceable. WebSocket can be added later as another
transport adapter if bidirectional streaming is useful, without changing
`CartographBackendApi` or frontend routing.

When WebUI is active in singleplayer, Cartograph temporarily disables
`pauseOnLostFocus` so opening the system browser does not freeze the integrated
server. A startup grace period covers slow browser launch, then the SSE session
explicitly represents frontend liveness. The previous setting is restored after
the WebUI session becomes idle or closes.

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
