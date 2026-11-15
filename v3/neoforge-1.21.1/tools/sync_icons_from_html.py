#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import math
import re
import shutil
import subprocess
import tempfile

from PIL import Image, ImageChops, ImageFilter, ImageDraw

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
  stroke-width:2.0;
  stroke-linecap:round;
  stroke-linejoin:round;
}
.thin {
  fill:none;
  stroke:CURRENT_LINE !important;
  stroke-width:1.25;
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
  stroke-width:1.0;
}
.gold,.blue,.green,.brown,.purple,.water,.sand,.stone {
  fill:CURRENT_ACCENT !important;
  stroke:none !important;
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


SEMANTIC = ".stroke,.thin,.fill,.soft,.gold,.blue,.green,.brown,.purple,.water,.sand,.stone"

BASE_STYLE = f"""
{SEMANTIC} {{ opacity:0 !important; }}
"""

PRIMARY_STYLE = f"""
svg * {{ opacity:0 !important; }}
.stroke,.fill,.gold,.brown,.stone {{
  opacity:1 !important;
  fill:#ffffff !important;
  stroke:#ffffff !important;
  stroke-width:1.15 !important;
  stroke-linecap:round;
  stroke-linejoin:round;
}}
.stroke {{
  fill:none !important;
}}
"""

ACCENT_STYLE = f"""
svg * {{ opacity:0 !important; }}
.thin,.soft,.blue,.green,.purple,.water,.sand {{
  opacity:1 !important;
  fill:#ffffff !important;
  stroke:#ffffff !important;
  stroke-width:1.0 !important;
  stroke-linecap:round;
  stroke-linejoin:round;
}}
.thin {{
  fill:none !important;
}}
.soft {{
  stroke:none !important;
}}
"""


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


def white_mask(alpha: Image.Image) -> Image.Image:
    out = Image.new("RGBA", alpha.size, (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def build_semantic_layers(
        primary_path: Path,
        accent_path: Path,
        mono_path: Path,
        primary_out: Path,
        accent_out: Path,
        silhouette_out: Path) -> None:
    primary = Image.open(primary_path).convert("RGBA").getchannel("A")
    accent = Image.open(accent_path).convert("RGBA").getchannel("A")
    mono = Image.open(mono_path).convert("RGBA").getchannel("A")

    if primary.getbbox() is None:
        if accent.getbbox() is not None:
            primary = accent
            accent = Image.new("L", primary.size, 0)
        else:
            primary = mono

    # The outline must follow the complete icon, including neutral/base
    # details that are not part of either editable color channel. The mono
    # render is the authoritative full visible shape.
    visible = mono if mono.getbbox() is not None else ImageChops.lighter(primary, accent)

    # Build a solid silhouette used only as the source for a screen-space
    # 1px outline. Do NOT pre-render a thin border ring into the texture:
    # when 64x64 icons are scaled down to ~12-24px that ring is undersampled
    # and becomes the dotted/checkerboard edge seen in-game.
    hard = visible.point(lambda a: 255 if a >= 24 else 0)
    closed = hard.filter(ImageFilter.MaxFilter(3)).filter(ImageFilter.MinFilter(3))
    flood = closed.copy()
    ImageDraw.floodfill(flood, (0, 0), 128)
    silhouette = flood.point(lambda a: 0 if a == 128 else 255)
    # Closing/fill may shave a boundary pixel at the canvas edge. The
    # outline source must always contain every original visible pixel.
    silhouette = ImageChops.lighter(silhouette, hard)

    primary_out.parent.mkdir(parents=True, exist_ok=True)
    white_mask(primary).save(primary_out)
    white_mask(accent).save(accent_out)
    white_mask(silhouette).save(silhouette_out)


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
        base = tmpdir / "base"
        primary = tmpdir / "primary"
        accent = tmpdir / "accent"
        resources = render_sheet(entries, DUO_STYLE, duo)
        render_sheet(entries, MONO_STYLE, mono)
        render_sheet(entries, BASE_STYLE, base)
        render_sheet(entries, PRIMARY_STYLE, primary)
        render_sheet(entries, ACCENT_STYLE, accent)

        for resource in resources:
            normal = TEXTURE_ROOT / f"{resource}.png"
            monochrome = TEXTURE_ROOT / f"{resource}_mono.png"
            base_layer = TEXTURE_ROOT / f"{resource}_base.png"
            primary_layer = TEXTURE_ROOT / f"{resource}_primary.png"
            accent_layer = TEXTURE_ROOT / f"{resource}_accent.png"
            silhouette_layer = TEXTURE_ROOT / f"{resource}_silhouette.png"
            normal.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(duo / f"{resource}.png", normal)
            shutil.copy2(mono / f"{resource}.png", monochrome)
            shutil.copy2(base / f"{resource}.png", base_layer)
            build_semantic_layers(
                    primary / f"{resource}.png",
                    accent / f"{resource}.png",
                    mono / f"{resource}.png",
                    primary_layer, accent_layer, silhouette_layer)

    print(
        f"synced {len(ASSETS)} HTML SVG icons -> "
        "64x64 duo/mono + semantic primary/accent/silhouette layers")


if __name__ == "__main__":
    main()
