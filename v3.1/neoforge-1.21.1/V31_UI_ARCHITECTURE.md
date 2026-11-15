# Cartograph V3.1 WebUI architecture

V3.1 has no in-game map screen. The Minecraft mod is the backend and data owner;
the large-map interface is a browser WebUI packaged inside the same JAR.

## Backend boundary

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

The frontend never reads Minecraft memory, chunks or save files directly.

## Facts-only persistence

`PersistentTileStore` stores only world facts:

- block state id
- biome id
- surface height
- relief height
- water depth

Rendered pixels are not authoritative data and are not persisted in the world
fact cache. A frontend can derive satellite, modern hand-drawn, ancient
hand-drawn or other presentations from the same facts without rescanning the
world.

## WebUI transport

`CartographWebUiServer` is a loopback-only HTTP bridge:

- binds only to `127.0.0.1`
- uses a random per-session token
- serves `webui/index.html` and `webui/app.js` from the JAR
- marshals Minecraft access back onto the Minecraft client thread
- exposes state, viewport, tile, point, marker and teleport APIs
- stops the backend live-view session after the browser has been idle for 10s

The current satellite frontend requests 16x16 RGB24 tile derivatives. Those
pixels are disposable WebUI cache; the persistent backend remains facts-only.

## Frontend

The first formal frontend is the existing high-fidelity HTML prototype promoted
to runtime WebUI.

Browser responsibilities:

- HTML/CSS layout
- SVG icons
- Canvas map composition
- player / structure / marker presentation
- panels, dialogs and settings
- pan / zoom / right-click interaction
- frontend-only visual preferences

This is why the WebUI can match the prototype directly: the prototype and the
runtime use the same browser rendering model instead of reimplementing CSS/SVG
through Minecraft GUI primitives.

Future frontends can consume the same backend API. A standalone desktop shell,
different browser theme, or another client does not require a new world scanner
or cache format.

## M shortcut

The Minecraft M key does not open a Screen. It starts/reuses the local WebUI
server and asks the operating system to open the WebUI URL in the normal system
browser.

## Build guard

`verifyV31WebUiBoundary` fails the build if the source/JAR contains the removed
Minecraft map screens, Pane runtime, old map GUI classes, or if the persistent
cache starts storing rendered pixels again.
