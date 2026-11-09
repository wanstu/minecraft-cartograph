# Cartograph / 绘图师

Survival-friendly Minecraft map atlas. Uses **actual generated/saved terrain**, not world-seed predictions or forced chunk generation.

## Current status (development snapshot)

**Minecraft 1.21.1 NeoForge adapter now builds and starts on a dedicated development server.** It has the first real saved-chunk survey pipeline, in-game single-page map viewer, held-map HUD, client/server data packets and server-authoritative world SavedData ledger. **This is still an unreleased development version** and has not passed client gameplay acceptance.

- Main-hand right-click with no material in the offhand, or **M** while an atlas is in inventory: open a 128×128 map page. Arrow keys switch pages, mouse wheel zooms.
- Holding an atlas displays a small current-page minimap after the page is surveyed. Player arrow requires the nonconsumable compass upgrade.
- Temporary material input: atlas in the main hand + ink sac, glow ink sac, red/yellow/blue dye or compass in the offhand, then right click. Sneak + empty offhand returns the compass.
- Atlas crafting: vanilla empty map + leather + feather; extend with atlas + vanilla empty map on the standard crafting table.
- Server loads **FULL** saved chunk surface pixels from MCA region files or already-loaded chunks only; map is black/white until colored. First survey and first colorization cost ink/pigment according to configurable server multipliers. Repeated updates are free.
- Remaining major work: proper two-slot atlas UI, continuous multi-page map, manually edited/glowing waypoints, validated surface structure icons, vanilla/Create road and track overlays, vanilla map export, real client acceptance.

Full technical implementation and acceptance: [docs/SURVEY_PIPELINE.md](docs/SURVEY_PIPELINE.md). Product rules: [docs/MECHANICS_V1.md](docs/MECHANICS_V1.md).

## Source layout

```
java/common/                          # Java 17 pure shared source and tests
java/adapters/1.21.1-neoforge/        # compiled loader adapter (Java 21)
config/targets.json                  # planned and implemented adapter matrix
docs/SURVEY_PIPELINE.md              # live survey implementation / known limitations
docs/MULTI_VERSION.md                # GeoWorld-style multi-version release contract
preview/cartograph-prototype.html     # standalone HTML design prototype
.github/workflows/release.yml         # test & per-target builds; releases gated
```

## Validate in dadm

```sh
bash scripts/verify-common.sh
cd java/adapters/1.21.1-neoforge
./gradlew --no-daemon check build
```

Build JAR: `java/adapters/1.21.1-neoforge/build/libs/Cartograph-0.1.0-mc1.21.1-neoforge.jar`. Other Minecraft/loader versions remain **planned**, not available, until verified. `release_ready=false` prevents accidental formal releases.

Cartograph is an independent implementation; [Map Atlases](https://github.com/Pepperoni-Jabroni/MapAtlases) serves as a feature reference, without embedding its GPL-3.0 source.
