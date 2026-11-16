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
def port_front():
    """Front (faces the depot): dark opening with cyan lights on the sides."""
    im = frame(ANDESITE)
    panel(im, 3, 3, 12, 12, ANDESITE)
    rect(im, 5, 6, 10, 9, HOLE)
    for y in (6, 7, 8, 9):
        px(im, 4, y, CYAN if y in (7, 8) else CYAN_DARK)
        px(im, 11, y, CYAN if y in (7, 8) else CYAN_DARK)
    for x in range(5, 11):
        px(im, x, 10, CYAN_DARK)
    px(im, 7, 4, CYAN_LIGHT); px(im, 8, 4, CYAN_LIGHT)
    return im


def port_side():
    """Side: cyan cable conduit across the block."""
    im = frame(ANDESITE)
    rect(im, 2, 6, 13, 9, ANDESITE['darker'])
    rect(im, 2, 7, 13, 8, CYAN)
    for x in range(2, 14, 3):
        px(im, x, 7, CYAN_LIGHT)
    return im


def port_back():
    """Back: round network connector."""
    im = frame(ANDESITE)
    panel(im, 4, 4, 11, 11, ANDESITE)
    for (x, y) in ((6, 5), (7, 5), (8, 5), (9, 5), (5, 6), (10, 6), (5, 7), (10, 7), (5, 8), (10, 8), (5, 9), (10, 9),
                   (6, 10), (7, 10), (8, 10), (9, 10)):
        px(im, x, y, CYAN_DARK)
    rect(im, 7, 7, 8, 8, CYAN)
    return im


def port_top():
    """Top and bottom: grate."""
    im = frame(ANDESITE)
    panel(im, 3, 3, 12, 12, ANDESITE)
    for y in (5, 7, 9, 11):
        for x in range(4, 12):
            px(im, x, y, HOLE)
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
    'assembly_port_front': port_front,
    'assembly_port_side': port_side,
    'assembly_port_back': port_back,
    'assembly_port_top': port_top,
    'assembly_controller_front': controller_front,
    'assembly_controller_side': controller_side,
    'assembly_controller_top': controller_top,
    'crafter_interface_side': crafter_side,
    'crafter_interface_top': crafter_top,
}


def preview(path, made):
    """Isometric view of the 3 blocks (top, front on the left, side on the right)."""
    s = 8
    w = 16 * s
    canvas = Image.new('RGBA', (3 * (2 * w + 40) + 40, 2 * w + 120), (54, 57, 63, 255))

    def iso(top, left, right, ox, oy):
        def big(im):
            return im.resize((w, w), Image.NEAREST)
        t, l, r = big(top), big(left), big(right)
        # top face: diamond, mapped pixel by pixel
        top_img = Image.new('RGBA', (2 * w, w), (0, 0, 0, 0))
        for y in range(w):
            for x in range(2 * w):
                u = (x / (2 * w)) - (y / w) + 0.5
                v = (x / (2 * w)) + (y / w) - 0.5
                if 0 <= u < 1 and 0 <= v < 1:
                    top_img.putpixel((x, y), t.getpixel((int(u * w), int(v * w))))
        canvas.alpha_composite(top_img, (ox, oy))

        def side(img, shade, flip):
            out = Image.new('RGBA', (w, int(1.5 * w)), (0, 0, 0, 0))
            for y in range(out.size[1]):
                for x in range(w):
                    sy = y - (x / 2 if flip else (w - x) / 2)
                    if 0 <= sy < w:
                        c = img.getpixel((x, int(sy)))
                        out.putpixel((x, y), (int(c[0] * shade), int(c[1] * shade), int(c[2] * shade), 255))
            return out
        canvas.alpha_composite(side(l, 0.82, True), (ox, oy + w // 2))
        canvas.alpha_composite(side(r, 0.64, False), (ox + w, oy + w // 2))

    x = 30
    iso(made['assembly_port_top'], made['assembly_port_front'], made['assembly_port_side'], x, 30)
    x += 2 * w + 40
    iso(made['assembly_controller_top'], made['assembly_controller_front'], made['assembly_controller_side'], x, 30)
    x += 2 * w + 40
    iso(made['crafter_interface_top'], made['crafter_interface_side'], made['crafter_interface_side'], x, 30)
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
