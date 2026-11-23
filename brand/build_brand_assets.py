#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSH Pocket — brand asset generator.

Single source of truth for the identity geometry. Emits SVG sources and PNG
exports from the same primitives, so raster and vector never drift.

Run:
    python brand/build_brand_assets.py
Requires: Pillow (bundled with the DSH runtime: dsh-runtimes/*/dependencies/python).
"""
from __future__ import annotations

import math
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
LOGO = os.path.join(HERE, "logo")
ICONS = os.path.join(HERE, "icons")
ANDROID = os.path.join(ICONS, "android")
PREVIEW = os.path.join(HERE, "preview")

# ---------------------------------------------------------------- palette ---
INK = "#0B1220"          # primary / badge background
INK_2 = "#16233A"        # secondary surface
ACCENT = "#38BDF8"       # signal accent (the core)
PAPER = "#FFFFFF"
MIST = "#F4F6FA"
MUTED = "#64748B"        # secondary wordmark word
MUTED_DARK = "#7C8BA1"

# ---------------------------------------------------------------- symbol ----
# 64-unit design grid. The mark is deliberately asymmetric: a heavy open ring
# ("C") whose body sweeps to the lower right, opening toward the upper left,
# with the engine chip nested in that opening. Ring = the sealed runtime,
# chip = the engine, gap = the seam between device and runtime.
SYM = 64.0
RING_CX = 29.3          # ring centre, offset down-right of the tile centre
RING_CY = 32.9
RING_R = 19.5           # centreline radius
RING_W = 4.6            # stroke width
RING_GAP = (140.0, 250.0)  # gap spans 140deg..250deg (upper left)
CORE_S = 12.0           # core side
CORE_R = 4.4            # core corner radius
CORE_CX = 20.3          # core centre, nested in the ring opening
CORE_CY = 23.9

# ------------------------------------------------------------- wordmark -----
# Monoline geometric capitals, cap height 100, stroke 11, round caps.
CAP = 100.0
WSTROKE = 11.0
TRACK = 20.0
WORDSPACE = 30.0


def glyph_D():
    return [("arc", 0.0, 50.0, 50.0, -90.0, 90.0), ("line", 0.0, 0.0, 0.0, 100.0)]


def glyph_S():
    # Two 270deg bowls meeting tangentially at y=50. The arcs overlap by a few
    # degrees so raster joins stay seamless (vector joins are tangent anyway).
    return [("arc", 27.0, 23.0, 27.0, 86.0, 360.0), ("arc", 27.0, 77.0, 27.0, 266.0, 540.0)]


def glyph_H():
    return [("line", 0.0, 0.0, 0.0, 100.0), ("line", 54.0, 0.0, 54.0, 100.0),
            ("line", 0.0, 50.0, 54.0, 50.0)]


def glyph_P():
    return [("line", 0.0, 0.0, 0.0, 100.0), ("arc", 0.0, 25.0, 25.0, -90.0, 90.0)]


def glyph_O():
    return [("circle", 50.0, 50.0, 50.0)]


def glyph_C():
    # clockwise from 50deg through bottom/left/top to 310deg: the gap faces right
    return [("arc", 50.0, 50.0, 50.0, 50.0, 310.0)]


def glyph_K():
    return [("line", 0.0, 0.0, 0.0, 100.0), ("line", 50.0, 0.0, 0.0, 50.0),
            ("line", 0.0,  50.0, 50.0, 100.0)]


def glyph_E():
    return [("line", 0.0, 0.0, 0.0, 100.0), ("line", 0.0, 0.0, 46.0, 0.0),
            ("line", 0.0, 50.0, 38.0, 50.0), ("line", 0.0, 100.0, 46.0, 100.0)]


def glyph_T():
    return [("line", 0.0, 0.0, 52.0, 0.0), ("line", 26.0, 0.0, 26.0, 100.0)]


GLYPHS = {
    "D": (glyph_D, 50.0), "S": (glyph_S, 54.0), "H": (glyph_H, 54.0),
    "P": (glyph_P, 50.0), "O": (glyph_O, 100.0), "C": (glyph_C, 100.0),
    "K": (glyph_K, 50.0), "E": (glyph_E, 46.0), "T": (glyph_T, 52.0),
}


def wordmark_layout():
    """-> (items, total_width); item = (prims, x_offset, color_role)."""
    items = []
    x = 0.0
    for i, ch in enumerate("DSH"):
        fn, adv = GLYPHS[ch]
        items.append((fn(), x, "primary"))
        x += adv + TRACK
    x += WORDSPACE - TRACK
    for ch in "POCKET":
        fn, adv = GLYPHS[ch]
        items.append((fn(), x, "secondary"))
        x += adv + TRACK
    return items, x - TRACK


def wordmark_width():
    return wordmark_layout()[1]


# ------------------------------------------------------------- primitives ---
def pol(r, deg):
    a = math.radians(deg)
    return (r * math.cos(a), r * math.sin(a))


def prim_points(prim):
    """Endpoint list of a primitive (for round caps in raster)."""
    kind = prim[0]
    if kind == "line":
        return [(prim[1], prim[2]), (prim[3], prim[4])]
    if kind == "arc":
        _, cx, cy, r, a0, a1 = prim
        p0 = (cx + pol(r, a0)[0], cy + pol(r, a0)[1])
        p1 = (cx + pol(r, a1)[0], cy + pol(r, a1)[1])
        return [p0, p1]
    if kind == "circle":
        return []
    return []


def prim_to_path(prim, ox=0.0, oy=0.0, prec=2):
    """SVG path data for one primitive."""
    kind = prim[0]
    f = "%." + str(prec) + "f"
    if kind == "line":
        _, x1, y1, x2, y2 = prim
        return ("M" + f + " " + f + " L" + f + " " + f) % (x1 + ox, y1 + oy, x2 + ox, y2 + oy)
    if kind == "arc":
        _, cx, cy, r, a0, a1 = prim
        x0, y0 = cx + pol(r, a0)[0], cy + pol(r, a0)[1]
        x1, y1 = cx + pol(r, a1)[0], cy + pol(r, a1)[1]
        sweep = (a1 - a0) % 360.0
        large = 1 if sweep > 180.0 else 0
        return ("M" + f + " " + f + " A" + f + " " + f + " 0 %d 1 " + f + " " + f) % (
            x0 + ox, y0 + oy, r, r, large, x1 + ox, y1 + oy)
    if kind == "circle":
        _, cx, cy, r = prim
        return (("M" + f + " " + f + " A" + f + " " + f + " 0 1 1 " + f + " " + f +
                " A" + f + " " + f + " 0 1 1 " + f + " " + f)) % (
            cx - r + ox, cy + oy, r, r, cx + r + ox, cy + oy, r, r, cx - r + ox, cy + oy)
    raise ValueError(kind)


def prim_points(prim, step=1.5):
    """Polyline approximation of a primitive (design units)."""
    kind = prim[0]
    if kind == "line":
        _, x1, y1, x2, y2 = prim
        return [(x1, y1), (x2, y2)]
    if kind == "arc":
        _, cx, cy, r, a0, a1 = prim
        n = max(2, int(abs(a1 - a0) / step) + 1)
        out = []
        for i in range(n):
            a = a0 + (a1 - a0) * i / (n - 1)
            dx, dy = pol(r, a)
            out.append((cx + dx, cy + dy))
        return out
    if kind == "circle":
        _, cx, cy, r = prim
        n = max(8, int(360.0 / step))
        out = []
        for i in range(n + 1):
            dx, dy = pol(r, 360.0 * i / n)
            out.append((cx + dx, cy + dy))
        return out
    return []


def stroke_polygon(prim, width):
    """Exact outline of a stroked primitive (butt caps), in design units."""
    w = width / 2.0
    kind = prim[0]
    if kind == "line":
        _, x1, y1, x2, y2 = prim
        dx, dy = x2 - x1, y2 - y1
        ln = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / ln * w, dx / ln * w
        return [(x1 + nx, y1 + ny), (x2 + nx, y2 + ny), (x2 - nx, y2 - ny), (x1 - nx, y1 - ny)]
    if kind == "arc":
        _, cx, cy, r, a0, a1 = prim
        step = 1.0
        n = max(2, int(abs(a1 - a0) / step) + 1)
        outer = []
        inner = []
        for i in range(n):
            t = a0 + (a1 - a0) * i / (n - 1)
            dx, dy = pol(r + w, t)
            outer.append((cx + dx, cy + dy))
        for i in range(n):
            t = a1 + (a0 - a1) * i / (n - 1)
            dx, dy = pol(r - w, t)
            inner.append((cx + dx, cy + dy))
        return outer + inner
    if kind == "circle":
        _, cx, cy, r = prim
        step = 1.0
        n = max(24, int(360.0 / step))
        outer = [(cx + pol(r + w, 360.0 * i / n)[0], cy + pol(r + w, 360.0 * i / n)[1])
                 for i in range(n + 1)]
        inner = [(cx + pol(r - w, 360.0 * i / n)[0], cy + pol(r - w, 360.0 * i / n)[1])
                 for i in range(n, -1, -1)]
        return outer + inner
    raise ValueError(kind)


def draw_prims(d, prims, ox, oy, scale, color, width, caps="round"):
    """Pillow rendering of primitives.

    ox/oy are PIXEL offsets, scale is pixels per design unit.
    Strokes are filled as analytic outlines (outer + inner offset), which keeps
    terminals exactly flat like the SVG and avoids Pillow's arc artefacts.
    """
    w = max(1, int(round(width * scale)))
    r = w / 2.0

    for prim in prims:
        poly = stroke_polygon(prim, width)
        xy = [(ox + x * scale, oy + y * scale) for (x, y) in poly]
        d.polygon(xy, fill=color)
        if caps == "round":
            for (x, y) in (prim_points(prim)[0], prim_points(prim)[-1]):
                cx, cy = ox + x * scale, oy + y * scale
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)


# -------------------------------------------------------------- symbol ------
def symbol_prims():
    """The open ring: an arc that sweeps the long way round, leaving a gap
    across the upper left where the engine chip sits."""
    a0, a1 = RING_GAP[1], RING_GAP[0] + 360.0
    return [("arc", RING_CX, RING_CY, RING_R, a0, a1)]


def core_path(cx=None, cy=None, side=None, r=None, ox=0.0, oy=0.0, prec=2):
    """Rounded-square core as an SVG path."""
    if cx is None:
        cx, cy = CORE_CX, CORE_CY
    if side is None:
        side = CORE_S
    if r is None:
        r = CORE_R
    x0 = cx - side / 2
    y0 = cy - side / 2
    x1 = cx + side / 2
    y1 = cy + side / 2

    def fmt(v):
        return ("%." + str(prec) + "f") % v
    return (f"M{fmt(x0 + r + ox)} {fmt(y0 + oy)} L{fmt(x1 - r + ox)} {fmt(y0 + oy)} "
            f"A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x1 + ox)} {fmt(y0 + r + oy)} "
            f"L{fmt(x1 + ox)} {fmt(y1 - r + oy)} "
            f"A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x1 - r + ox)} {fmt(y1 + oy)} "
            f"L{fmt(x0 + r + ox)} {fmt(y1 + oy)} "
            f"A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x0 + ox)} {fmt(y1 - r + oy)} "
            f"L{fmt(x0 + ox)} {fmt(y0 + r + oy)} "
            f"A{fmt(r)} {fmt(r)} 0 0 1 {fmt(x0 + r + ox)} {fmt(y0 + oy)} Z")


# ------------------------------------------------------------- svg files ----
def svg_header(w, h, title=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" '
            f'width="{w}" height="{h}" role="img" aria-label="{title}">\n')


def write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    print("  ", os.path.relpath(path, HERE))


def build_symbol_svg(ring, core, out, mono=False):
    s = svg_header(SYM, SYM, "DSH Pocket")
    if mono:
        s += (f'  <path d="{prim_to_path(symbol_prims()[0])}" fill="none" '
              f'stroke="{ring}" stroke-width="{RING_W}" stroke-linecap="round"/>\n')
        s += f'  <path d="{core_path()}" fill="{ring}"/>\n'
    else:
        s += (f'  <path d="{prim_to_path(symbol_prims()[0])}" fill="none" '
              f'stroke="{ring}" stroke-width="{RING_W}" stroke-linecap="round"/>\n')
        s += f'  <path d="{core_path()}" fill="{core}"/>\n'
    s += "</svg>\n"
    write(out, s)


def build_icon_svg(out, size=512, radius_ratio=0.225, ring=PAPER, core=ACCENT, bg=INK):
    n = size
    r = n * radius_ratio
    s = svg_header(n, n, "DSH Pocket")
    s += f'  <rect x="0" y="0" width="{n}" height="{n}" rx="{r:.2f}" fill="{bg}"/>\n'
    k = n / SYM
    s += (f'  <g transform="scale({k:.6f})">\n')
    s += (f'    <path d="{prim_to_path(symbol_prims()[0])}" fill="none" '
          f'stroke="{ring}" stroke-width="{RING_W}" stroke-linecap="round"/>\n')
    s += f'    <path d="{core_path()}" fill="{core}"/>\n'
    s += "  </g>\n</svg>\n"
    write(out, s)


def build_wordmark_svg(primary, secondary, out):
    items, total = wordmark_layout()
    h = CAP + WSTROKE
    s = svg_header(total, h, "DSH Pocket")
    s += (f'  <g fill="none" stroke-width="{WSTROKE}" stroke-linecap="butt" '
          f'stroke-linejoin="round">\n')
    for prims, x, role in items:
        color = primary if role == "primary" else secondary
        s += f'    <path d="{" ".join(prim_to_path(p, ox=x) for p in prims)}" stroke="{color}"/>\n'
    s += "  </g>\n</svg>\n"
    write(out, s)


def build_lockup_svg(out, primary, secondary, ring, core, stacked=False):
    items, total = wordmark_layout()
    wm_h = CAP + WSTROKE
    if stacked:
        sym = 96.0
        gap = 26.0
        w = max(sym, total * sym / wm_h)
        h = sym + gap + sym * 0.52
        s = svg_header(round(w, 2), round(h, 2), "DSH Pocket")
        k = sym / SYM
        s += f'  <g transform="translate({(w - sym) / 2:.2f},0) scale({k:.6f})">\n'
        s += (f'    <path d="{prim_to_path(symbol_prims()[0])}" fill="none" '
              f'stroke="{ring}" stroke-width="{RING_W}" stroke-linecap="round"/>\n')
        s += f'    <path d="{core_path()}" fill="{core}"/>\n'
        s += "  </g>\n"
        wm_scale = sym * 0.52 / wm_h
        s += (f'  <g transform="translate({(w - total * wm_scale) / 2:.2f},'
              f'{sym + gap:.2f}) scale({wm_scale:.6f})" fill="none" '
              f'stroke-width="{WSTROKE}" stroke-linecap="butt" stroke-linejoin="round">\n')
        for prims, x, role in items:
            color = primary if role == "primary" else secondary
            s += f'    <path d="{" ".join(prim_to_path(p, ox=x) for p in prims)}" stroke="{color}"/>\n'
        s += "  </g>\n</svg>\n"
    else:
        sym = 72.0
        gap = 26.0
        wm_scale = sym * 0.42 / wm_h
        w = sym + gap + total * wm_scale
        h = sym
        s = svg_header(round(w, 2), round(h, 2), "DSH Pocket")
        k = sym / SYM
        s += f'  <g transform="translate(0,{(h - sym) / 2:.2f}) scale({k:.6f})">\n'
        s += (f'    <path d="{prim_to_path(symbol_prims()[0])}" fill="none" '
              f'stroke="{ring}" stroke-width="{RING_W}" stroke-linecap="round"/>\n')
        s += f'    <path d="{core_path()}" fill="{core}"/>\n'
        s += "  </g>\n"
        s += (f'  <g transform="translate({sym + gap:.2f},{(h - wm_h * wm_scale) / 2:.2f}) '
              f'scale({wm_scale:.6f})" fill="none" stroke-width="{WSTROKE}" '
              f'stroke-linecap="butt" stroke-linejoin="round">\n')
        for prims, x, role in items:
            color = primary if role == "primary" else secondary
            s += f'    <path d="{" ".join(prim_to_path(p, ox=x) for p in prims)}" stroke="{color}"/>\n'
        s += "  </g>\n</svg>\n"
    write(out, s)


# ---------------------------------------------------------------- raster ----
def hex_rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def render_icon(size, radius_ratio=0.225, ring=PAPER, core=ACCENT, bg=INK, ss=4):
    n = size * ss
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    if bg is not None:
        mask = Image.new("L", (n, n), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, n - 1, n - 1],
                                               radius=int(n * radius_ratio), fill=255)
        img.paste(hex_rgb(bg), (0, 0), mask)
    d = ImageDraw.Draw(img)
    k = n / SYM
    draw_prims(d, symbol_prims(), 0, 0, k, hex_rgb(ring), RING_W)
    # core
    cx, cy = CORE_CX, CORE_CY
    x0, y0 = (cx - CORE_S / 2) * k, (cy - CORE_S / 2) * k
    x1, y1 = (cx + CORE_S / 2) * k, (cy + CORE_S / 2) * k
    d.rounded_rectangle([x0, y0, x1, y1], radius=CORE_R * k, fill=hex_rgb(core))
    return img.resize((size, size), Image.LANCZOS)


def render_circle_icon(size, ring=PAPER, core=ACCENT, bg=INK, ss=4):
    n = size * ss
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, n - 1, n - 1], fill=255)
    img.paste(hex_rgb(bg), (0, 0), mask)
    d = ImageDraw.Draw(img)
    k = n / SYM
    draw_prims(d, symbol_prims(), 0, 0, k, hex_rgb(ring), RING_W)
    cx, cy = CORE_CX, CORE_CY
    x0, y0 = (cx - CORE_S / 2) * k, (cy - CORE_S / 2) * k
    x1, y1 = (cx + CORE_S / 2) * k, (cy + CORE_S / 2) * k
    d.rounded_rectangle([x0, y0, x1, y1], radius=CORE_R * k, fill=hex_rgb(core))
    return img.resize((size, size), Image.LANCZOS)


def render_wordmark(primary, secondary, scale=2.0, ss=2, pad=8):
    items, total = wordmark_layout()
    h = CAP + WSTROKE
    s = scale * ss
    W = int(total * s) + pad * 2 * ss
    H = int(h * s) + pad * 2 * ss
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for prims, x, role in items:
        color = hex_rgb(primary if role == "primary" else secondary)
        draw_prims(d, prims, (x + pad) * s, pad * s, s, color, WSTROKE, caps="butt")
    return img.resize((W // ss, H // ss), Image.LANCZOS)


def render_lockup(stacked=False, primary=INK, secondary=MUTED, ring=PAPER, core=ACCENT,
                  bg=None, scale=1.0, ss=2):
    items, total = wordmark_layout()
    wm_h = CAP + WSTROKE
    if stacked:
        sym, gap = 96.0, 26.0
        w = max(sym, total * sym / wm_h)
        h = sym + gap + sym * 0.52
        wm_scale = sym * 0.52 / wm_h
        tx = (w - total * wm_scale) / 2
        ty = sym + gap
    else:
        sym, gap = 72.0, 26.0
        wm_scale = sym * 0.42 / wm_h
        w = sym + gap + total * wm_scale
        h = sym
        tx = sym + gap
        ty = (h - wm_h * wm_scale) / 2
    s = scale * ss
    W, H = int(w * s), int(h * s)
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0) if bg is None else hex_rgb(bg))
    d = ImageDraw.Draw(img)
    k = sym * s / SYM
    draw_prims(d, symbol_prims(), 0, 0, k, hex_rgb(ring), RING_W)
    cx, cy = CORE_CX, CORE_CY
    d.rounded_rectangle([(cx - CORE_S / 2) * k, (cy - CORE_S / 2) * k,
                         (cx + CORE_S / 2) * k, (cy + CORE_S / 2) * k],
                        radius=CORE_R * k, fill=hex_rgb(core))
    for prims, x, role in items:
        color = hex_rgb(primary if role == "primary" else secondary)
        draw_prims(d, prims, (tx + x * wm_scale) * s, ty * s, wm_scale * s, color, WSTROKE, caps="butt")
    return img.resize((int(w * scale), int(h * scale)), Image.LANCZOS)


def _first_existing(paths):
    for p in paths:
        if os.path.exists(p):
            return p
    return None


def font(size, bold=True):
    """Latin font. Probe the common per-OS locations, then fall back to the
    bitmap default so the build never hard-fails on macOS/Linux."""
    if bold:
        names = ["arialbd.ttf", "Arial Bold.ttf", "DejaVuSans-Bold.ttf",
                 "LiberationSans-Bold.ttf", "Helvetica.ttc"]
    else:
        names = ["arial.ttf", "Arial.ttf", "DejaVuSans.ttf",
                 "LiberationSans-Regular.ttf", "Helvetica.ttc"]
    roots = ["C:/Windows/Fonts/", "/usr/share/fonts/truetype/dejavu/",
             "/usr/share/fonts/truetype/liberation/", "/usr/share/fonts/",
             "/Library/Fonts/", "/System/Library/Fonts/"]
    for root in roots:
        for name in names:
            path = os.path.join(root, name)
            if os.path.exists(path):
                try:
                    return ImageFont.truetype(path, size)
                except Exception:
                    continue
    return ImageFont.load_default()


def cjk_font(size):
    """CJK-capable font. Probe per-OS locations, then fall back to the Latin
    font (which renders boxes for CJK but never crashes the build)."""
    candidates = [
        ("C:/Windows/Fonts/msyh.ttc", 0),
        ("C:/Windows/Fonts/msyhl.ttc", 0),
        ("C:/Windows/Fonts/NotoSansSC-VF.ttf", 0),
        ("C:/Windows/Fonts/simhei.ttf", 0),
        ("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc", 0),
        ("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc", 0),
        ("/usr/share/fonts/opentype/noto/NotoSansSC-Regular.otf", 0),
        ("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc", 0),
        ("/usr/share/fonts/truetype/arphic/uming.ttc", 0),
        ("/System/Library/Fonts/PingFang.ttc", 0),
        ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0),
        ("/Library/Fonts/Arial Unicode.ttf", 0),
    ]
    for path, idx in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size, index=idx)
            except Exception:
                continue
    return font(size, bold=False)


def brand_sheet():
    W, H = 1600, 1180
    img = Image.new("RGB", (W, H), hex_rgb(MIST))
    d = ImageDraw.Draw(img)
    f_title = font(34)
    f_h = font(22)
    f_p = font(16)
    f_mono = font(13)

    d.text((60, 46), "DSH Pocket", font=f_title, fill=hex_rgb(INK))
    d.text((60, 92), "DeepSeek Harness for Android — brand sheet", font=f_p, fill=hex_rgb(MUTED))

    # symbol on ink + on paper
    ink_tile = render_icon(200, bg=INK)
    img.paste(ink_tile, (60, 150), ink_tile)
    flat = render_icon(200, bg=MIST, ring=INK, core=ACCENT)
    img.paste(flat, (290, 150), flat)
    mono = render_icon(200, bg=MIST, ring=INK, core=INK)
    img.paste(mono, (520, 150), mono)
    d.text((60, 364), "app icon (ink)", font=f_p, fill=hex_rgb(MUTED))
    d.text((290, 364), "flat mark", font=f_p, fill=hex_rgb(MUTED))
    d.text((520, 364), "monochrome", font=f_p, fill=hex_rgb(MUTED))

    # small sizes
    x = 780
    for s in (96, 72, 48, 32):
        t = render_icon(s, bg=INK)
        img.paste(t, (x, 150 + (96 - s)), t)
        d.text((x, 258), f"{s}px", font=f_mono, fill=hex_rgb(MUTED))
        x += s + 28

    # horizontal lockup (light)
    lock = render_lockup(stacked=False, primary=INK, secondary=MUTED, ring=INK, core=ACCENT, scale=1.5)
    img.paste(lock, (60, 410), lock)
    d.text((60, 530), "primary lockup", font=f_p, fill=hex_rgb(MUTED))

    # stacked lockup (light)
    stack = render_lockup(stacked=True, primary=INK, secondary=MUTED, ring=INK, core=ACCENT, scale=0.95)
    img.paste(stack, (700, 400), stack)
    d.text((700, 580), "stacked lockup", font=f_p, fill=hex_rgb(MUTED))

    # inverse lockup on ink band
    d.rectangle([60, 630, 1540, 790], fill=hex_rgb(INK))
    inv = render_lockup(stacked=False, primary=PAPER, secondary=MUTED_DARK, ring=PAPER, core=ACCENT, scale=1.5)
    img.paste(inv, (90, 665), inv)
    d.text((90, 800), "inverse lockup", font=f_p, fill=hex_rgb(MUTED))

    # palette
    d.text((60, 870), "palette", font=f_h, fill=hex_rgb(INK))
    swatches = [("Ink", INK), ("Ink 2", INK_2), ("Accent", ACCENT), ("Mist", MIST), ("Muted", MUTED)]
    sx = 60
    for name, col in swatches:
        d.rounded_rectangle([sx, 906, sx + 130, 970], radius=10, fill=hex_rgb(col),
                            outline=hex_rgb("#D5DBE5"))
        d.text((sx, 980), name, font=f_p, fill=hex_rgb(INK))
        d.text((sx, 1002), col, font=f_mono, fill=hex_rgb(MUTED))
        sx += 150

    # chinese name
    try:
        cn = cjk_font(58)
        # These two lines mix CJK and Latin. A Latin-only font renders the
        # CJK glyphs as tofu boxes, so use the CJK font for both lines.
        cn_small = cjk_font(16)
        d.text((900, 862), "口袋 DSH", font=cn, fill=hex_rgb(INK))
        d.text((900, 936), "中文名 / Chinese name", font=cn_small, fill=hex_rgb(MUTED))
        d.text((900, 962), "正式全称 DeepSeek Harness Pocket", font=cn_small, fill=hex_rgb(MUTED))
    except Exception as exc:  # pragma: no cover
        d.text((900, 880), "CJK font unavailable: %s" % exc, font=f_p, fill=hex_rgb(MUTED))

    img.save(os.path.join(PREVIEW, "brand-sheet.png"))
    print("   preview/brand-sheet.png")


def arc_to_beziers(cx, cy, r, a0, a1, prec=2, ox=0.0, oy=0.0):
    """Circular arc -> cubic beziers (Android VectorDrawable pathData).

    Android's arc-to-bezier conversion mis-renders large sweeps, so the shell
    vector uses explicit curves instead of the SVG 'A' command.
    """
    f = "%." + str(prec) + "f"
    sweep = a1 - a0
    if sweep <= 0:
        sweep += 360.0
    n = max(1, int(math.ceil(sweep / 90.0)))
    step = sweep / n
    k = 4.0 / 3.0 * math.tan(math.radians(step) / 4.0)
    parts = []

    def pt(a, radius):
        dx, dy = pol(radius, a)
        return (cx + dx + ox, cy + dy + oy)

    def num(v):
        return ("%." + str(prec) + "f") % v

    x0, y0 = pt(a0, r)
    parts.append("M " + num(x0) + " " + num(y0))
    a = a0
    for _ in range(n):
        b = a + step
        p0 = pt(a, r)
        p3 = pt(b, r)
        d0 = (-math.sin(math.radians(a)), math.cos(math.radians(a)))
        d1 = (-math.sin(math.radians(b)), math.cos(math.radians(b)))
        p1 = (p0[0] + k * r * d0[0], p0[1] + k * r * d0[1])
        p2 = (p3[0] - k * r * d1[0], p3[1] - k * r * d1[1])
        parts.append("C " + num(p1[0]) + " " + num(p1[1]) + " "
                     + num(p2[0]) + " " + num(p2[1]) + " "
                     + num(p3[0]) + " " + num(p3[1]))
        a = b
    return " ".join(parts)


# ------------------------------------------------------------ android -------
def android_vector(foreground=True, monochrome=False):
    """Adaptive-icon foreground: 108dp viewport, mark inside the 72dp safe zone.

    Geometry is baked into the path coordinates (no <group> transform): the
    group translate/scale order in VectorDrawable is not reliably documented,
    and 'A' arcs with a 320deg sweep render wrong on-device.
    """
    k = 72.0 / SYM
    # The tile is 108dp; the mark lives inside the 72dp safe zone, so the 64-unit
    # design grid maps 1:1 onto that zone with an 18dp margin on each side.
    m = 18.0
    s = 72.0 / SYM
    w = RING_W * s
    ring = symbol_prims()[0]
    ring_cx = m + RING_CX * s
    ring_cy = m + RING_CY * s
    ring_path = arc_to_beziers(ring_cx, ring_cy, RING_R * s, ring[4], ring[5])
    core = core_path(cx=m + CORE_CX * s, cy=m + CORE_CY * s,
                     side=CORE_S * s, r=CORE_R * s)
    body = ""
    if monochrome:
        body += (f'    <path android:pathData="{ring_path}" android:strokeColor="#FFFFFFFF" '
                 f'android:strokeWidth="{w:.3f}" android:strokeLineCap="round"/>\n')
        body += f'    <path android:pathData="{core}" android:fillColor="#FFFFFFFF"/>\n'
    else:
        body += (f'    <path android:pathData="{ring_path}" android:strokeColor="#FFFFFFFF" '
                 f'android:strokeWidth="{w:.3f}" android:strokeLineCap="round"/>\n')
        body += f'    <path android:pathData="{core}" android:fillColor="{ACCENT.upper()}"/>\n'
    return body


def write_android_res():
    res = os.path.join(os.path.dirname(HERE), "android-shell", "app", "src", "main", "res")
    mip_v26 = os.path.join(res, "mipmap-anydpi-v26")
    mip_v33 = os.path.join(res, "mipmap-anydpi-v33")
    draw = os.path.join(res, "drawable")
    vals = os.path.join(res, "values")
    for p in (mip_v26, mip_v33, draw, vals):
        os.makedirs(p, exist_ok=True)

    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background"/>\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n')
    adaptive_v33 = adaptive.replace(
        '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n',
        '    <foreground android:drawable="@drawable/ic_launcher_foreground"/>\n'
        '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n')
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(mip_v26, name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(adaptive + "</adaptive-icon>\n")
        with open(os.path.join(mip_v33, name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(adaptive_v33 + "</adaptive-icon>\n")

    fg = ('<?xml version="1.0" encoding="utf-8"?>\n'
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    android:width="108dp" android:height="108dp"\n'
          '    android:viewportWidth="108" android:viewportHeight="108">\n'
          + android_vector() + '</vector>\n')
    with open(os.path.join(draw, "ic_launcher_foreground.xml"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(fg)

    mono = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108"\n'
            '    android:tint="#FFFFFFFF">\n'
            + android_vector(monochrome=True) + '</vector>\n')
    with open(os.path.join(draw, "ic_launcher_monochrome.xml"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(mono)

    bg = ('<?xml version="1.0" encoding="utf-8"?>\n'
          '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    android:width="108dp" android:height="108dp"\n'
          '    android:viewportWidth="108" android:viewportHeight="108">\n'
          '    <path android:pathData="M0,0h108v108h-108z" android:fillColor="' + INK.upper() + '"/>\n'
          '</vector>\n')
    with open(os.path.join(draw, "ic_launcher_background.xml"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(bg)
    print("   android-shell/app/src/main/res (adaptive icon, v26 + v33, monochrome)")


# ------------------------------------------------------------------ main ----
def main():
    for d in (LOGO, ICONS, ANDROID, PREVIEW):
        os.makedirs(d, exist_ok=True)

    print("svg:")
    build_symbol_svg(INK, ACCENT, os.path.join(LOGO, "dsh-pocket-symbol.svg"))
    build_symbol_svg(PAPER, ACCENT, os.path.join(LOGO, "dsh-pocket-symbol-inverse.svg"))
    build_symbol_svg("#000000", "#000000", os.path.join(LOGO, "dsh-pocket-symbol-monochrome.svg"), mono=True)
    build_icon_svg(os.path.join(LOGO, "dsh-pocket-icon.svg"))
    build_wordmark_svg(INK, MUTED, os.path.join(LOGO, "dsh-pocket-wordmark.svg"))
    build_wordmark_svg(PAPER, MUTED_DARK, os.path.join(LOGO, "dsh-pocket-wordmark-inverse.svg"))
    build_lockup_svg(os.path.join(LOGO, "dsh-pocket-lockup-horizontal.svg"), INK, MUTED, INK, ACCENT)
    build_lockup_svg(os.path.join(LOGO, "dsh-pocket-lockup-stacked.svg"), INK, MUTED, INK, ACCENT, stacked=True)

    print("png:")
    for size in (16, 32, 48, 72, 96, 144, 180, 192, 512):
        render_icon(size, bg=INK).save(os.path.join(ICONS, "icon-%d.png" % size))
    render_icon(512, bg=INK, radius_ratio=0.0).save(os.path.join(ICONS, "maskable-512.png"))
    render_circle_icon(512).save(os.path.join(ICONS, "circle-512.png"))
    render_lockup(stacked=False, primary=INK, secondary=MUTED, ring=INK, core=ACCENT, scale=2).save(
        os.path.join(ICONS, "lockup-horizontal.png"))
    render_wordmark(INK, MUTED, scale=2).save(os.path.join(ICONS, "wordmark.png"))

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dens, size in densities.items():
        p = os.path.join(ANDROID, dens)
        os.makedirs(p, exist_ok=True)
        render_icon(size, bg=INK).save(os.path.join(p, "ic_launcher.png"))
        render_circle_icon(size).save(os.path.join(p, "ic_launcher_round.png"))
    print("   icons/android/mipmap-* (legacy fallbacks)")

    print("android:")
    write_android_res()

    print("preview:")
    brand_sheet()
    print("done")


if __name__ == "__main__":
    main()
