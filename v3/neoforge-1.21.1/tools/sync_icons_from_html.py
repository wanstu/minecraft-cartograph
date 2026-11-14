#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import math
import re
import shutil
import subprocess
import tempfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / "design" / "icon-preview.html"
DESIGN_ROOT = ROOT / "design" / "icons"
TEXTURE_ROOT = ROOT / "src" / "main" / "resources" / "assets" / "cartograph_v3" / "textures" / "gui" / "icons"

CELL = 256
OUT = 64
COLS = 8

ASSETS = {
    "player/arrow": ("player", "导航箭头"),
    "player/cross": ("player", "十字准星"),

    "ui/menu": ("menu", "菜单"),
    "ui/markers": ("menu", "标点"),
    "ui/layers": ("menu", "图层"),
    "ui/settings": ("menu", "设置"),
    "ui/locate": ("menu", "定位"),
    "ui/close": ("menu", "关闭"),
    "ui/add": ("menu", "添加"),
    "ui/edit": ("menu", "编辑"),
    "ui/trash": ("menu", "删除"),
    "ui/restore": ("menu", "恢复"),
    "ui/reset": ("menu", "重置"),
    "ui/teleport": ("menu", "传送"),

    "marker/pin": ("custom", "标点"),
    "marker/village": ("structure", "村庄"),
    "marker/mansion": ("structure", "林地府邸"),
    "marker/temple": ("structure", "神殿"),
    "marker/outpost": ("structure", "前哨站"),
    "marker/mine": ("structure", "废弃矿井"),
    "marker/portal": ("structure", "下界传送门"),
    "marker/ship": ("structure", "船只"),
    "marker/city": ("custom", "城市"),
    "marker/fortress": ("structure", "要塞"),
    "marker/home": ("custom", "住宅"),
    "marker/flag": ("custom", "旗帜"),
    "marker/camp": ("structure", "营地"),
    "marker/farm": ("structure", "农场"),
    "marker/shop": ("structure", "商店"),
    "marker/rail": ("structure", "铁路"),
    "marker/harbor": ("structure", "港口"),
    "marker/factory": ("custom", "工厂"),
    "marker/tower": ("structure", "高塔"),
    "marker/cave": ("custom", "洞穴"),
    "marker/mountain": ("geo", "山峰"),
    "marker/mountain_range": ("geo", "山脉"),
    "marker/hill": ("geo", "丘陵"),
    "marker/basin": ("geo", "盆地"),
    "marker/lake": ("geo", "湖泊"),
    "marker/river": ("geo", "河流"),
    "marker/large_river": ("geo", "大河"),
    "marker/small_river": ("geo", "小河"),
    "marker/coast": ("geo", "海岸"),
    "marker/beach": ("geo", "沙滩"),
    "marker/tree": ("geo", "森林"),
    "marker/swamp": ("geo", "沼泽"),
    "marker/desert": ("geo", "沙漠"),
    "marker/volcano": ("geo", "火山"),
    "marker/sinkhole": ("geo", "天坑"),
    "marker/island": ("geo", "岛屿"),
    "marker/delta": ("geo", "三角洲"),
    "marker/waterfall": ("geo", "瀑布"),
    "marker/water": ("geo", "水域"),
    "marker/danger": ("custom", "危险"),
    "marker/info": ("custom", "信息"),
    "marker/food": ("custom", "食物"),
    "marker/star": ("custom", "星标"),
}

STYLE_COMMON = """
svg {
  display:block;
  width:256px;
  height:256px;
  color:#e8eef5;
  overflow:visible;
}
.stroke {
  fill:none;
  stroke:CURRENT_LINE !important;
  stroke-width:3.2;
  stroke-linecap:round;
  stroke-linejoin:round;
}
.thin {
  fill:none;
  stroke:CURRENT_LINE !important;
  stroke-width:2.2;
  stroke-linecap:round;
  stroke-linejoin:round;
}
.fill {
  fill:CURRENT_PRIMARY !important;
  stroke:none;
}
.soft {
  fill:CURRENT_SOFT !important;
  stroke:CURRENT_LINE !important;
  stroke-width:2.4;
}
.gold,.blue,.green,.brown,.purple,.water,.sand,.stone {
  fill:CURRENT_ACCENT !important;
  stroke:CURRENT_LINE !important;
  stroke-width:1.5;
}
"""

DUO_STYLE = (STYLE_COMMON
    .replace("CURRENT_LINE", "#dfe7ef")
    .replace("CURRENT_PRIMARY", "#e8eef5")
    .replace("CURRENT_SOFT", "rgba(232,238,245,.13)")
    .replace("CURRENT_ACCENT", "#f2bd45"))

MONO_STYLE = (STYLE_COMMON
    .replace("CURRENT_LINE", "#ffffff")
    .replace("CURRENT_PRIMARY", "#ffffff")
    .replace("CURRENT_SOFT", "rgba(255,255,255,.18)")
    .replace("CURRENT_ACCENT", "#ffffff"))


def parse_preview() -> dict[tuple[str, str], str]:
    text = PREVIEW.read_text(encoding="utf-8")
    found: dict[tuple[str, str], str] = {}
    for line in text.splitlines():
        if "{g:'" not in line or "svg:I('" not in line:
            continue
        group = re.search(r"\bg:'([^']+)'", line)
        name = re.search(r"\bname:'([^']+)'", line)
        body = re.search(r"svg:I\('(.*)'\)\s*\}\s*,?\s*$", line)
        if group and name and body:
            found[(group.group(1), name.group(1))] = body.group(1)

    missing = {key: source for key, source in ASSETS.items() if source not in found}
    if missing:
        details = ", ".join(f"{key} <- {source}" for key, source in missing.items())
        raise RuntimeError(f"icon-preview.html missing mapped icons: {details}")
    return found


def standalone_svg(body: str, style: str) -> str:
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="256" height="256">
<style>{style}</style>
{body}
</svg>
"""


def save_design_sources(entries: dict[tuple[str, str], str]) -> None:
    for resource, source in ASSETS.items():
        target = DESIGN_ROOT / f"{resource}.svg"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(standalone_svg(entries[source], DUO_STYLE), encoding="utf-8")


def render_sheet(entries: dict[tuple[str, str], str], style: str, target: Path) -> list[str]:
    resources = list(ASSETS)
    rows = math.ceil(len(resources) / COLS)
    width = COLS * CELL
    height = rows * CELL

    cells = []
    for resource in resources:
        body = entries[ASSETS[resource]]
        cells.append(
            '<div class="cell">'
            + standalone_svg(body, style)
            + '</div>'
        )

    page = f"""<!doctype html>
<meta charset="utf-8">
<style>
html,body{{margin:0;padding:0;width:{width}px;height:{height}px;background:transparent;overflow:hidden}}
.grid{{display:grid;grid-template-columns:repeat({COLS},{CELL}px);grid-auto-rows:{CELL}px;width:{width}px;height:{height}px}}
.cell{{width:{CELL}px;height:{CELL}px;overflow:hidden}}
.cell svg{{width:{CELL}px;height:{CELL}px}}
</style>
<div class="grid">{''.join(cells)}</div>
"""

    with tempfile.TemporaryDirectory(prefix="cartograph-icon-sync-") as tmp:
        tmpdir = Path(tmp)
        html = tmpdir / "sheet.html"
        png = tmpdir / "sheet.png"
        html.write_text(page, encoding="utf-8")

        cmd = [
            "google-chrome",
            "--headless=new",
            "--disable-gpu",
            "--no-sandbox",
            "--hide-scrollbars",
            "--force-device-scale-factor=1",
            "--default-background-color=00000000",
            f"--window-size={width},{height}",
            f"--screenshot={png}",
            html.resolve().as_uri(),
        ]
        result = subprocess.run(cmd, text=True, capture_output=True)
        if result.returncode != 0 or not png.is_file():
            raise RuntimeError("Chrome SVG rasterization failed:\n" + result.stdout + result.stderr)

        sheet = Image.open(png).convert("RGBA")
        if sheet.size != (width, height):
            raise RuntimeError(f"unexpected screenshot size {sheet.size}, expected {(width, height)}")

        target.mkdir(parents=True, exist_ok=True)
        for index, resource in enumerate(resources):
            col = index % COLS
            row = index // COLS
            icon = sheet.crop((col * CELL, row * CELL, (col + 1) * CELL, (row + 1) * CELL))
            icon = icon.resize((OUT, OUT), Image.Resampling.LANCZOS)
            out = target / f"{resource}.png"
            out.parent.mkdir(parents=True, exist_ok=True)
            icon.save(out)

    return resources


def main() -> None:
    entries = parse_preview()

    if DESIGN_ROOT.exists():
        shutil.rmtree(DESIGN_ROOT)
    if TEXTURE_ROOT.exists():
        shutil.rmtree(TEXTURE_ROOT)

    save_design_sources(entries)

    with tempfile.TemporaryDirectory(prefix="cartograph-icon-output-") as tmp:
        tmpdir = Path(tmp)
        duo = tmpdir / "duo"
        mono = tmpdir / "mono"
        resources = render_sheet(entries, DUO_STYLE, duo)
        render_sheet(entries, MONO_STYLE, mono)

        for resource in resources:
            normal = TEXTURE_ROOT / f"{resource}.png"
            monochrome = TEXTURE_ROOT / f"{resource}_mono.png"
            normal.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(duo / f"{resource}.png", normal)
            shutil.copy2(mono / f"{resource}.png", monochrome)

    print(f"synced {len(ASSETS)} HTML SVG icons -> exact 64x64 duo + mono game textures")


if __name__ == "__main__":
    main()
