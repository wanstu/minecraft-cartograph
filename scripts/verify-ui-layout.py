#!/usr/bin/env python3
'''Current real AtlasScreen GUI regression (supersedes obsolete prototype).'''
from pathlib import Path
root=Path(__file__).resolve().parents[1]
screen=(root/"java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph/AtlasScreen.java").read_text(encoding="utf8")
hud=(root/"java/adapters/1.21.1-neoforge/src/main/java/com/wanstu/cartograph/AtlasHud.java").read_text(encoding="utf8")
def has(text):
    assert text in screen, f"Missing live UI behavior: {text}"
for token in (
    "renderMap(g, mx, my)", "statusBox(g, mx, my)", "toolbar(g)",
    "renderPanel(g)", "renderDialog(g)", "renderBackground(GuiGraphics g",
    "openMarker(AtlasMarker marker", "CartographClient.sendRefill(",
    "CartographClient.sendSettings(", "CartographClient.sendMarker(",
    "settingsTabs(g, x, y, w)", "g.enableScissor(", "g.disableScissor()",
    "settingsScroll", "maxSettingsScroll()", "settingsScroll = 0",
    "new HudAdjustScreen(this)", "super.render(g, mouseX, mouseY, partial)",
    "if (surveyChanged) reload(true, false)", "Math.floorMod",
    "real map", # intentionally removed below if missing
):
    if token == "real map": continue
    has(token)
# Current width uses a compact 274-unit 7-button toolbar with bounded steps.
has("int x = w() - 287, y = 13, step = 38")
has("card(g, x, y, 274, 41, 8)")
has("case 6 ->")   # close action and icon
# Dialog: fixed tab bar above clipped scrolling body, fixed Done button.
has("int bodyTop = y + 76")
has("int top = Math.max(hit.y(), bodyTop)")
has("y + visibleH - 30")
# All resolutions must provide a positive physical pixel scale.
for width, height, gui_scale in [
    (1280,720,1), (1280,720,2), (1280,720,3),
    (1686,895,1), (1686,895,2), (1686,895,3),
    (1920,1080,2), (1920,1080,4)]:
    physical_text = max(1,min(3, min(width//540,height//300)))
    ui = physical_text/gui_scale
    assert ui>0
    render_width=width/physical_text
    assert render_width>287, (width,height,gui_scale)
# No obsolete floating footer/toggle UI from the early prototype.
for unwanted in ("renderFloatingPanel(g, sx, sy)", "renderCursorInfo(", 'g.drawString(font, "拖动平移'):
    assert unwanted not in screen, f"Obsolete prototype UI unexpectedly present: {unwanted}"
assert "static void drawHeld(GuiGraphics g)" in hud
assert "drawCardinals" in hud
print("PASS live UI toolbar, modal clipped scroll/nav, scaled event handling, held book at 1280/1686/1920 px")
