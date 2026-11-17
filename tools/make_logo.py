"""Project logo (CurseForge / Modrinth page and the mod list): the Assembly Controller with an Assembly Port and the
Crafter Interface, drawn isometrically with the block textures, on a background split between RS blue and Create
brass. Usage: python tools/make_logo.py <project dir> <output png>"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

sys.path.insert(0, os.path.dirname(__file__))
import make_textures as mt  # noqa: E402

SIZE = 512


def background():
    im = Image.new('RGBA', (SIZE, SIZE))
    draw = ImageDraw.Draw(im)
    blue, brass = (38, 70, 120), (150, 104, 44)
    for y in range(SIZE):
        for x in range(0, SIZE, 4):
            t = max(0.0, min(1.0, (x + y - SIZE * 0.85) / (SIZE * 0.3) + 0.5))
            c = tuple(int(blue[i] * (1 - t) + brass[i] * t) for i in range(3))
            k = 0.75 + 0.25 * (1 - ((x - SIZE / 2) ** 2 + (y - SIZE / 2) ** 2) ** 0.5 / (SIZE * 0.75))
            draw.line([(x, y), (x + 3, y)], fill=tuple(int(v * k) for v in c) + (255,))
    # Faint grid, like a base plate.
    for i in range(0, SIZE, 32):
        draw.line([(i, 0), (i, SIZE)], fill=(255, 255, 255, 14))
        draw.line([(0, i), (SIZE, i)], fill=(255, 255, 255, 14))
    return im


def main():
    made = {name: fn() for name, fn in mt.TEXTURES.items()}
    canvas = background()
    blocks = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    s = 9

    def cube(top, front, side, ox, oy, size=16):
        mt.iso_boxes(blocks, [((0, 0, 0), (size, size, size), {
            'up': (top, (0, 0, 16, 16)), 'south': (front, (0, 0, 16, 16)), 'east': (side, (0, 0, 16, 16))})],
            made, ox, oy, s)

    # Crafter Interface behind, Controller in front, Port on the Controller's east side pointing east.
    cube('crafter_interface_top', 'crafter_interface_side', 'crafter_interface_side', 150, 195)
    cube('assembly_controller_top', 'assembly_controller_front', 'assembly_controller_side', 205, 290)
    port = [
        ((16, 6, 6), (25, 10, 10), {'south': ('assembly_port_cable', (7, 6, 16, 10)),
                                    'up': ('assembly_port_cable', (7, 6, 16, 10))}),
        ((25, 4, 4), (29, 12, 12), {'south': ('assembly_port_body', (3, 4, 7, 12)),
                                    'up': ('assembly_port_body', (4, 3, 12, 7))}),
        ((29, 2, 2), (32, 14, 14), {'east': ('assembly_port_head', (2, 2, 14, 14)),
                                    'south': ('assembly_port_body', (0, 2, 3, 14)),
                                    'up': ('assembly_port_body', (2, 0, 14, 3))}),
    ]
    mt.iso_boxes(blocks, port, made, 205, 290, s)
    shadow = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 120), (8, 14), blocks.split()[3])
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(10)))
    canvas.alpha_composite(blocks)
    canvas.save(sys.argv[2])
    print('logo written to', sys.argv[2])


if __name__ == '__main__':
    main()
