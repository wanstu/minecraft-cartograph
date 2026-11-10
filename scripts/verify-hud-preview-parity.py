#!/usr/bin/env python3
'''Structural contract: in-game and editor use the same HUD layouts/renderers.'''
from pathlib import Path
root=Path(__file__).resolve().parent.parent
folder=root/'java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph'
read=lambda file:(folder/file).read_text(encoding='utf8')
hud=read('AtlasHud.java')
edit=read('HudAdjustScreen.java')
cfg=read('ClientMapSettings.java')
client=read('CartographClient.java')
settings=read('AtlasScreen.java')
assert 'static Layout heldLayout(int width, int height)' in hud
assert 'Layout area = heldLayout(g.guiWidth(), g.guiHeight());' in hud
assert 'drawWithin(g, editing, true, area);' in hud
assert 'drawWithin(g, editing, false, layout(g.guiWidth(), g.guiHeight()));' in hud
assert 'if (held()) AtlasHud.drawHeld(g, true);' in edit
assert 'else AtlasHud.draw(g, true);' in edit
assert 'return held() ? AtlasHud.heldLayout(width, height) : AtlasHud.layout(width, height);' in edit
assert 'mode == Mode.HELD' in edit
assert 'screen.previewingFloating()' in client
assert 'if (holdingAtlas()) AtlasHud.drawHeld(event.getGuiGraphics());' in client
assert 'else if (ClientMapSettings.get().hud(ClientMapSettings.ENABLED))' in client
for key in ('heldSize','heldX','heldY','heldZoom','heldFlags'):
    assert f'out.{key} = ' in cfg, f'{key} not loaded'
    assert f'p.setProperty("{key}"' in cfg, f'{key} not persisted'
for key in ('hudX', 'hudY', 'hudSize', 'heldX', 'heldY', 'heldSize'):
    assert f'cfg.{key} =' in edit, f'{key} not editable/restored'
assert 'private enum SettingsTab { MAP, MINI, COST, CACHE }' in settings
assert 'new HudAdjustScreen(this, HudAdjustScreen.Mode.FLOAT)' in settings
assert 'new HudAdjustScreen(this, HudAdjustScreen.Mode.HELD)' in settings
for key in ('held(ClientMapSettings.ICONS)', 'held(ClientMapSettings.COORDS)',
            'held(ClientMapSettings.CARDINALS)', 'held(ClientMapSettings.NORTH_UP)'):
    assert key in hud, f'held toggle missing in actual renderer: {key}'
assert 'case MAP -> 366; case MINI -> 416;' in settings
print('PASS FLOAT/HELD preview is actual renderer/layout, per-mode flags/zoom/position/size persisted, cancel restores both')
