"""Draws the five wallpapers bundled with the launcher (launcher/assets/wallpapers).

    pip install pillow
    python wallpapers/draw-bundled.py <output folder>

Everything is drawn from code: gradients, layered ridges, a few lights. The words after the
first dot in each file name tell the launcher when to show the picture (see Backdrop.java).
"""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter
W, H = 1280, 720
out = sys.argv[1]

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def sky(stops):
    im = Image.new("RGB", (W, H)); d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / (H - 1)
        for i in range(len(stops) - 1):
            (t0, c0), (t1, c1) = stops[i], stops[i + 1]
            if t0 <= t <= t1:
                d.line([(0, y), (W, y)], fill=lerp(c0, c1, (t - t0) / (t1 - t0))); break
    return im

def glow(im, cx, cy, r, color, strength=1.0):
    layer = Image.new("RGB", (W, H), (0, 0, 0)); d = ImageDraw.Draw(layer)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=tuple(int(c * strength) for c in color))
    layer = layer.filter(ImageFilter.GaussianBlur(r * 0.9))
    from PIL import ImageChops
    return ImageChops.screen(im, layer)

def disc(im, cx, cy, r, color):
    big = Image.new("RGBA", (W * 2, H * 2), (0, 0, 0, 0)); d = ImageDraw.Draw(big)
    d.ellipse([(cx - r) * 2, (cy - r) * 2, (cx + r) * 2, (cy + r) * 2], fill=color + (255,))
    im.paste(big.resize((W, H), Image.LANCZOS), (0, 0), big.resize((W, H), Image.LANCZOS)); return im

def stars(im, n, rnd, maxy):
    d = ImageDraw.Draw(im)
    for _ in range(n):
        x, y = rnd.randrange(W), rnd.randrange(int(maxy)); b = rnd.randrange(120, 255)
        d.point((x, y), fill=(b, b, b))
        if rnd.random() < 0.12: d.ellipse([x - 1, y - 1, x + 1, y + 1], fill=(b, b, b))
    return im

def ridge(im, base, amp, color, rnd, rough=1.0, blur=0):
    ph = [rnd.random() * 6.28 for _ in range(6)]
    pts = []
    for x in range(0, W + 8, 8):
        u = x / W
        y = base + amp * (0.55 * math.sin(u * 3.1 + ph[0]) + 0.28 * math.sin(u * 7.3 + ph[1])
                          + rough * 0.12 * math.sin(u * 17 + ph[2]) + rough * 0.06 * math.sin(u * 41 + ph[3]))
        pts.append((x, y))
    layer = Image.new("RGBA", (W * 2, H * 2), (0, 0, 0, 0)); d = ImageDraw.Draw(layer)
    d.polygon([(x * 2, y * 2) for x, y in pts] + [(W * 2, H * 2), (0, H * 2)], fill=color + (255,))
    layer = layer.resize((W, H), Image.LANCZOS)
    if blur: layer = layer.filter(ImageFilter.GaussianBlur(blur))
    im.paste(layer, (0, 0), layer); return im

def lights(im, rnd, y0, y1, n, color):
    layer = Image.new("RGB", (W, H), (0, 0, 0)); d = ImageDraw.Draw(layer)
    for _ in range(n):
        x, y = rnd.randrange(W), rnd.randrange(y0, y1); r = rnd.choice([1, 1, 2, 2, 3])
        d.ellipse([x - r, y - r, x + r, y + r], fill=color)
    from PIL import ImageChops
    soft = layer.filter(ImageFilter.GaussianBlur(5))
    return ImageChops.screen(ImageChops.screen(im, soft), layer)

def save(im, name): im.save(f"{out}/{name}.jpg", quality=86)

# 1. Dusk ridges: the ambient wallpaper
r = random.Random(7)
im = sky([(0, (13, 14, 38)), (0.45, (58, 32, 92)), (0.68, (176, 74, 104)), (0.8, (242, 142, 92)), (1, (250, 190, 120))])
im = stars(im, 260, r, H * 0.42); im = glow(im, 820, 560, 210, (255, 170, 110), 0.8)
for base, amp, col, bl in [(500, 60, (120, 62, 108), 2), (548, 70, (78, 40, 88), 1), (600, 60, (44, 24, 62), 0), (660, 46, (20, 12, 34), 0)]:
    im = ridge(im, base, amp, col, r, blur=bl)
save(im, "ridges.dusk")

# 2. Harbor at night: teal, warm lights
r = random.Random(11)
im = sky([(0, (4, 14, 26)), (0.55, (10, 52, 70)), (0.75, (26, 104, 112)), (1, (8, 34, 48))])
im = stars(im, 160, r, H * 0.4); im = disc(im, 980, 170, 46, (236, 240, 226)); im = glow(im, 980, 170, 120, (120, 190, 200), 0.6)
im = ridge(im, 500, 40, (8, 40, 54), r, blur=1); im = ridge(im, 540, 26, (5, 26, 38), r)
im = lights(im, r, 520, 566, 70, (255, 196, 120))
im = ridge(im, 640, 18, (3, 14, 22), r, rough=0.3)
save(im, "harbor.night")

# 3. Dunes: amber, large low sun
r = random.Random(3)
im = sky([(0, (86, 44, 30)), (0.5, (204, 110, 52)), (0.72, (246, 176, 92)), (1, (250, 214, 150))])
im = glow(im, 420, 430, 240, (255, 210, 140), 0.9); im = disc(im, 420, 430, 96, (255, 236, 200))
for base, amp, col in [(520, 50, (176, 92, 48)), (575, 60, (132, 62, 34)), (640, 50, (88, 38, 24)), (690, 30, (46, 20, 14))]:
    im = ridge(im, base, amp, col, r, rough=0.2)
save(im, "dunes.dawn.day")

# 4. Aurora: green bands over dark ridges
r = random.Random(19)
im = sky([(0, (3, 8, 20)), (0.6, (6, 26, 40)), (1, (10, 40, 44))])
im = stars(im, 320, r, H * 0.6)
from PIL import ImageChops
band = Image.new("RGB", (W, H), (0, 0, 0)); d = ImageDraw.Draw(band)
for k, (yb, col) in enumerate([(210, (40, 230, 150)), (280, (60, 200, 210)), (170, (130, 90, 220))]):
    pts = [(x, yb + 60 * math.sin(x / 190.0 + k * 1.7) + 26 * math.sin(x / 61.0 + k)) for x in range(0, W + 10, 10)]
    d.line(pts, fill=col, width=46)
band = band.filter(ImageFilter.GaussianBlur(34)).resize((W, H * 2)).crop((0, 0, W, H))
im = ImageChops.screen(im, band)
for base, amp, col in [(560, 60, (8, 30, 36)), (620, 50, (5, 18, 24)), (680, 30, (2, 8, 12))]:
    im = ridge(im, base, amp, col, r)
save(im, "aurora.night")

# 5. Red canyon: crimson, twin moons
r = random.Random(23)
im = sky([(0, (30, 6, 14)), (0.5, (120, 24, 34)), (0.78, (214, 70, 52)), (1, (240, 130, 84))])
im = stars(im, 120, r, H * 0.3); im = disc(im, 300, 190, 60, (250, 214, 196)); im = disc(im, 420, 130, 22, (236, 170, 150))
for base, amp, col in [(470, 90, (128, 30, 34)), (540, 90, (86, 18, 26)), (630, 70, (48, 10, 18)), (690, 30, (22, 4, 10))]:
    im = ridge(im, base, amp, col, r, rough=1.6)
save(im, "canyon.dusk")
