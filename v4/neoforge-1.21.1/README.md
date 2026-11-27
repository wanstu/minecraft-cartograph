# Cartograph V4 development adapter

Separate development-only NeoForge 1.21.1 module, not the V3.1 release.
No client rendering or map UI. Java 21, NeoForge 21.1.265. From repository root:

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

Current limitations: conservative recapture on load/save, no automatic archive scanner
or saved-dirty consumption, no production renderer or TPS/FPS validation. FrozenSaveCapture
requires an initialized registry and a frozen save/copy; never call on the tick thread
or against an actively changing source. Unknown material states fail closed. No deployment.