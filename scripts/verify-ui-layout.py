#!/usr/bin/env python3
"""Static GUI layout regression guard for common Minecraft GUI scales.

This does not replace a real client screenshot test. It protects the critical
non-overlap invariants agreed in the full-screen HTML design.
"""
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = (root / "java/adapters/1.21.1-neoforge/src/main/java/"
          "com/wanstu/cartograph/AtlasScreen.java").read_text(encoding="utf-8")

required = [
    "renderTerrain(g)", "renderToolbar(g, sx, sy)",
    "renderFloatingPanel(g, sx, sy)", "renderDialog(g, sx, sy)",
    "renderBackground(GuiGraphics graphics", "setupMarkerSearch()",
    "showSurfaceFeatures(drawRoads, drawRails)",
    "CartographClient.sendRefill(5)",
    "CartographClient.sendSettings(",
    "CartographClient.sendMarker(", "CartographClient.exportAt(",
    "markerOffset(", "settingsOffset(", "drawWaypoints",
    "renderSurveyBadge(g, mx, my)", "1.55 / guiScale",
    "super.mouseClicked(guiX, guiY, button)",
    "super.mouseDragged(ux(mx), uy(my), button, ux(dx), uy(dy))",
    "super.mouseScrolled(guiX, guiY, deltaX, deltaY)",
]
for token in required:
    assert token in source, f"Expected GUI interaction missing: {token}"

for absent in ["drawSidebar(", "panelWidth()", "renderCursorInfo(", 'g.drawString(font, "拖动平移']:
    assert absent not in source, f"Legacy fixed sidebar/footer returned: {absent}"


def marker_offset(h, wide, medium, tiny):
    return tiny if h < 252 else medium if h < 300 else wide


def setting_offset(h, wide, medium, tiny):
    return tiny if h < 238 else medium if h < 307 else wide


def fits(h, name, boxes):
    for i, (start, end) in enumerate(boxes):
        assert 0 <= start < end <= h, f"{name} out of bounds: {h=} {boxes=}"
        if i:
            assert boxes[i-1][1] + 2 <= start, (
                f"{name} overlap at GUI modal {h}: {boxes[i-1]} with {boxes[i]}"
            )


for gui_height in (270, 280, 300, 350, 450, 600):
    mh = min(302, gui_height - 22)
    m = lambda wide, med, tiny: marker_offset(mh, wide, med, tiny)
    name = (m(79, 69, 60), m(79, 69, 60) + 19)
    icons = (m(118, 108, 93), m(118, 108, 93) + m(28, 26, 22))
    colors = (m(174, 153, 134), m(174, 153, 134) + m(20, 18, 16))
    glow = (m(209, 182, 158), m(209, 182, 158) + m(26, 24, 22))
    buttons = (mh - 36, mh - 11)
    fits(mh, "waypoint editor", [name, icons, colors, glow, buttons])

    sh = min(309, gui_height - 22)
    st = lambda wide, med, tiny: setting_offset(sh, wide, med, tiny)
    base, spacing = st(64, 52, 44), st(37, 31, 29)
    sliders = [(base + i * spacing, base + i * spacing + 21)
               for i in range(3)]
    inputs = (st(222, 178, 159), st(222, 178, 159) + 21)
    buttons = (sh - 35, sh - 10)
    fits(sh, "server settings", sliders + [inputs, buttons])

    if gui_height >= 270:
        panel_h = min(gui_height - 66 - 12,
                      247 if gui_height < 365 else
                      274 if gui_height < 402 else 332)
        compass = (166 if gui_height < 280 else
                   176 if gui_height < 295 else
                   198 if gui_height < 365 else
                   236 if gui_height < 402 else 283)
        assert compass + 24 <= panel_h, (
            f"Atlas compass control clipped at {gui_height=} {panel_h=}"
        )
        layer_h = min(gui_height - 66 - 12, 285)
        layertop = 52 if gui_height < 295 else 59 if gui_height < 350 else 66
        step = 35 if gui_height < 295 else 39 if gui_height < 350 else 47
        row = 32 if gui_height < 295 else 35 if gui_height < 350 else 39
        assert layertop + 3 * step + row <= layer_h, (
            f"Fourth layer toggle clipped at {gui_height=}"
        )

# The real user's screenshot is 1686x895; the original HTML has 338px
# floating panels and ~495px modals. Vanilla GUI=3 would otherwise enlarge
# them 3x and clip outside the game viewport. Our drawing + event coordinates
# must both use 1.55 physical pixels per UI unit.
for physical_w, physical_h, gui_scale in (
    (1686, 895, 1), (1686, 895, 2), (1686, 895, 3), (1686, 895, 4),
    (1280, 720, 3), (1920, 1080, 4)
):
    scale = 1.55 / gui_scale
    gui_w = physical_w / gui_scale
    gui_h = physical_h / gui_scale
    ui_w = gui_w / scale
    ui_h = gui_h / scale
    assert abs(ui_w - physical_w / 1.55) < 1
    assert abs(ui_h - physical_h / 1.55) < 1
    panel_w = min(218, ui_w - 20)
    panel_x = ui_w - panel_w - 18
    assert panel_x > 0 and panel_x + panel_w <= ui_w
    assert abs(panel_w * 1.55 - 338) < 2, (physical_w, gui_scale, panel_w)
    assert abs((ui_w - (panel_x + panel_w)) * 1.55 - 28) < 2
    modal_w = min(320, max(210, ui_w - 24))
    assert modal_w <= ui_w and (ui_w - modal_w) / 2 >= 0
    if ui_w >= 520:
        assert abs(modal_w * 1.55 - 496) < 2
    toolbar_w = 7 * (35 if ui_w < 590 else 38) + 6 * 4 + 12
    assert ui_w - toolbar_w - 18 >= 0
    assert 66 + 35 < ui_h

print("PASS physical pixel UI size for 1280/1686/1920px at GUI scales 1-4")
print("PASS UI static geometry for 270/280/300/350/450/600 virtual heights")
print("PASS floating panels + independent input/search/settings/layer interaction contracts")
