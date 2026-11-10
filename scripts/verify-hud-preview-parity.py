#!/usr/bin/env python3
'''Hand-map fixed natural pose, float map WYSIWYG and shared style flags.'''
from pathlib import Path
root=Path(__file__).resolve().parents[1]
path=root/'java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph'
read=lambda fn:(path/fn).read_text(encoding='utf8')
hud=read('AtlasHud.java')
screen=read('AtlasScreen.java')
adjust=read('HudAdjustScreen.java')
client=read('CartographClient.java')
settings=read('ClientMapSettings.java')
assert 'static Layout layout(int width, int height)' in hud
assert 'AtlasHud.draw(g, true);' in adjust
assert 'AtlasHud.layout(width, height)' in adjust
assert 'private static void drawWithin(GuiGraphics g, boolean editing, Layout area)' in hud
assert 'new HudAdjustScreen(this)' in screen
assert 'new HudAdjustScreen(this,' not in screen
assert 'heldX' not in adjust and 'heldY' not in adjust
assert 'heldSize' not in settings
assert 'held ?' not in hud
assert 'drawHeld(event.getGuiGraphics())' in client
assert 'event.setCanceled(true);' in client
assert 'cfg.style, CartographClient.revision()' in hud
assert 'cfg.hud(ClientMapSettings.CARDINALS)' in hud
assert 'cfg.hud(ClientMapSettings.COORDS)' in hud
assert 'cfg.hud(ClientMapSettings.NORTH_UP)' in hud
assert 'cfg.hud(ClientMapSettings.ICONS)' in hud
assert 'Book' not in adjust
print('PASS floating preview maps to real layout, hand book is fixed and shares same style/overlay settings')
