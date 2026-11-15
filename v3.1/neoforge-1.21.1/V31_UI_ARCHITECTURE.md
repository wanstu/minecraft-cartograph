# Cartograph V3.1 UI

V3.1 is the Pane-based UI branch of Cartograph.

## Boundary

The migration deliberately does **not** replace the map data/rendering pipeline:

- chunk/surface collection
- client cache and disk restore
- visible-region requests
- satellite map renderer
- map texture cache
- marker/structure data

Pane owns the application UI layer:

- top navigation
- zoom/location HUD
- layer window
- settings window
- marker manager
- layout, scrolling, hit testing, window dragging/resizing

The first V3.1 milestone temporarily reuses the V3 marker create/edit modal and
map right-click context menu. They will be migrated after the Pane shell is
visually accepted.

## Dependency policy

Pane is MIT licensed and is bundled inside the Cartograph NeoForge JAR using
NeoForge Jar-in-Jar. See `THIRD_PARTY_LICENSES.md`.

The UI code uses Pane's loader-neutral core/widget APIs so the same UI design
can be reused by the future Fabric build. Loader/network calls remain outside
the general layout/rendering responsibilities.
