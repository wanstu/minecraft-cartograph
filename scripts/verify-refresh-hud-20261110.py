#!/usr/bin/env python3
'''2026-11-10 visual refresh/HUD structural regression.'''
from pathlib import Path
root=Path(__file__).resolve().parent.parent
code=root/'java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph'
load=lambda name:(code/name).read_text(encoding='utf-8')
client=load('CartographClient.java')
screen=load('AtlasScreen.java')
hud=load('AtlasHud.java')
adjust=load('HudAdjustScreen.java')
jobs=load('MapRefreshJobs.java')
settings=load('ClientMapSettings.java')
model=(root/'java/adapters/1.21.1-neoforge/src/main/resources/assets/cartograph/models/item/atlas.json').read_text()
assert 'boolean mayReplace = old == null' in client
assert 'page.complete()' in client.split('boolean mayReplace = old == null',1)[1].split('if (mayReplace)',1)[0]
assert '(!old.complete() && !page.cachedOnly() && page.scanned() >= old.scanned())' in client
assert 'if (IN_FLIGHT.containsKey(key)) {' in client.split('void invalidatePage',1)[1].split('public static void acceptPage',1)[0]
assert 'STALE.remove(key);' in client.split('void invalidatePage',1)[1].split('public static void acceptPage',1)[0]
assert 'unfinishedPerPage' in jobs and 'if (remaining == 0)' in jobs
assert jobs.count('AtlasMapServer.markSurfaceChanged(') == 1
assert 'AtlasHud.draw(g, true)' in adjust
assert 'AtlasHud.margin()' in adjust
assert 'instanceof HudAdjustScreen' in client and 'event.setCanceled(true);' in client
assert 'int travelY = Math.max(0, height - size - 2 * MARGIN)' in hud
assert 'cfg.heldSize / guiScale' in hud and 'static Layout heldLayout(' in hud
assert 'held ? false : cfg.round' in hud
assert 'held ? ClientMapSettings.Style.ILLUSTRATED : cfg.style' in hud
assert 'cfg.held(ClientMapSettings.COORDS)' in hud and 'cfg.hud(ClientMapSettings.COORDS)' in hud
assert 'g.enableScissor(' in hud and 'g.disableScissor()' in hud
assert 'case MAP -> 366; case MINI -> 416;' in screen
assert '手持古式地图（拿在手中时；独立方形样式）' in screen
assert 'hudSize = 130' in settings
assert '0.72' in model, 'Hand item still too large'
print('PASS no partial overwriting complete tiles, grouped refresh, compact hand, scoped preview, HUD bounds')
