#!/usr/bin/env python3
'''Screenshot-based native Minecraft visual regression on dadm Xephyr :92.

Checks screenshots taken by qa_dadm_visual.py after actual Minecraft input.
Real UI screenshots are stored in build/visual-qa and deliberately excluded
from git to avoid committing captured game content.
'''
from pathlib import Path
from PIL import Image

root=Path(__file__).resolve().parents[1]/"build/visual-qa"
required=[root/(name+".png") for name in ("tabs-fixed-top", "tabs-fixed-scrolled", "tabs-fixed-cost")]
if not all(path.is_file() for path in required):
    print("SKIP screenshot assertions: capture real Minecraft GUI first with qa_dadm_visual.py")
    raise SystemExit(0)
scrolled=Image.open(root/"tabs-fixed-scrolled.png").convert("RGB")
cost=Image.open(root/"tabs-fixed-cost.png").convert("RGB")
top=Image.open(root/"tabs-fixed-top.png").convert("RGB")
assert top.size == scrolled.size == cost.size == (1280,720)

def gold(rgb):
    r,g,b=rgb
    return r>=192 and g>=160 and b>=112 and r>g and g>b

map_tab=(275,160)
cost_tab=(747,160)
done=(978,628)
assert gold(top.getpixel(map_tab)), ("Default Map tab not active", top.getpixel(map_tab))
assert gold(scrolled.getpixel(map_tab)), ("Map tab disappeared on scroll",scrolled.getpixel(map_tab))
assert gold(cost.getpixel(cost_tab)), ("Cannot switch tabs after scroll",cost.getpixel(cost_tab))
assert not gold(cost.getpixel(map_tab)), ("Old Map tab stayed active",cost.getpixel(map_tab))
for image in (top,scrolled,cost):
    assert gold(image.getpixel(done)), ("Done button lost after scrolling",image.getpixel(done))
print("PASS native Minecraft visual: pinned tabs, interactive tab switch after scrolling, sticky Done at 1280x720")
