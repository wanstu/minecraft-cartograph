# Cartograph V4 — unified geographic engine

Status: architecture contract and standalone foundation prototype. RC3 stays installed; no migration/deployment until replay and actual client acceptance.

## Non-negotiable contracts
- One geographic dataset per world and dimension; satellite and hand-drawn are rendering policies, not storage types. A style changes presentation only. Preserve existing supported mode/style combinations.
- 2D and 2.5D are camera presets of the same map. Orthographic camera with world-space center, zoom, yaw and elevation angle (30–90 degrees initial proposal), 90 degrees + north-up restores conventional map. Presets do not change data. Depth testing, side faces, terrain picking and object instances are renderer capabilities; no second map UI or world reader.
- Target tilted appearance: stepped terrain, river/water surfaces, simplified upright trees/buildings, map-like silhouettes and labels, fog only outside known coverage. Never infer a building/tree from roof color alone. Objects require observed facts or known structure metadata; uncertain objects are omitted or explicitly approximate. Never load/generate unknown chunks for mapping.
- Preserve precise top surface and ground/relief heights separately; use ground mesh plus objects (avoid double counting canopy/roof). Water surface, depth, validity, material and biome channels remain independent. Exact coordinate queries use L0, not coarse averages.
- No global rebuild on pan, zoom, angle or style. Data changes invalidate only affected leaf, required halo and ancestor cells. Never invalidate session validation on RAM eviction. Reopen uses disk snapshots; only observed dirty data needs refresh, with bounded reconciliation for external changes.

## Data schema and identity
Raw facts: adapt V3.1 SurfaceTile (state, biome, surface/relief heights, unsigned water depth), validate the registry/resource mapping before interpreting runtime IDs. Old RGB-only tiles are not sufficient for height/semantic claims. Keep legacy facts and markers read-only during migration.
Feature dictionary: canonical stable material/state key, biome/tint class, semantic class; hash is for lookup, equality for collision checks, integer ID for indexing. No exact height/position in feature identity. Disk IDs scoped to a versioned dictionary/region, never assume runtime registry IDs stable. Prototype uses in-memory IDs only.
Independent channels: observed mask, both height fields and their separate valid counts, linear RGB sums, class histogram, water statistics, future directional edge connectivity and observed object geometry. Coarse cells retain weighted sums and counts, not averages of averages; missing cells are excluded and coverage retained.
Use world-aligned powers-of-two grid, floor division for negative coordinates, parent = floorDiv(child,2). Cross-chunk and cross-region aggregation. Fine-to-coarse levels 0–10 initially; extend only if measured. Unknown partial coverage must remain visibly distinct. Preserve narrow waterways through observed geometry/connectivity overlay later rather than inventing a river from percentages.
Prototype uses sparse objects for correctness, NOT production memory layout; use packed region pages and bounded buffers after measurement.

## Region persistence and scheduling (pending)
Key: world/dimension/schema/region/LOD; region-local indexed compressed pages, content revision and checksum, atomic publish, corruption recovery, migration checkpoint, no destructive overwrite of legacy. Separate factual schema/derivation versions from presentation versions. Measure page sizes and codecs before choosing. Use bounded background IO/derivation, cancellation and coalescing by key/revision, viewport priority with reserved capacity for observed dirty writes. Main thread captures permitted immutable snapshots only; render thread owns GPU upload budget. Cache eviction is not a world change. Read cached coarse pages directly, without loading their descendants. Style/angle changes must not enqueue scans.

## Rendering and interaction (pending)
One renderer, shared world-space coordinate system, orthographic projection, stable anchored zoom, yaw rotation and constrained tilt. Draw terrain mesh with stitched LOD boundaries and valid-mask clipping, top and side surfaces; water separately; simplified observed object instances; world-anchored labels/markers, contours, navigation; depth-aware picking with unknown rejection. At north-up 90 degrees match 2D map coordinates. GPU palette/LUT and per-channel texture sampling, shader effects and CPU fallback if necessary; verify actual game and WebGL support. Same drawing policy and style at any angle. Angle/rotation changes update matrices; new viewport coverage loads only relevant pages. No previous-style flash.
Marker drag fix remains an independent V3.1 branch; do not lose it at integration.

## Gates
A: standalone deterministic aggregation, feature equality and negative coordinates.
B: migration replay on real facts, stable output across restart, exact known coverage, localized edits, bounded storage and memory. Include regions larger than the 8192-chunk V3.1 cache.
C: native render prototype with 90-degree parity, 45-degree relief/side faces, 360 yaw, picking round-trip, no scans on style or angle changes. Verify actual GPU support and fallback.
D: object display, water, seams, continuous LOD and marker drag/click. Existing style combinations and UI regression suite.
E: actual DADM modpack acceptance: cold/warm opening, continuous zoom/pan/return, all style and camera transitions, restart, FPS frame-time p50/p95/p99, TPS/MSPT, heap/GC, disk reads, derivation/upload counters. Compare identical world and settings against RC3 and no-map control. No deployment until gates pass. 60 FPS/next-frame warm switch are targets, not measured results.
