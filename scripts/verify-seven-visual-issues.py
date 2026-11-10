#!/usr/bin/env python3
'''7 reproduction/guard contracts from the current screenshot feedback.'''
from pathlib import Path
root=Path(__file__).resolve().parents[1]
folder=root/'java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph'
source=lambda name:(folder/name).read_text(encoding='utf8')
ui=source('AtlasScreen.java')
hud=source('AtlasHud.java')
server=source('AtlasMapServer.java')
client=source('CartographClient.java')
adjust=source('HudAdjustScreen.java')
cfg=source('ClientMapSettings.java')
# 1. Zoom down: markers AND player arrows scale, labels hide.
assert 'Math.clamp(zoomPixels / 7.0' in ui
assert 'zoomPixels >= 5.5' in ui
assert 'g.pose().scale(iconScale, iconScale, 1)' in ui
# 2. No moving 8/32 scanned rows.
assert 'boolean milestone = done' in server
assert 'task.next == 8 || task.next == 32' not in server
# 3/4. Main book centered; offhand to left, dimensions locked to viewport.
assert 'boolean main = mc.player.getMainHandItem().is(' in hud
assert 'main ? (g.guiWidth() - width) / 2' in hud
assert 'Math.max(7, g.guiWidth() / 24)' in hud
assert 'drawBookCardinals(' in hud
assert 'updateBook(frame)' in hud
# 5. Map coords drawn compact, measured to circular chord; no black 2-row bar.
assert 'int chord = drawn.round()' in hud
assert 'g.drawCenteredString(mc.font, coords, 0, 0' in hud
assert 'minimap coordinates' not in hud.lower()
# 6. Same user style and flags for float and held, no hidden held overrides.
assert 'Frame frame = new Frame(' in hud
assert 'actualZoom = cfg.hudZoom' in hud
assert 'false, false, cfg.style, CartographClient.revision()' in hud
assert 'cfg.hud(ClientMapSettings.CARDINALS)' in hud
assert 'toggleHeld' not in cfg
assert 'new HudAdjustScreen(this)' in ui
assert 'heldX' not in adjust
# 7. Item temporarily missing preserves maps while discarding stale requests.
idle=client.split('if (!hasAtlas()) {',1)[1].split('String id = atlasId()',1)[0]
assert 'IN_FLIGHT.clear()' in idle and 'QUEUED.clear()' in idle
assert 'PAGES.clear()' not in idle and 'if (!lastAtlasId.equals(id))' in client
assert 'if (!lastAtlasId.equals(id))' in client
print('PASS screenshot 1-7: scaled markers, complete refresh, dual hand book, compact coords, shared styles, non-destructive reacquire')
