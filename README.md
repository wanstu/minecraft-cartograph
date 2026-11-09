# Cartograph / 绘图师

An intentionally survival-friendly Minecraft atlas. It can represent **actually saved/generated chunks without player travel**; it must never predict an ungenerated region from the seed or cause chunk generation while viewing a map.

## Current development status

**Work in progress.** The playable world-map rendering and save-backed live cartography are **not yet integrated**. Do not publish this as a finished map mod.

- First adapter: Minecraft 1.21.1 NeoForge (Java 21).
- Shared Java 17 core: 128×128 map page coordinates, read-only MCA region presence checks, exact-once survey/color payment ledger, independent world/dimension keys.
- Initial NeoForge mechanics: atlas item, leather + empty map + feather crafting, atlas + empty map expansion recipe that preserves atlas data, ink/glow dye/compass data component and server-side resource settings.
- Existing HTML UI prototype: `preview/cartograph-prototype.html`.
- Common acceptance checks: `bash scripts/verify-common.sh`.
- Version matrix and publication policy: [docs/MULTI_VERSION.md](docs/MULTI_VERSION.md); complete gameplay contract: [docs/MECHANICS_V1.md](docs/MECHANICS_V1.md).

## Adapter layout

```
java/common/src/main/java/           # pure Java, Java 17 compatible
java/common/src/test/java/           # pure Java acceptance
java/adapters/1.21.1-neoforge/       # official NeoForge ModDevGradle 1.21.1 project
config/targets.json                 # planned vs. implemented targets
scripts/release-matrix.py           # machine-readable release target list
scripts/verify-common.sh            # portable common tests
scripts/verify-jar.py               # validates actual built artifact
.github/workflows/release.yml        # per-target CI, tag-triggered GitHub release
```

## Local build on Linux (Java 21)

```sh
bash scripts/verify-common.sh
cd java/adapters/1.21.1-neoforge
./gradlew --no-daemon check build
```

Expected artifact name: `Cartograph-0.1.0-mc1.21.1-neoforge.jar`. Other target directories are **not** implemented and do not produce releases.

## Current in-game development interaction

Until the atlas GUI is implemented, hold the atlas in the **main hand** and an input item in the **offhand**, then use the atlas. It stores consumed ink sacs, glow ink sacs and red/yellow/blue dye counts, and can install a compass; sneak-use with empty offhand returns the installed compass. This is a **temporary functional input test**, not the final unified material-slot UI. Tooltips display balances and pages.

Importantly, the mere existence of a saved region header is not sufficient to display terrain or claim a surface structure exists. The final adapter must read and validate the actual saved **fully generated chunk and surface** without invoking new chunk generation. The current index is only a cheap first-stage filter.

## Multi-version release

`config/targets.json` lists the same 9 **intended** Minecraft/loader pairs as GeoWorld. Do **not** flip a target to `implemented` until its own corresponding loader adapter has actually passed tests. Each built loader JAR compiles in the same common logic, uses its own Minecraft/loader metadata, and has a unique filename. Tags matching `v<mod_version>` cause GitHub CI to package the completed targets with SHA-256 checksums **only when `release_ready=true`**. The gate is intentionally `false` until actual playable cartography has passed acceptance.

## Implementation independence

The original [Map Atlases](https://github.com/Pepperoni-Jabroni/MapAtlases) is a feature reference only, not the source of this implementation. Its code is GPL-3.0; this project does not embed it. Cartograph's map-reading and rendering code must be written independently.
