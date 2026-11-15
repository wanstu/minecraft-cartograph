# Cartograph V3.1 frontend architecture

V3.1 uses a strict backend/frontend split. The opened large-map page is a
complete Pane frontend theme; the backend only exposes world facts and semantic
actions.

## Stable boundary

Frontend code consumes `CartographBackendApi`.

The API exposes:

- surface facts by chunk
- visible region revisions
- player position/direction
- markers and structures
- biome/surface lookup
- viewport/request hints
- semantic actions such as teleport and marker CRUD

Frontend code must not access `MapClientCache`, `PersistentTileStore`,
`PacketDistributor`, or `MapProtocol` directly. The Gradle task
`verifyV31FrontendBoundary` enforces this rule.

`MinecraftCartographBackend` is the NeoForge implementation. A Fabric build
can provide another implementation without changing theme code.

## Facts-only persistence

V3.1 does not persist rendered RGB tiles.

The authoritative cache contains only:

- block state id
- biome id
- surface height
- relief height
- water depth

The cache lives in a V3.1-only format/directory and deliberately does not read
older V3 cache formats.

Rendered pixels are disposable frontend data.

## Theme frontend

A complete theme implements `CartographThemeFrontend`.

A theme owns all large-map presentation:

- map renderer
- map canvas
- UI layout
- interaction model
- icon set
- controls
- windows/dialogs
- marker presentation

The first implementation is `NativeCartographThemeFrontend`.

Its map is `NativeMapCanvas`, a Pane component. The map component receives raw
facts through `CartographBackendApi` and uses
`SatelliteMapStyleRenderer` to derive satellite pixels.

`MapRegionTextureCache` is frontend-only. It caches derived GPU textures and
can be discarded/rebuilt when the active theme renderer changes.

Future themes can provide completely different layouts and interaction models,
for example:

- Native / satellite
- Hand-drawn modern
- Hand-drawn ancient

They still consume the same backend API and the same persisted world facts.

## Screen host

`MapScreenV31` is intentionally thin. It only bridges Minecraft Screen
lifecycle/input/render calls to Pane and the active theme.

It does not render the map, player, markers, panels or controls itself.

The V3.1 client entry opens `MapScreenV31`; the old `MapScreenV3` remains
only as implementation history/reference and is not an active frontend.

## Pane dependency

Pane 0.4.0 for Minecraft 1.21.1 is MIT licensed and is bundled inside the
Cartograph NeoForge JAR using NeoForge Jar-in-Jar. Players do not install Pane
separately. See `THIRD_PARTY_LICENSES.md`.
