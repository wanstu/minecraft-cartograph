from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SVG_ROOT = ROOT / "design" / "icons"
PNG_ROOT = ROOT / "src" / "main" / "resources" / "assets" / "cartograph_v3" / "textures" / "gui" / "icons"
SCALE = 4
STROKE = 2.2

def L(*pts): return ("polyline", pts)
def P(*pts): return ("polygon", pts)
def R(x,y,w,h): return ("rect", x,y,w,h)
def C(cx,cy,r): return ("circle", cx,cy,r)
def LN(x1,y1,x2,y2): return ("line", x1,y1,x2,y2)

icons = {
    "ui/menu": [LN(7,9,25,9), LN(7,16,25,16), LN(7,23,25,23)],
    "ui/markers": [C(16,11,5), L((12,15),(16,25),(20,15)), C(16,11,1.5)],
    "ui/layers": [P((16,5),(27,11),(16,17),(5,11)), L((6,16),(16,22),(26,16)), L((6,21),(16,27),(26,21))],
    "ui/settings": [C(16,16,5), C(16,16,2), LN(16,4,16,8), LN(16,24,16,28), LN(4,16,8,16), LN(24,16,28,16), LN(8,8,11,11), LN(21,21,24,24), LN(8,24,11,21), LN(21,11,24,8)],
    "ui/locate": [C(16,16,8), C(16,16,2), LN(16,4,16,9), LN(16,23,16,28), LN(4,16,9,16), LN(23,16,28,16)],
    "ui/add": [C(16,16,10), LN(16,10,16,22), LN(10,16,22,16)],
    "ui/teleport": [P((16,4),(27,16),(16,28),(5,16)), P((16,9),(22,16),(16,23),(10,16))],
    "ui/edit": [L((7,24),(9,18),(21,6),(26,11),(14,23),(7,24)), LN(19,8,24,13)],
    "ui/trash": [R(9,10,14,17), LN(7,8,25,8), LN(12,5,20,5), LN(13,13,13,23), LN(19,13,19,23)],
    "ui/restore": [L((25,9),(19,9),(22,5)), L((20,9),(15,9),(10,12),(8,17),(10,22),(14,25),(20,25),(24,21))],
    "ui/reset": [L((9,10),(13,6),(18,6),(23,10),(25,15)), L((23,22),(19,26),(14,26),(9,22),(7,17)), L((7,10),(9,10),(9,7)), L((25,22),(23,22),(23,25))],
    "ui/close": [LN(8,8,24,24), LN(24,8,8,24)],

    "marker/pin": [C(16,11,5), L((12,15),(16,27),(20,15)), C(16,11,1.7)],
    "marker/village": [P((5,15),(16,6),(27,15)), R(8,14,16,13), R(14,20,4,7), R(10,16,4,4), R(19,16,3,4)],
    "marker/mansion": [P((5,13),(16,5),(27,13)), R(7,13,18,14), LN(10,15,10,25), LN(14,15,14,25), LN(18,15,18,25), LN(22,15,22,25), LN(5,27,27,27)],
    "marker/temple": [P((5,12),(16,5),(27,12)), LN(7,13,25,13), LN(9,14,9,25), LN(14,14,14,25), LN(19,14,19,25), LN(24,14,24,25), LN(6,26,26,26)],
    "marker/outpost": [P((8,8),(16,4),(24,8)), R(10,8,12,18), LN(10,15,22,15), LN(13,10,13,24), LN(19,10,19,24), LN(8,27,24,27)],
    "marker/mine": [L((7,23),(12,18),(22,8),(25,11),(15,21),(10,26)), L((9,8),(12,5),(21,14)), LN(11,7,24,20)],
    "marker/portal": [R(7,4,18,24), R(11,8,10,16), LN(9,6,12,9), LN(20,23,23,26)],
    "marker/ship": [L((5,20),(8,26),(23,26),(27,20),(5,20)), LN(16,6,16,20), P((17,8),(25,14),(17,16)), LN(8,22,25,22)],
    "marker/city": [R(5,16,6,11), R(13,10,7,17), R(22,14,5,13), LN(4,27,28,27), LN(16,6,16,10), LN(8,19,8,22), LN(16,14,16,17), LN(24,18,24,21)],
    "marker/fortress": [L((6,8),(10,8),(10,12),(14,12),(14,8),(18,8),(18,12),(22,12),(22,8),(26,8),(26,27),(6,27),(6,8)), R(13,19,6,8), LN(6,15,26,15)],
    "marker/home": [P((5,15),(16,6),(27,15)), R(8,14,16,13), R(14,20,4,7)],
    "marker/flag": [LN(10,5,10,27), P((11,7),(24,10),(11,15))],
    "marker/camp": [P((6,25),(16,7),(26,25)), LN(11,25,16,17), LN(21,25,16,17), LN(5,27,27,27)],
    "marker/farm": [L((6,24),(10,16),(16,13),(22,16),(26,24)), LN(16,13,16,27), LN(9,21,23,21), L((8,11),(11,8),(14,11)), L((18,10),(21,7),(24,10))],
    "marker/shop": [R(7,13,18,14), L((6,13),(9,7),(23,7),(26,13)), LN(10,13,10,17), LN(15,13,15,17), LN(20,13,20,17), R(13,20,6,7)],
    "marker/rail": [LN(10,5,10,27), LN(22,5,22,27), LN(10,8,22,8), LN(10,13,22,13), LN(10,18,22,18), LN(10,23,22,23)],
    "marker/harbor": [LN(16,5,16,25), C(16,9,3), L((8,18),(8,22),(12,26),(20,26),(24,22),(24,18)), LN(10,19,22,19)],
    "marker/factory": [R(6,14,20,13), L((6,14),(12,9),(12,14),(18,9),(18,14)), R(21,5,4,9), LN(10,20,10,24), LN(16,20,16,24), LN(22,20,22,24)],
    "marker/tower": [L((10,27),(13,6),(19,6),(22,27)), LN(12,15,20,15), LN(11,21,21,21), P((11,6),(16,3),(21,6))],
    "marker/cave": [L((5,25),(8,14),(13,8),(19,7),(25,13),(28,25)), L((11,25),(12,18),(16,14),(20,18),(21,25))],
    "marker/mountain": [P((4,26),(13,10),(18,18),(22,12),(28,26)), L((10,15),(13,10),(16,15)), LN(4,26,28,26)],
    "marker/tree": [C(16,11,6), C(11,15,5), C(21,15,5), LN(16,16,16,28), LN(12,28,20,28)],
    "marker/water": [L((5,12),(9,10),(13,12),(17,10),(21,12),(25,10),(28,12)), L((5,18),(9,16),(13,18),(17,16),(21,18),(25,16),(28,18)), L((5,24),(9,22),(13,24),(17,22),(21,24),(25,22),(28,24))],
    "marker/danger": [P((16,4),(29,27),(3,27)), LN(16,11,16,19), C(16,23,1)],
    "marker/info": [C(16,16,11), C(16,10,1), LN(16,14,16,23), LN(13,23,19,23)],
    "marker/food": [C(16,17,8), LN(8,7,8,27), LN(5,7,5,14), LN(11,7,11,14), LN(24,7,24,27), L((21,7),(24,12),(27,7))],
    "marker/star": [P((16,4),(19,12),(28,12),(21,17),(24,27),(16,21),(8,27),(11,17),(4,12),(13,12))],
}

def svg_for(cmds):
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none" stroke="white" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">']
    for cmd in cmds:
        t = cmd[0]
        if t == "line":
            _,x1,y1,x2,y2 = cmd
            parts.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}"/>')
        elif t == "polyline":
            pts = " ".join(f"{x},{y}" for x,y in cmd[1])
            parts.append(f'<polyline points="{pts}"/>')
        elif t == "polygon":
            pts = " ".join(f"{x},{y}" for x,y in cmd[1])
            parts.append(f'<polygon points="{pts}"/>')
        elif t == "rect":
            _,x,y,w,h = cmd
            parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="1"/>')
        elif t == "circle":
            _,cx,cy,r = cmd
            parts.append(f'<circle cx="{cx}" cy="{cy}" r="{r}"/>')
    parts.append("</svg>")
    return "\n".join(parts) + "\n"

def draw_png(cmds, out):
    size = 32 * SCALE
    im = Image.new("RGBA", (size,size), (0,0,0,0))
    d = ImageDraw.Draw(im)
    w = max(1, round(STROKE*SCALE))
    def pt(p): return (round(p[0]*SCALE), round(p[1]*SCALE))
    for cmd in cmds:
        t=cmd[0]
        if t=="line":
            d.line([pt((cmd[1],cmd[2])),pt((cmd[3],cmd[4]))], fill="white", width=w)
        elif t=="polyline":
            d.line([pt(p) for p in cmd[1]], fill="white", width=w, joint="curve")
        elif t=="polygon":
            pts=[pt(p) for p in cmd[1]]
            d.line(pts+[pts[0]], fill="white", width=w, joint="curve")
        elif t=="rect":
            _,x,y,rw,rh=cmd
            d.rounded_rectangle([x*SCALE,y*SCALE,(x+rw)*SCALE,(y+rh)*SCALE], radius=SCALE, outline="white", width=w)
        elif t=="circle":
            _,cx,cy,r=cmd
            d.ellipse([(cx-r)*SCALE,(cy-r)*SCALE,(cx+r)*SCALE,(cy+r)*SCALE], outline="white", width=w)
    im=im.resize((32,32), Image.Resampling.LANCZOS)
    out.parent.mkdir(parents=True, exist_ok=True)
    im.save(out)

for name,cmds in icons.items():
    svg_path = SVG_ROOT / f"{name}.svg"
    png_path = PNG_ROOT / f"{name}.png"
    svg_path.parent.mkdir(parents=True, exist_ok=True)
    svg_path.write_text(svg_for(cmds), encoding="utf-8")
    draw_png(cmds, png_path)

print(f"generated {len(icons)} SVG+PNG icon pairs")
