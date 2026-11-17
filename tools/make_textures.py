"""Generates the block textures (16x16, opaque) and an optional isometric preview.

Usage:  python tools/make_textures.py <project root> [<preview png>]

Original pixel art (no Create textures are copied): a riveted metal frame in the style of Create's casings,
plus Refined Storage's cyan for anything network related.
"""
import os
import sys

from PIL import Image

ROOT = sys.argv[1]
OUT = os.path.join(ROOT, 'src/main/resources/assets/rscreatebridge/textures/block')

# --------------------------------------------------------------------------- palettes
ANDESITE = dict(light=(170, 175, 166), base=(138, 143, 135), dark=(96, 101, 94), darker=(64, 68, 62))
BRASS = dict(light=(240, 205, 120), base=(205, 160, 66), dark=(150, 108, 38), darker=(98, 70, 24))
PANEL = (40, 44, 52)
PANEL_DARK = (26, 28, 34)
HOLE = (14, 15, 18)
CYAN = (66, 214, 230)
CYAN_LIGHT = (170, 246, 255)
CYAN_DARK = (28, 120, 136)
CABLE = (78, 82, 90)
CABLE_DARK = (46, 49, 55)
GREEN = (96, 230, 110)
AMBER = (250, 190, 70)
DIM = (60, 66, 76)


def new():
    return Image.new('RGBA', (16, 16), (0, 0, 0, 255))


def px(im, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        im.putpixel((x, y), c + (255,) if len(c) == 3 else c)


def rect(im, x0, y0, x1, y1, c):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            px(im, x, y, c)


def frame(pal):
    """Casing frame: dark border, light bevel top/left, dark bevel bottom/right, 4 rivets."""
    im = new()
    rect(im, 0, 0, 15, 15, pal['base'])
    # subtle metal texture
    for i in range(0, 16, 3):
        px(im, (i * 7) % 16, (i * 5 + 3) % 16, pal['light'])
        px(im, (i * 11 + 4) % 16, (i * 3 + 9) % 16, pal['dark'])
    for i in range(16):
        px(im, i, 0, pal['darker']); px(im, i, 15, pal['darker'])
        px(im, 0, i, pal['darker']); px(im, 15, i, pal['darker'])
    for i in range(1, 15):
        px(im, i, 1, pal['light']); px(im, 1, i, pal['light'])
        px(im, i, 14, pal['dark']); px(im, 14, i, pal['dark'])
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        px(im, x, y, pal['light'])
        px(im, x + 1 if x < 8 else x - 1, y + 1 if y < 8 else y - 1, pal['dark'])
    return im


def panel(im, x0, y0, x1, y1, pal):
    """Recessed panel: shadow top/left, highlight bottom/right."""
    rect(im, x0, y0, x1, y1, PANEL)
    for x in range(x0, x1 + 1):
        px(im, x, y0, PANEL_DARK)
        px(im, x, y1 + 1, pal['light'])
    for y in range(y0, y1 + 1):
        px(im, x0, y, PANEL_DARK)
        px(im, x1 + 1, y, pal['light'])


def gear(im, cx, cy, c, hole):
    """8x8 gear with 8 teeth centered on (cx, cy) (top-left pixel of the center)."""
    shape = [
        "...##...",
        ".#.##.#.",
        "..####..",
        "####.###",
        "###.####",
        "..####..",
        ".#.##.#.",
        "...##...",
    ]
    for dy, row in enumerate(shape):
        for dx, ch in enumerate(row):
            if ch == '#':
                px(im, cx - 4 + dx, cy - 4 + dy, c)
    rect(im, cx - 1, cy - 1, cx, cy, hole)


# --------------------------------------------------------------------------- Assembly Port (andesite)
# The port is shaped like an RS importer/exporter: a head facing the Create station (front), a neck and an RS
# cable stub on the back. The head's front face uses the 12x12 area at 2..13 of the head texture.
def port_head():
    """Head front: andesite frame with rivets around a dark opening lit in cyan."""
    im = new()
    rect(im, 0, 0, 15, 15, ANDESITE['base'])
    for i in range(2, 14):
        px(im, i, 2, ANDESITE['light']); px(im, 2, i, ANDESITE['light'])
        px(im, i, 13, ANDESITE['darker']); px(im, 13, i, ANDESITE['darker'])
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        px(im, x, y, ANDESITE['light'])
    rect(im, 5, 5, 10, 10, PANEL_DARK)
    rect(im, 6, 6, 9, 9, HOLE)
    for i in range(6, 10):
        px(im, 5, i, CYAN if i in (7, 8) else CYAN_DARK)
        px(im, 10, i, CYAN if i in (7, 8) else CYAN_DARK)
    px(im, 7, 4, CYAN_LIGHT); px(im, 8, 4, CYAN_LIGHT)
    return im


def port_body():
    """Neck and head sides: plain andesite casing with a darker edge."""
    im = new()
    rect(im, 0, 0, 15, 15, ANDESITE['base'])
    for i in range(0, 16, 3):
        px(im, (i * 7) % 16, (i * 5 + 3) % 16, ANDESITE['light'])
        px(im, (i * 11 + 4) % 16, (i * 3 + 9) % 16, ANDESITE['dark'])
    for i in range(16):
        px(im, i, 0, ANDESITE['dark']); px(im, 0, i, ANDESITE['dark'])
        px(im, i, 15, ANDESITE['darker']); px(im, 15, i, ANDESITE['darker'])
    return im


def port_cable():
    """RS-style cable: a dark band (rows 6..9) with a cyan core line along it; the end cap uses 6..9 x 6..9."""
    im = new()
    rect(im, 0, 0, 15, 15, CABLE)
    rect(im, 0, 6, 15, 9, CABLE)
    for x in range(16):
        px(im, x, 6, CABLE_DARK); px(im, x, 9, CABLE_DARK)
        px(im, x, 7, CYAN if x % 4 else CYAN_LIGHT); px(im, x, 8, CYAN_DARK)
    rect(im, 6, 6, 9, 9, CABLE_DARK)
    rect(im, 7, 7, 8, 8, CYAN)
    return im


# --------------------------------------------------------------------------- Assembly Controller (brass)
def controller_front():
    """Front: screen with status lights (pieces in progress)."""
    im = frame(BRASS)
    panel(im, 3, 3, 12, 12, BRASS)
    colors = [CYAN, GREEN, DIM, GREEN, CYAN, AMBER, DIM, CYAN, GREEN]
    i = 0
    for gy in (4, 7, 10):
        for gx in (4, 7, 10):
            rect(im, gx, gy, gx + 1, gy + 1, colors[i])
            i += 1
    return im


def controller_side():
    """Sides: brass gear on a panel."""
    im = frame(BRASS)
    panel(im, 3, 3, 12, 12, BRASS)
    gear(im, 8, 8, BRASS['light'], PANEL_DARK)
    for (dx, dy) in ((1, 1), (2, 0), (0, 2)):
        px(im, 8 + dx, 8 + dy, BRASS['base'])
    return im


def controller_top():
    """Top: vents and a cyan network light."""
    im = frame(BRASS)
    for y in (4, 6, 8, 10):
        for x in range(4, 12):
            px(im, x, y, BRASS['darker'])
            px(im, x, y + 1, BRASS['light'])
    rect(im, 7, 12, 8, 12, CYAN)
    return im


# --------------------------------------------------------------------------- Crafter Interface (brass)
def crafter_side():
    """Sides: 3x3 crafting-like grid with cyan lights (the items RS delivers)."""
    im = frame(BRASS)
    panel(im, 3, 3, 12, 12, BRASS)
    lit = {(0, 0), (1, 1), (2, 0), (1, 2)}
    for i, gy in enumerate((4, 7, 10)):
        for j, gx in enumerate((4, 7, 10)):
            rect(im, gx, gy, gx + 1, gy + 1, CYAN if (j, i) in lit else DIM)
    return im


def crafter_top():
    im = frame(BRASS)
    gear(im, 8, 8, BRASS['light'], BRASS['darker'])
    return im


TEXTURES = {
    'assembly_port_head': port_head,
    'assembly_port_body': port_body,
    'assembly_port_cable': port_cable,
    'assembly_controller_front': controller_front,
    'assembly_controller_side': controller_side,
    'assembly_controller_top': controller_top,
    'crafter_interface_side': crafter_side,
    'crafter_interface_top': crafter_top,
}


def iso_boxes(canvas, boxes, made, ox, oy, s):
    """Isometric view of axis-aligned boxes (model pixels), seen from +x +y +z: draws up, east and south faces.
    Each box is (from, to, {face: (texture name, (u1, v1, u2, v2))})."""
    from PIL import ImageDraw
    draw = ImageDraw.Draw(canvas)

    def screen(x, y, z):
        return (ox + (x - z) * s, oy + (x + z) * s / 2 - y * s)

    shade = {'up': 1.0, 'south': 0.82, 'east': 0.64}
    for (f, t, faces) in sorted(boxes, key=lambda b: (b[0][0] + b[1][0]) + (b[0][2] + b[1][2]) + (b[0][1] + b[1][1])):
        for face in ('south', 'east', 'up'):
            if face not in faces:
                continue
            tex, (u1, v1, u2, v2) = faces[face]
            img = made[tex]
            if face == 'up':
                w, h = t[0] - f[0], t[2] - f[2]
                def point(a, b): return screen(f[0] + a, t[1], f[2] + b)
            elif face == 'south':
                w, h = t[0] - f[0], t[1] - f[1]
                def point(a, b): return screen(f[0] + a, t[1] - b, t[2])
            else:
                w, h = t[2] - f[2], t[1] - f[1]
                def point(a, b): return screen(t[0], t[1] - b, t[2] - a)
            for i in range(w):
                for j in range(h):
                    u = int(u1 + (u2 - u1) * (i + 0.5) / w)
                    v = int(v1 + (v2 - v1) * (j + 0.5) / h)
                    c = img.getpixel((min(u, 15), min(v, 15)))
                    k = shade[face]
                    col = (int(c[0] * k), int(c[1] * k), int(c[2] * k), 255)
                    draw.polygon([point(i, j), point(i + 1, j), point(i + 1, j + 1), point(i, j + 1)], fill=col)


def preview(path, made):
    """Preview: the Assembly Port with its real model (head towards the viewer, cable going back), next to the
    Controller and the Crafter Interface as cubes."""
    s = 8
    canvas = Image.new('RGBA', (3 * 300 + 40, 330), (54, 57, 63, 255))
    # Port model turned 180 degrees so the head (model north) faces the viewer (south).
    def turn(f, t):
        return ((16 - t[0], f[1], 16 - t[2]), (16 - f[0], t[1], 16 - f[2]))
    port = [
        ((2, 2, 0), (14, 14, 3), {'south': ('assembly_port_head', (2, 2, 14, 14)),
                                 'east': ('assembly_port_body', (0, 2, 3, 14)),
                                 'up': ('assembly_port_body', (2, 0, 14, 3))}),
        ((4, 4, 3), (12, 12, 7), {'east': ('assembly_port_body', (3, 4, 7, 12)),
                                 'up': ('assembly_port_body', (4, 3, 12, 7))}),
        ((6, 6, 7), (10, 10, 16), {'east': ('assembly_port_cable', (7, 6, 16, 10)),
                                  'up': ('assembly_port_cable', (7, 6, 16, 10))}),
    ]
    boxes = []
    for f, t, faces in port:
        nf, nt = turn(f, t)
        boxes.append((nf, nt, faces))
    iso_boxes(canvas, boxes, made, 160, 200, s)

    def cube(top, front, side, ox):
        iso_boxes(canvas, [((0, 0, 0), (16, 16, 16), {'up': (top, (0, 0, 16, 16)), 'south': (front, (0, 0, 16, 16)),
                                                        'east': (side, (0, 0, 16, 16))})], made, ox, 200, s)
    cube('assembly_controller_top', 'assembly_controller_front', 'assembly_controller_side', 460)
    cube('crafter_interface_top', 'crafter_interface_side', 'crafter_interface_side', 760)
    canvas.save(path)


def main():
    os.makedirs(OUT, exist_ok=True)
    made = {}
    for name, fn in TEXTURES.items():
        im = fn()
        im.save(os.path.join(OUT, name + '.png'))
        made[name] = im
    if len(sys.argv) > 2:
        preview(sys.argv[2], made)
    print('generated', len(made), 'textures')


main()
