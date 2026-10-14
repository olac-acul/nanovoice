#!/usr/bin/env python3
"""Regenerates the launcher icon layers from docs/branding/icon-source.png.

The artwork is a rounded square (pixels 20..1233 of a 1254 px image) with a white "n" arch on a teal-to-blue gradient,
drawn over a checkerboard that is painted in, not real transparency. So the image is not used as is:
- background: res/drawable/ic_launcher_background.xml, a vector gradient with stops sampled from the artwork;
- foreground: the "n" alone, cut out by its brightness (the blue has almost no red, the glyph a lot);
- monochrome: the same "n" in white, for themed icons.
The rounded square is mapped onto the 72 dp visible part of the 108 dp adaptive canvas (432 px at xxxhdpi), so the
glyph keeps its size and position. Needs: pip install pillow numpy.
"""
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "docs/branding/icon-source.png"
RES = ROOT / "app/src/main/res/drawable-nodpi"
ART = (20, 1234)              # the rounded square, both axes
GLYPH = (330, 313, 924, 982)  # x0, y0, x1, y1: the "n" with a margin for its soft edge
CANVAS, VISIBLE = 432, 288    # 108 dp and its 72 dp visible part, at 4 px per dp

src = np.array(Image.open(SOURCE).convert("RGB")).astype(float)
x0, y0, x1, y1 = GLYPH
region = src[y0:y1, x0:x1]
# Red separates the glyph (R 140..255) from the gradient (R below 30); ramp between for an anti-aliased edge.
alpha = np.clip((region[..., 0] - 35) / (130 - 35), 0, 1)

scale = VISIBLE / (ART[1] - ART[0])
offset = (CANVAS - VISIBLE) / 2
size = (round((x1 - x0) * scale), round((y1 - y0) * scale))
at = (round(offset + (x0 - ART[0]) * scale), round(offset + (y0 - ART[0]) * scale))

def layer(rgb):
    img = np.dstack([rgb, alpha * 255]).astype("uint8")
    glyph = Image.fromarray(img, "RGBA").resize(size, Image.LANCZOS)
    out = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    out.paste(glyph, at, glyph)
    return out

layer(region).save(RES / "ic_launcher_foreground.png")
layer(np.full_like(region, 255)).save(RES / "ic_launcher_monochrome.png")
print("wrote", RES / "ic_launcher_foreground.png", "and ic_launcher_monochrome.png")
