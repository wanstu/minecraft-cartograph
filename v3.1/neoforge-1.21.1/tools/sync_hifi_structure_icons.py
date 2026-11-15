#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess
import tempfile
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "design" / "map-ui-prototype.html"
ICON_ROOT = ROOT / "src" / "main" / "resources" / "assets" / "cartograph_v3" / "textures" / "gui" / "icons"
OUT = ICON_ROOT / "marker"
UI_OUT = ICON_ROOT / "ui"

UI_ICONS = {
    "map": "i-map",
    "about": "i-info",
    "copy": "i-copy",
}

ICONS = {
    "village": "i-village",
    "mansion": "i-mansion",
    "desert_pyramid": "i-desert-pyramid",
    "jungle_temple": "i-jungle-temple",
    "swamp_hut": "i-swamp-hut",
    "igloo": "i-igloo",
    "ocean_monument": "i-ocean-monument",
    "outpost": "i-outpost",
    "mine": "i-mine",
    "ruined_portal": "i-ruined-portal",
    "shipwreck": "i-shipwreck",
    "ocean_ruin": "i-ocean-ruin",
    "buried_treasure": "i-buried-treasure",
    "trail_ruins": "i-trail-ruins",
    "trial_chambers": "i-trial-chambers",
    "ancient_city": "i-ancient-city",
    "stronghold": "i-stronghold",
    "nether_fortress": "i-nether-fortress",
    "bastion_remnant": "i-bastion-remnant",
    "nether_fossil": "i-nether-fossil",
    "end_city": "i-end-city",
}


def symbols():
    text = SOURCE.read_text(encoding="utf-8")
    found = {}
    for m in re.finditer(r'<symbol id="([^"]+)" viewBox="([^"]+)">(.*?)</symbol>', text, re.S):
        found[m.group(1)] = (m.group(2), m.group(3))
    return found


def render(viewbox: str, body: str, target: Path):
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="{viewbox}">
<style>
*{{vector-effect:non-scaling-stroke}}
path,rect,circle,polyline,polygon,line{{fill:none;stroke:#fff;stroke-width:1.65;stroke-linecap:round;stroke-linejoin:round}}
</style>
{body}
</svg>"""
    with tempfile.TemporaryDirectory(prefix="cartograph-structure-icon-") as tmp:
        tmp = Path(tmp)
        html = tmp / "icon.html"
        png = tmp / "icon.png"
        html.write_text(
            '<!doctype html><style>html,body{margin:0;width:512px;height:512px;background:transparent}'
            'svg{width:512px;height:512px}</style>' + svg,
            encoding="utf-8")
        cmd = [
            "google-chrome", "--headless=new", "--disable-gpu", "--no-sandbox",
            "--hide-scrollbars", "--force-device-scale-factor=1",
            "--default-background-color=00000000", "--window-size=512,512",
            f"--screenshot={png}", html.resolve().as_uri(),
        ]
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0 or not png.exists():
            raise RuntimeError(result.stdout + result.stderr)
        image = Image.open(png).convert("RGBA").resize((224, 224), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
        canvas.alpha_composite(image, (16, 16))
        target.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(target)


def main():
    found = symbols()
    missing = [symbol for symbol in ICONS.values() if symbol not in found]
    if missing:
        raise RuntimeError("prototype missing symbols: " + ", ".join(missing))
    OUT.mkdir(parents=True, exist_ok=True)
    UI_OUT.mkdir(parents=True, exist_ok=True)

    for name, symbol in UI_ICONS.items():
        if symbol not in found:
            raise RuntimeError(f"prototype missing UI symbol: {symbol}")
        viewbox, body = found[symbol]
        render(viewbox, body, UI_OUT / f"{name}_mono.png")

    for name, symbol in ICONS.items():
        viewbox, body = found[symbol]
        render(viewbox, body, OUT / f"{name}_mono.png")

    print(
        f"synced {len(ICONS)} high-fidelity 256px mono structure icons "
        f"and {len(UI_ICONS)} mono UI icons from {SOURCE.name}")


if __name__ == "__main__":
    main()
