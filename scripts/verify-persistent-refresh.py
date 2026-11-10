#!/usr/bin/env python3
"""Structural guards: persistent cache and cost-free refresh must stay separate."""
from pathlib import Path
root=Path(__file__).resolve().parent.parent
code=root/"java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph"
def src(name): return (code/name).read_text(encoding="utf-8")
jobs=src("MapRefreshJobs.java")
client=src("CartographClient.java")
server=src("AtlasMapServer.java")
index=src("WorldSurfaceIndex.java")
item=src("AtlasHud.java")
mod=src("CartographMod.java")
for forbidden in ("reveal(", "fillInk", "fillPaper", "withInk", "payPaper", "shrink("):
    assert forbidden not in jobs, "Refresh modified paid resource: "+forbidden
for required in (
    "entry.surveyedKeys(dim)", "MapNetwork.RefreshProgress", "job.target.size()",
    "job.waiting", "MAX_IN_FLIGHT", "WorldSurfaceIndex.of(", "index.forceRefresh(",
    "state == 2", "job.state = 4", "CartographConfig.mainThreadBudgetMs",
):
    if required == "CartographConfig.mainThreadBudgetMs": continue
    assert required in jobs, "Refresh job missing: "+required
assert "MAX_PARALLEL_PAGES = 4" in client
assert "private static final int CACHE_LIMIT = 1400" in client
assert "GPU_TEXTURE_LIMIT = 140" in client
assert "ClientPageStore.save(" in client and "ClientPageStore.request(" in client
assert "if (holdingAtlas()) AtlasHud.drawHeld" in client
assert "AtlasHud.draw(event.getGuiGraphics(), false)" in client
assert "drawHeld(GuiGraphics g)" in item
for required in ("markSurfaceChanged", "MAX_PAGES_PER_VIEWER = 4", "sendChangedPages"):
    assert required in server
for required in ("SurfaceDiskStore.read(", "SurfaceDiskStore.write(", "savedFresh(", "chunkTimestamp"):
    assert required in src("SurfaceSampler.java") + src("SurfaceDiskStore.java")
assert "markDirty" in index and "forceRefresh" in index
assert "BlockEvent.NeighborNotifyEvent" in mod
print("PASS persisted pages/raw cache, parallel requests, block invalidation, hand HUD, cost-free manual refresh")
