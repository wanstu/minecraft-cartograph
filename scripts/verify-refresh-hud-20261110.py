#!/usr/bin/env python3
'''Screenshot regression: no partial refresh seams and hand-based atlas folio.'''
from pathlib import Path
base=Path(__file__).resolve().parents[1]
code=base/'java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph'
load=lambda name:(code/name).read_text(encoding='utf8')
client=load('CartographClient.java')
screen=load('AtlasScreen.java')
hud=load('AtlasHud.java')
adjust=load('HudAdjustScreen.java')
jobs=load('MapRefreshJobs.java')
server=load('AtlasMapServer.java')
settings=load('ClientMapSettings.java')
assert 'boolean mayReplace = old == null' in client
assert 'if (IN_FLIGHT.containsKey(key)) {' in client
assert 'unfinishedPerPage' in jobs and 'if (remaining == 0)' in jobs
assert jobs.count('AtlasMapServer.markWholePageChanged(')==1  # manual refresh completes one whole page
assert 'boolean milestone = done;' in server
assert 'task.next == 8 || task.next == 32' not in server
assert 'static void drawHeld(GuiGraphics g)' in hud
assert 'BOOK_RASTER_W = 256' in hud
assert 'if (cfg.hud(ClientMapSettings.CARDINALS))' in hud
assert 'cfg.style, CartographClient.revision()' in hud
assert 'boolean main = mc.player.getMainHandItem().is(CartographMod.ATLAS.get())' in hud
assert 'main ? (g.guiWidth() - width) / 2' in hud
assert 'if (!hasAtlas()) {' in client and 'IN_FLIGHT.clear();' in client
assert 'HudAdjustScreen(this)' in screen
assert 'heldLayout' not in hud and 'heldLayout' not in adjust
assert 'int chord = drawn.round()' in hud
assert 'markerScale' in hud
assert 'hudSize = 130' in settings
print('PASS stable refresh frames, main/offhand book anchors, shared map styles/cardinals, compact coords, drop-pickup preservation')
