#!/usr/bin/env python3
'''Guards for real-game CPU regression: no-ink maps rescanned continuously.'''
from pathlib import Path
d=Path(__file__).resolve().parents[1]/'java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph'
read=lambda name:(d/name).read_text('utf8')
monitor=read('SurfaceChangeMonitor.java')
mod=read('CartographMod.java')
client=read('CartographClient.java')
page=read('ClientPageStore.java')
atlas=read('AtlasMapServer.java')
screen=read('AtlasScreen.java')
assert 'SurfaceChangeMonitor::tick' in mod
assert 'SurfaceChangeMonitor::stopped' in mod
assert 'SurfaceChangeMonitor.changed(event.getLevel(), event.getPos(), false)' in mod
assert 'SurfaceChangeMonitor.changed(event.getLevel(), event.getPos(), true)' in mod
assert 'AtlasMapServer.markSurfaceChanged' not in mod
assert 'SurfaceDiskStore.equal(candidate.before, observed.snapshot().tile())' in monitor
assert 'if (!direct && existing.state() != WorldSurfaceIndex.State.READY)' in monitor
assert 'PENDING.size() >= MAX_PENDING' in monitor
assert 'System.nanoTime() < deadline' in monitor and 'processed < 4' in monitor
assert 'NEIGHBOR_RECHECK_TICKS = 120' in monitor
assert 'DISK_RECHECK_ONCE' in client and 'SURFACE_RECHECK_TICKS' not in client
assert 'WorldSurfaceIndex::chunkLoaded' in mod
assert 'WorldSurfaceIndex::chunkSaved' in mod
world=read('WorldSurfaceIndex.java')
assert 'public static void chunkLoaded(' in world and 'public static void chunkSaved(' in world
assert 'getChunkSource().getChunkNow(' in world
assert 'level.getServer().isSameThread()' in world
assert 'AtlasMapServer.markSurfaceChanged(next.level(), next.cx(), next.cz())' in world
assert 'AtlasMapServer.markWholePageChanged(next.level(),' in world
assert 'CACHE_TTL_LOADED' not in world and 'CACHE_TTL_SAVED' not in world
assert 'PAGES.clear();' not in world and 'currentAtlas(' not in world
assert 'Set<CacheKey> VISIBLE_GPU' in client
assert 'VISIBLE_GPU.contains(e.getKey())' in client
assert 'pinVisiblePages(visiblePages)' in screen
assert 'tickExploration(' not in atlas

assert 'tickExploration(' not in atlas
assert 'if (page.complete() && page.inRange()' in client
assert 'page.painted() < 1' not in page
assert 'boolean milestone = done;' in atlas
assert 'private void updateUiScale()' in screen
assert 'dialog == Dialog.SETTINGS ? 306 : modalH() + 36' in screen
assert 'settingsScroll' in screen
assert 'settingsTabs(g, x, y, w);' in screen
assert 'int bodyTop = y + 76' in screen
assert screen.count('private void settingsTabs(') == 1
assert 'g.enableScissor' in screen and 'g.disableScissor' in screen
assert 'maxSettingsScroll()' in screen
assert 'settingsScroll = 0' in screen
assert 'int top = Math.max(hit.y(), bodyTop)' in screen
assert 'y + visibleH - 30' in screen
assert 'settingsScroll - (int) Math.round(dy * 24)' in screen
print('PASS real surface diff gating, neighbor coalescing, no-ink cache, responsive integer UI scaling')
