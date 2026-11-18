#!/usr/bin/env python3
"""Render the AoD store / promo bitmaps.

The numbers below are the same design numbers as docs/icon.svg, docs/icon-square.svg
and docs/icon-monochrome.svg (and as the adaptive launcher layers in
app/src/main/res). Change the design in one place, re-run this, and the store
assets follow.

Needs Pillow only:  python3 -m pip install Pillow

Usage
-----
  # refresh the fastlane images (512x512 icon + 1024x500 feature graphic)
  python3 docs/render_assets.py

  # icon only, no font needed
  python3 docs/render_assets.py --no-feature

  # one-off promo export, any size, rounded card or full square
  python3 docs/render_assets.py --icon-size 1024 --icon-out /tmp/icon-1024.png
  python3 docs/render_assets.py --icon-size 512 --square --icon-out /tmp/play.png

The feature graphic needs a font. Inter is used when it can be found; pass
--font /path/to/Inter.ttf (or any .ttf) to pick one explicitly. See docs/brand.md.
"""

import argparse
import glob
import math
import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required:  python3 -m pip install Pillow")

# ---------------------------------------------------------------- design ----
CARD = 58.0            # card side, design units
CORNER = 15.0          # card corner radius
RING_R = 16.0          # ring radius (centre line)
RING_W = 3.4           # ring stroke
DOT_R = 4.6            # dot radius
DOT_ANGLE = -45.0      # dot position on the ring, degrees
HAIR_W = 0.6           # hairline stroke on the card edge
HAIR_A = 0.102         # hairline opacity

CARD_A = (0x17, 0x1C, 0x28)   # card gradient, top left
CARD_B = (0x0B, 0x0E, 0x15)   # card gradient, bottom right
CYAN = (0x35, 0xD6, 0xF0)
WHITE = (0xFF, 0xFF, 0xFF)
TEXT_MUTED = (0xC2, 0xCC, 0xDA)

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_IMAGES = os.path.join(REPO, "fastlane", "metadata", "android", "en-US", "images")

INTER_URL = ("https://raw.githubusercontent.com/google/fonts/main/ofl/inter/"
             "Inter%5Bopsz,wght%5D.ttf")


# ------------------------------------------------------------- primitives ---
def diagonal_gradient(w, h, a, b):
    """Linear gradient from a (top left) to b (bottom right), sRGB, opaque."""
    n = 64
    m = Image.new("L", (n, n))
    m.putdata([int(round((x + y) / (2.0 * (n - 1)) * 255))
               for y in range(n) for x in range(n)])
    m = m.resize((w, h), Image.BICUBIC)
    return Image.composite(Image.new("RGBA", (w, h), b + (255,)),
                           Image.new("RGBA", (w, h), a + (255,)), m)


def render_icon(size, square=False, card_frac=0.96, ss=4):
    """The app icon as RGBA.

    square=False -> rounded card at card_frac of the canvas, transparent
                    outside (this is docs/icon.svg).
    square=True  -> edge to edge, no corners, no alpha, no hairline
                    (this is docs/icon-square.svg, the store listing icon).
    """
    S = size * ss
    side = int(round(S if square else S * card_frac))
    off = int(round((S - side) / 2.0))
    radius = 0 if square else side * CORNER / CARD

    card = diagonal_gradient(side, side, CARD_A, CARD_B)
    mask = Image.new("L", (side, side), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, side - 1, side - 1],
                                           radius=radius, fill=255)
    card.putalpha(mask)

    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.alpha_composite(card, (off, off))

    if not square:
        hw = max(1, int(round(side * HAIR_W / CARD)))
        hair = Image.new("RGBA", (S, S), (0, 0, 0, 0))
        # same trick as the ring: grow the box by half the stroke so the
        # hairline straddles the card edge instead of sitting inside it
        ImageDraw.Draw(hair).rounded_rectangle(
            [off - hw / 2.0, off - hw / 2.0,
             off + side - 1 + hw / 2.0, off + side - 1 + hw / 2.0],
            radius=radius + hw / 2.0,
            outline=WHITE + (int(round(255 * HAIR_A)),), width=hw)
        img.alpha_composite(hair)

    d = ImageDraw.Draw(img)
    cx = cy = S / 2.0
    rr = side * RING_R / CARD          # centre line of the stroke
    w = max(1, int(round(side * RING_W / CARD)))
    # Pillow strokes an ellipse *inside* the box, so grow the box by half the
    # stroke to keep the centre line exactly on rr (as in the SVG).
    box = [cx - rr - w / 2.0, cy - rr - w / 2.0, cx + rr + w / 2.0, cy + rr + w / 2.0]
    d.ellipse(box, outline=CYAN + (255,), width=w)

    a = math.radians(DOT_ANGLE)
    dx, dy, dr = cx + rr * math.cos(a), cy + rr * math.sin(a), side * DOT_R / CARD
    d.ellipse([dx - dr, dy - dr, dx + dr, dy + dr], fill=WHITE + (255,))

    return img.resize((size, size), Image.LANCZOS)


def render_mark(size, color=WHITE, ss=4):
    """Ring + dot alone, optically centred, 1/32 of the canvas as clear space."""
    S = size * ss
    outer = RING_R + RING_W / 2.0
    scale = (S / 2.0 * (1 - 1 / 16.0)) / outer
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = S / 2.0
    rr = RING_R * scale
    w = max(1, int(round(RING_W * scale)))
    box = [cx - rr - w / 2.0, cy - rr - w / 2.0, cx + rr + w / 2.0, cy + rr + w / 2.0]
    d.ellipse(box, outline=color + (255,), width=w)
    a = math.radians(DOT_ANGLE)
    dx, dy, dr = cx + rr * math.cos(a), cy + rr * math.sin(a), DOT_R * scale
    d.ellipse([dx - dr, dy - dr, dx + dr, dy + dr], fill=color + (255,))
    return img.resize((size, size), Image.LANCZOS)


# ------------------------------------------------------------------ fonts ---
def find_font(explicit=None):
    cands = []
    if explicit:
        cands.append(explicit)
    if os.environ.get("AOD_PROMO_FONT"):
        cands.append(os.environ["AOD_PROMO_FONT"])
    cands += ["/tmp/fonts/Inter.ttf", os.path.expanduser("~/fonts/Inter.ttf")]
    for pat in ("/usr/share/fonts/**/Inter*.ttf",
                os.path.expanduser("~/.fonts/**/Inter*.ttf"),
                "/usr/share/fonts/**/DejaVuSans.ttf",
                "/usr/share/fonts/**/LiberationSans-Regular.ttf",
                "/app/node_modules/pdfjs-dist/standard_fonts/LiberationSans-Regular.ttf",
                "/opt/arena-python/lib/python3.11/site-packages/reportlab/fonts/Vera.ttf"):
        cands += sorted(glob.glob(pat, recursive=True))
    for c in cands:
        if c and os.path.isfile(c):
            return c
    return None


def load_font(path, px, weight=400, opsz=32):
    f = ImageFont.truetype(path, px)
    try:                       # variable font (Inter): pick optical size + weight
        f.set_variation_by_axes([opsz, weight])
    except Exception:
        pass                   # static font: nothing to pick
    return f


def wrap(draw, text, font, max_w):
    lines, cur = [], ""
    for word in text.split():
        trial = (cur + " " + word).strip()
        if draw.textlength(trial, font=font) <= max_w or not cur:
            cur = trial
        else:
            lines.append(cur)
            cur = word
    if cur:
        lines.append(cur)
    return lines


def tracked(draw, xy, text, font, fill, tracking):
    """Draw text with letter spacing (Pillow has no tracking of its own)."""
    x, y = xy
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill, anchor="lt")
        x += draw.textlength(ch, font=font) + tracking
    return x


def tracked_width(draw, text, font, tracking):
    return sum(draw.textlength(c, font=font) for c in text) + tracking * (len(text) - 1)


# -------------------------------------------------------- feature graphic ---
def render_feature(font_path, w=1024, h=500, ss=2):
    W, H = w * ss, h * ss
    img = diagonal_gradient(W, H, CARD_B, CARD_A)

    # soft cyan wash behind the icon, so the card does not sit on flat black
    icon_px = 300
    ix, iy = 88, (h - icon_px) // 2
    gsize = int(icon_px * 2.6 * ss)
    n = 128
    g = Image.new("L", (n, n))
    g.putdata([int(round(255 * 0.18 * max(0.0, 1.0 - math.hypot(
        (x + 0.5) / n * 2 - 1, (y + 0.5) / n * 2 - 1)) ** 2))
        for y in range(n) for x in range(n)])
    glow = g.resize((gsize, gsize), Image.BICUBIC)
    gmask = Image.new("L", (W, H), 0)
    gmask.paste(glow, (int((ix + icon_px / 2) * ss - gsize / 2),
                       int((iy + icon_px / 2) * ss - gsize / 2)))
    wash = Image.new("RGBA", (W, H), CYAN + (255,))
    wash.putalpha(gmask)
    img.alpha_composite(wash)

    card = render_icon(icon_px * ss, card_frac=0.96, ss=2)
    img.alpha_composite(card, (ix * ss, iy * ss))

    d = ImageDraw.Draw(img)
    tx = (ix + icon_px + 56) * ss
    max_w = W - tx - 64 * ss

    f_title = load_font(font_path, 100 * ss, 650, 32)
    f_tag = load_font(font_path, 32 * ss, 400, 24)
    f_kick = load_font(font_path, 21 * ss, 600, 14)

    title = "AoD"
    tagline = "A notification light for phones that do not have one"
    tracking = 2.2 * ss
    for kicker in ("NO ROOT  ·  NO ADS  ·  NO INTERNET PERMISSION",
                   "NO ROOT  ·  NO INTERNET PERMISSION",
                   "NO ROOT  ·  NO ADS  ·  OPEN SOURCE"):
        if tracked_width(d, kicker, f_kick, tracking) <= max_w:
            break

    tag_lines = wrap(d, tagline, f_tag, max_w)
    if tracked_width(d, kicker, f_kick, tracking) > max_w:
        kicker = "NO ROOT  ·  NO ADS  ·  OPEN SOURCE"

    lh = int(42 * ss)
    gap1, gap2 = int(20 * ss), int(26 * ss)
    title_h = d.textbbox((0, 0), title, font=f_title, anchor="lt")[3]
    kick_h = d.textbbox((0, 0), kicker, font=f_kick, anchor="lt")[3]
    total = title_h + gap1 + lh * len(tag_lines) + gap2 + kick_h
    y = (H - total) // 2

    d.text((tx, y), title, font=f_title, fill=WHITE + (255,), anchor="lt")
    y += title_h + gap1
    for line in tag_lines:
        d.text((tx, y), line, font=f_tag, fill=TEXT_MUTED + (255,), anchor="lt")
        y += lh
    y += gap2
    tracked(d, (tx, y), kicker, f_kick, CYAN + (255,), tracking)

    return img.resize((w, h), Image.LANCZOS).convert("RGB")  # 24-bit, no alpha


# ------------------------------------------------------------------- main ---
def save(img, path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print("  %-58s %4dx%-4d %7d B" % (os.path.relpath(path, REPO),
                                      img.width, img.height, os.path.getsize(path)))


def main():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--images-dir", default=DEFAULT_IMAGES,
                   help="fastlane images directory (default: en-US)")
    p.add_argument("--font", help="ttf for the feature graphic (Inter preferred)")
    p.add_argument("--no-feature", action="store_true", help="skip the feature graphic")
    p.add_argument("--icon-size", type=int, help="also export a promo icon at this size")
    p.add_argument("--icon-out", help="path for --icon-size (default: next to the script)")
    p.add_argument("--square", action="store_true",
                   help="--icon-size exports the full-square variant")
    p.add_argument("--mark", action="store_true",
                   help="--icon-size exports the bare ring+dot mark instead")
    a = p.parse_args()

    print("AoD assets:")
    save(render_icon(512, square=True), os.path.join(a.images_dir, "icon.png"))

    if not a.no_feature:
        font = find_font(a.font)
        if not font:
            sys.exit("No font found for the feature graphic. Pass --font /path/to/Inter.ttf\n"
                     "Inter (OFL) can be downloaded from:\n  " + INTER_URL)
        print("  font: " + font)
        save(render_feature(font), os.path.join(a.images_dir, "featureGraphic.png"))

    if a.icon_size:
        out = a.icon_out or os.path.join(REPO, "docs", "icon-%d.png" % a.icon_size)
        img = (render_mark(a.icon_size) if a.mark
               else render_icon(a.icon_size, square=a.square))
        save(img, out)


if __name__ == "__main__":
    main()
