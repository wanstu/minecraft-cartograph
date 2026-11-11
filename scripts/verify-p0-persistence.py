#!/usr/bin/env python3
'''P0 contract: disk-first immutable cache, bounded 16x16 terrain patches,
and irreversible independent survey/color masks.'''
from pathlib import Path
base=Path(__file__).resolve().parents[1]
root=base/"java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph"
read=lambda name:(root/name).read_text(encoding="utf8")
client=read("CartographClient.java")
network=read("MapNetwork.java")
server=read("AtlasMapServer.java")
jobs=read("MapRefreshJobs.java")
applier=read("TerrainPatchApplier.java")
saved=read("AtlasWorldData.java")
monitor=read("SurfaceChangeMonitor.java")
screen=read("AtlasScreen.java")
core=(base/"java/common/src/main/java/com/wanstu/cartograph/core/SurveyLedger.java").read_text(encoding="utf8")
assert "DISK_READ_GRACE_TICKS = 16" in client
assert "DISK_VALIDATION_DELAY_TICKS = 200" in client
assert "DISK_LOADING.put(key, ticks)" in client
assert "DISK_LOADING.remove(k)" in client and "QUEUED.put(k, false)" in client
assert "DISK_RECHECK_ONCE.add(k)" in client
assert "ClientPageStore.request" in client and "ClientPageStore.save" in client
assert "DISK_LOADING.clear();" in client
assert "record PaperCoverage(" in network
assert "record AddPage(String atlasId, int targetPageX, int targetPageZ)" in network
assert "record TerrainPatch(" in network
assert "PIXELS = 16 * 16" in network
assert 'event.registrar("17")' in network
assert "CartographClient.acceptTerrainPatch(patch)" in network
assert "private record Changed(String dimension, int cx, int cz)" in server
assert "new MapNetwork.TerrainPatch(" in server
assert "atlasData.isColored(key)" in server
assert "boolean milestone = done" in server
assert "markWholePageChanged" in server and "markWholePageChanged" in jobs
for name in ("old.surveyedChunks()", "old.coloredChunks()", "old.inkUnits()",
             "old.pigmentUnits()", "old.glowUnits()", "old.totalSurveyed()",
             "old.totalColored()"):
    assert name in applier, name
assert "Math.floorMod(patch.chunkX(), 8)" in applier
assert "if ((old.surveyedChunks() & 1L << index) == 0)" in applier
assert "SurfaceDiskStore.equal(candidate.before, observed.snapshot().tile())" in monitor
assert "new Entry(state, width)" in saved
assert 'out.put("survey_mask", saveMask(masks.surveyed()))' in saved
assert 'out.put("color_mask", saveMask(masks.colorized()))' in saved
assert "new SurveyLedger(new SurveyLedger.Snapshot(paid, color))" in saved
assert "record Snapshot(Set<ChunkKey> surveyed, Set<ChunkKey> colorized)" in core
assert "if (surveyChanged) reload(true, false)" in screen
assert "CartographClient.queue(pageX(viewX), pageX(viewZ), true, false);" not in screen
print("PASS disk-first, delayed verification, server-authorized 16x16 patch, masks/credits unchanged, NBT persistence")
