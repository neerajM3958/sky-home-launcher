"""Draws the app icon and the TV banner (Pillow). Usage: python3 tools/make_art.py"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/InterVariable.ttf")
STOPS = [(0.0, (123, 97, 255)), (0.5, (74, 58, 166)), (1.0, (23, 20, 56))]   # twilight: dusk violet -> near-black


def gradient(w, h, angle=35):
    """Diagonal three-color gradient."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    a = math.radians(angle)
    dx, dy = math.cos(a), math.sin(a)
    lo = min(0, w * dx, h * dy, w * dx + h * dy)
    hi = max(0, w * dx, h * dy, w * dx + h * dy)
    for y in range(h):
        for x in range(w):
            t = ((x * dx + y * dy) - lo) / (hi - lo)
            for i in range(len(STOPS) - 1):
                t0, c0 = STOPS[i]
                t1, c1 = STOPS[i + 1]
                if t <= t1:
                    f = (t - t0) / (t1 - t0)
                    px[x, y] = tuple(round(c0[k] + (c1[k] - c0[k]) * f) for k in range(3))
                    break
    return img


def sparkle(draw, cx, cy, r, fill):
    """Four-point star."""
    pts = []
    for i in range(8):
        ang = math.pi / 2 * (i // 2) + (0 if i % 2 == 0 else math.pi / 4)
        rad = r if i % 2 == 0 else r * 0.26
        pts.append((cx + rad * math.cos(ang - math.pi / 2), cy + rad * math.sin(ang - math.pi / 2)))
    draw.polygon(pts, fill=fill)


def mark(size, bg=True):
    """The mark: a house with a sparkle, on the gradient squircle (bg) or alone (white)."""
    s = size * 4
    img = gradient(s, s).convert("RGBA") if bg else Image.new("RGBA", (s, s), (0, 0, 0, 0))
    if bg:
        mask = Image.new("L", (s, s), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, s - 1, s - 1), radius=int(s * 0.23), fill=255)
        img.putalpha(mask)
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    white = (255, 255, 255, 255)
    cx, cy = s * 0.5, s * 0.5
    # roof
    roof_w, roof_h = s * 0.46, s * 0.26
    apex = (cx, cy - s * 0.17)
    d.polygon([apex, (cx - roof_w / 2, cy + roof_h / 2 - s * 0.17), (cx + roof_w / 2, cy + roof_h / 2 - s * 0.17)],
              fill=white)
    # base
    base_w, base_h = s * 0.34, s * 0.22
    base_y = cy + roof_h / 2 - s * 0.17
    d.rounded_rectangle((cx - base_w / 2, base_y, cx + base_w / 2, base_y + base_h), radius=int(s * 0.02), fill=white)
    # sparkle above the roof
    sparkle(d, cx + s * 0.16, cy - s * 0.27, s * 0.05, white)
    if bg:
        shadow = layer.copy().filter(ImageFilter.GaussianBlur(s * 0.02))
        tint = Image.new("RGBA", (s, s), (120, 40, 80, 0))
        tint.putalpha(shadow.getchannel("A").point(lambda v: int(v * 0.22)))
        img.alpha_composite(tint, (0, int(s * 0.012)))
    img.alpha_composite(layer)
    return img.resize((size, size), Image.LANCZOS)


def font(px, weight=600):
    f = ImageFont.truetype(FONT, px)
    try:
        axes = f.get_variation_axes()
        values = []
        for a in axes:
            name = a["name"] if isinstance(a["name"], str) else a["name"].decode()
            values.append(weight if "eight" in name else 32 if "ptical" in name else a.get("default", 0))
        f.set_variation_by_axes(values)
    except Exception:
        pass
    return f


def banner(w=640, h=360):
    s = 4
    img = gradient(w * s, h * s, angle=20).convert("RGBA")
    m = mark(int(h * 0.5 * s), bg=False)
    d = ImageDraw.Draw(img)
    text = "Sky Home Launcher"
    gap = int(w * 0.02 * s)
    margin = w * s * 0.92
    px = int(h * 0.15 * s)
    while True:
        f = font(px, 650)
        box = d.textbbox((0, 0), text, font=f)
        total = m.width + gap + (box[2] - box[0])
        if total <= margin or px <= int(h * 0.06 * s):
            break
        px -= 2
    x = (w * s - total) // 2
    img.alpha_composite(m, (x, int((h * s - m.height) / 2)))
    y = (h * s - (box[3] - box[1])) // 2 - box[1]
    d.text((x + m.width + gap - box[0], y), text, font=f, fill=(255, 255, 255, 255))
    return img.resize((w, h), Image.LANCZOS)


if __name__ == "__main__":
    res = os.path.join(ROOT, "app/res/drawable-nodpi")
    os.makedirs(res, exist_ok=True)
    mark(192).save(os.path.join(res, "icon.png"))
    banner().save(os.path.join(res, "banner.png"))
    print("ok")
