# Cartograph V4 development adapter

Separate development-only NeoForge 1.21.1 module, not the V3.1 release.
Native integrated-server 2D map reference (M to open). Java 21, NeoForge 21.1.265. From repository root:

    bash v3.1/neoforge-1.21.1/gradlew -p v4/neoforge-1.21.1 compileJava jar

For DADM dedicated-server smoke tests, use an isolated run directory/world, not a user save.
Set eula=true only for the test server and bind server-ip=127.0.0.1, use a separate port,
level-type=minecraft:flat and a small view/simulation distance. Run runServer with stdin;
after startup, modify a loaded surface block, allow the bounded scheduler to drain,
save-all flush and stop. The dev adapter stores geography only under the test world's
cartograph-v4-dev directory. It does not modify V3.1 caches.

Test evidence: two DADM server runs, real setblock gold then diamond at (128,-60,128)
on the isolated flat world; LiveSavedParityProbe in v4/core/src/test validates reopened
V4 and vanilla save material, biome and height. First run committed 25 active chunks;
second logged 50 active, 51 commits at tick 200. Both saved and stopped normally.

Current limitations: conservative cross-session live recapture and saved-source arbitration, no final production renderer or TPS/FPS validation. FrozenSaveCapture
requires an initialized registry and a frozen save/copy; never call on the tick thread
or against an actively changing source. Unknown material states fail closed. No deployment.
## Native map smoke

Use an isolated copy of a test save in run/saves, launch runClient, and enter that
world. M opens the map, left drag pans, wheel zooms around cursor, 1-5 change
reference style, R returns to the player, Esc/M closes. The session retains
asynchronous CPU page and texture atlas caches across reopen. DADM actual
client screenshots verified satellite/hand modern, drag, wheel, center and
reopen; no extra texture uploads after the hand style populated its seven tiles.
This development UI is not ready for deployment; no remote transport, 2.5D,
full visual style or complete gameplay-performance acceptance.
The batched renderer draws all terrain patches in a single textured quad batch,
using nearest sampling and explicit alpha blending. If an exact LOD tile is not
yet available, cached coarser tiles are clipped to that tile's footprint;
on zoom-out, available immediate finer children can fill corresponding quadrants.
An authoritative empty page suppresses fallback. Coarse coverage remains an
approximation at its original resolution, not proof of exact finer observation.
Only the current style can be drawn. The 64-extra-page request budget includes
parents and a best-effort adjacent ring; the ring prefetches CPU data only.
The debug HUD reports exact+fallback visible tiles, cumulative uploads, and
terrain batch draw calls. One call is not the entire game's draw count.
The 2.5D prototype shares the same camera, atlas and geography: T cycles
90/60/45/30 degrees, Q/E rotates 45 degrees, N restores north-up. Known
equal-height top surfaces are merged and observed height differences form
shaded sides. Drag/zoom currently use a reference-height plane, not depth
picking. The relief path is a stepped heightfield with CPU-projected batches,
not yet a GPU-resident mesh renderer or full production visual style.
DADM tested an isolated elevated fixture at 90/60/45 degrees and 45-degree
yaw, satellite/hand, drag, center and reopen. No hardware FPS acceptance.
## Retained vertex batch (unverified native development slice)
The current source uses world-local static GPU vertices, camera matrices and
right-click ray picking. HUD reports texture and mesh uploads separately.
Only rebuild on a changed drawable dependency; one combined retained batch
is capped at 262144 quads. Core tests passed, but native build and client QA
are pending because DADM source sync was blocked and EADM lacks the offline
NeoForge 21.1.265 dependency. Do not deploy this commit without actual native
compile and client verification; 8360b57 is the previous verified reference.
The retained batch now uses bounded region-local groups instead of rebuilding
a whole visible batch after local changes. Actual Java API compilation passed
against locally cached Minecraft 1.21.1 / NeoForge 21.1.211 artifacts; target
21.1.265 Gradle packaging and runtime QA are still required. The contour
calculation core is independently tested, not yet displayed in the map UI.