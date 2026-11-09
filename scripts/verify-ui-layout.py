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
    "renderTerrain(g)", "renderToolbar(g, mx, my)",
    "renderFloatingPanel(g, mx, my)", "renderDialog(g, mx, my)",
    "renderBackground(GuiGraphics graphics", "setupMarkerSearch()",
    "showSurfaceFeatures(drawRoads, drawRails)",
    "CartographClient.sendRefill(5)",
    "CartographClient.sendSettings(",
    "CartographClient.sendMarker(", "CartographClient.exportAt(",
    "markerOffset(", "settingsOffset(", "drawWaypoints",
]
for token in required:
    assert token in source, f"Expected GUI interaction missing: {token}"

for absent in ["drawSidebar(", "panelWidth()", 'g.drawString(font, "拖动平移']:
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

    if gui_height >= 280:
        panel_h = min(gui_height - 59 - 12,
                      247 if gui_height < 365 else
                      274 if gui_height < 402 else 332)
        compass = (181 if gui_height < 295 else
                   198 if gui_height < 365 else
                   236 if gui_height < 402 else 283)
        assert compass + 24 <= panel_h, (
            f"Atlas compass control clipped at {gui_height=} {panel_h=}"
        )
        layer_h = min(gui_height - 59 - 12, 285)
        layertop = 52 if gui_height < 295 else 59 if gui_height < 350 else 66
        step = 35 if gui_height < 295 else 39 if gui_height < 350 else 47
        row = 32 if gui_height < 295 else 35 if gui_height < 350 else 39
        assert layertop + 3 * step + row <= layer_h, (
            f"Fourth layer toggle clipped at {gui_height=}"
        )

print("PASS UI static geometry for 270/280/300/350/450/600 scaled heights")
print("PASS floating panels + independent input/search/settings/layer interaction contracts")
