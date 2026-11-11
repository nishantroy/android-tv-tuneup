"""Draws the optional San Francisco wallpapers (wallpapers/san-francisco).

    pip install pillow
    python wallpapers/draw-san-francisco.py <output folder> [golden-gate coit-tower ...]

Everything is drawn from code, at twice the final size and then scaled down. The subjects are
kept to the upper middle of each picture, clear of where the launcher puts its clock and rows.
"""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageChops
W, H, SS = 1280, 720, 2
out = sys.argv[1]
only = sys.argv[2:] 

def s(v): return int(round(v * SS))
def pts(p): return [(s(x), s(y)) for x, y in p]
def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def sky(stops):
    col = Image.new("RGB", (1, H * SS)); px = col.load()
    for y in range(H * SS):
        t = y / (H * SS - 1)
        for i in range(len(stops) - 1):
            (t0, c0), (t1, c1) = stops[i], stops[i + 1]
            if t0 <= t <= t1: px[0, y] = lerp(c0, c1, (t - t0) / (t1 - t0)); break
    return col.resize((W * SS, H * SS)).convert("RGBA")

class Lay:
    """A transparent layer to draw on, then lay over the picture, optionally blurred."""
    def __init__(self): self.im = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0)); self.d = ImageDraw.Draw(self.im)
    def poly(self, p, c): self.d.polygon(pts(p), fill=c)
    def rect(self, x0, y0, x1, y1, c): self.d.rectangle([s(x0), s(y0), s(x1), s(y1)], fill=c)
    def ell(self, cx, cy, rx, ry, c): self.d.ellipse([s(cx - rx), s(cy - ry), s(cx + rx), s(cy + ry)], fill=c)
    def line(self, p, c, w): self.d.line(pts(p), fill=c, width=max(1, s(w)), joint="curve")
    def on(self, base, blur=0):
        # Blurred with color premultiplied by alpha, or the transparent surround bleeds in as a dark fringe.
        im = self.im.convert("RGBa").filter(ImageFilter.GaussianBlur(blur * SS)).convert("RGBA") if blur else self.im
        return Image.alpha_composite(base, im)

def moved(layer, dy):
    """The same layer, slid down by dy (up if negative)."""
    out_ = Lay(); out_.im.paste(layer.im, (0, s(dy))); out_.d = ImageDraw.Draw(out_.im)
    return out_

def glow(base, cx, cy, r, color, strength=1.0):
    layer = Image.new("RGB", (W * SS, H * SS), (0, 0, 0)); d = ImageDraw.Draw(layer)
    d.ellipse([s(cx - r), s(cy - r), s(cx + r), s(cy + r)], fill=tuple(int(c * strength) for c in color))
    layer = layer.filter(ImageFilter.GaussianBlur(r * 0.9 * SS))
    return ImageChops.screen(base.convert("RGB"), layer).convert("RGBA")

def stars(base, n, rnd, maxy):
    L = Lay()
    for _ in range(n):
        x, y = rnd.uniform(0, W), rnd.uniform(0, maxy); b = rnd.randrange(130, 255); r = rnd.choice([0.5, 0.5, 0.8, 1.1])
        L.ell(x, y, r, r, (b, b, b, 255))
    return L.on(base)

def ridge_pts(base_y, amp, rnd, rough=1.0, step=8):
    ph = [rnd.random() * 6.28 for _ in range(4)]
    p = []
    for x in range(-step, W + 2 * step, step):
        u = x / W
        p.append((x, base_y + amp * (0.55 * math.sin(u * 3.1 + ph[0]) + 0.28 * math.sin(u * 7.3 + ph[1])
                                    + rough * 0.12 * math.sin(u * 17 + ph[2]) + rough * 0.06 * math.sin(u * 41 + ph[3]))))
    return p

def ridge(base, base_y, amp, color, rnd, rough=1.0, blur=0, alpha=255):
    L = Lay(); L.poly(ridge_pts(base_y, amp, rnd, rough) + [(W + 20, H + 20), (-20, H + 20)], color + (alpha,))
    return L.on(base, blur)

def lights(base, rnd, x0, x1, y0, y1, n, color):
    layer = Image.new("RGB", (W * SS, H * SS), (0, 0, 0)); d = ImageDraw.Draw(layer)
    for _ in range(n):
        x, y = rnd.uniform(x0, x1), rnd.uniform(y0, y1); r = rnd.choice([0.7, 0.9, 1.2, 1.6])
        d.ellipse([s(x - r), s(y - r), s(x + r), s(y + r)], fill=color)
    soft = layer.filter(ImageFilter.GaussianBlur(4 * SS))
    rgb = ImageChops.screen(ImageChops.screen(base.convert("RGB"), soft), layer)
    return rgb.convert("RGBA")

def smooth(p, rounds=3):
    """Chaikin corner cutting on a closed outline."""
    for _ in range(rounds):
        q = []
        for i in range(len(p)):
            a, b = p[i], p[(i + 1) % len(p)]
            q.append((a[0] * .75 + b[0] * .25, a[1] * .75 + b[1] * .25)); q.append((a[0] * .25 + b[0] * .75, a[1] * .25 + b[1] * .75))
        p = q
    return p

def place(shape, x, y, scale=1.0, flip=False, rot=0.0):
    c, sn = math.cos(rot), math.sin(rot)
    r = []
    for px, py in shape:
        if flip: px = -px
        r.append((x + (px * c - py * sn) * scale, y + (px * sn + py * c) * scale))
    return r

# When the launcher shows each picture: these words go into the file name (see Backdrop.java).
WHEN = {"golden-gate": "dawn.dusk.fog", "coit-tower": "night", "painted-ladies": "dusk", "fleet-week": "day", "sea-lions": "dusk",
        "sutro-tower": "dawn.day.fog", "palace-of-fine-arts": "day", "bay-bridge": "night", "skyline": "night",
        "conservatory": "day", "windmill": "dawn.day", "lands-end": "dusk", "baker-beach": "dusk", "ballpark": "day"}

def save(im, name):
    im.convert("RGB").resize((W, H), Image.LANCZOS).save(f"{out}/{name}.{WHEN[name]}.jpg", quality=90)

def want(name): return not only or name in only

# ------------------------------------------------------------ Golden Gate in fog, dusk
if want("golden-gate"):
    r = random.Random(4)
    im = sky([(0, (16, 20, 52)), (0.4, (58, 44, 104)), (0.62, (168, 82, 110)), (0.76, (240, 150, 96)), (1, (250, 196, 130))])
    im = stars(im, 170, r, H * 0.36)
    im = glow(im, 980, 470, 230, (255, 176, 110), 0.75)
    im = ridge(im, 452, 34, (88, 62, 110), r, blur=1.5)          # Marin headlands, far
    im = ridge(im, 486, 26, (60, 44, 90), r, blur=0.5)
    ORANGE, DARK = (198, 62, 44, 255), (150, 42, 36, 255)
    deck_y, top_y = 452, 150
    towers = [400, 900]
    def cable(x):  # main cable height at x: parabolas hung between the tower tops
        if x <= towers[0]: t = (towers[0] - x) / 470.0; return top_y + (deck_y - 14 - top_y) * min(1, t) ** 1.5
        if x >= towers[1]: t = (x - towers[1]) / 470.0; return top_y + (deck_y - 14 - top_y) * min(1, t) ** 1.5
        m = (towers[0] + towers[1]) / 2; t = (x - m) / (towers[1] - m); return (deck_y - 22) - ((deck_y - 22) - top_y) * t * t
    L = Lay()
    for x in range(-40, W + 40, 15):                              # suspender ropes
        if min(abs(x - towers[0]), abs(x - towers[1])) > 14: L.line([(x, cable(x)), (x, deck_y)], (190, 70, 54, 190), 0.8)
    L.line([(x, cable(x)) for x in range(-60, W + 61, 6)], ORANGE, 3.4)
    L.rect(-10, deck_y, W + 10, deck_y + 7, ORANGE); L.rect(-10, deck_y + 7, W + 10, deck_y + 13, DARK)
    for x in range(-10, W + 10, 22): L.line([(x, deck_y + 13), (x + 11, deck_y + 7), (x + 22, deck_y + 13)], DARK, 0.9)
    for tx in towers:                                             # towers: two stepped legs and four braces
        for side in (-1, 1):
            for (y0, y1, w) in [(top_y - 10, 250, 9), (250, 350, 11), (350, 640, 13)]:
                L.rect(tx + side * 22 - w / 2, y0, tx + side * 22 + w / 2, y1, ORANGE)
            L.rect(tx + side * 22 + (w / 2 - 3 if side > 0 else -w / 2), top_y - 10, tx + side * 22 + (w / 2 if side > 0 else -w / 2 + 3), 640, DARK)
        for by, bh in [(top_y - 4, 16), (232, 20), (318, 20), (404, 22)]: L.rect(tx - 22, by, tx + 22, by + bh, ORANGE); L.rect(tx - 22, by + bh - 4, tx + 22, by + bh, DARK)
    im = L.on(im)
    # Fog: banks that swallow the tower feet and lap over the deck in places.
    for base_y, amp, col, a, bl in [(500, 26, (150, 130, 170), 150, 10), (474, 20, (176, 150, 180), 120, 14),
                                    (540, 30, (120, 108, 156), 225, 8), (592, 26, (84, 78, 126), 245, 6), (650, 22, (50, 48, 88), 255, 4)]:
        im = ridge(im, base_y, amp, col, r, rough=0.5, blur=bl, alpha=a)
    save(im, "golden-gate")

# ------------------------------------------------------------ Coit Tower, blue hour
if want("coit-tower"):
    r = random.Random(12)
    im = sky([(0, (6, 12, 34)), (0.5, (16, 40, 78)), (0.74, (46, 96, 128)), (0.86, (150, 140, 150)), (1, (224, 170, 130))])
    im = stars(im, 220, r, H * 0.5)
    L = Lay(); L.ell(1150, 172, 24, 24, (240, 236, 220, 255)); im = L.on(im); im = glow(im, 1150, 172, 80, (130, 170, 210), 0.5)
    im = ridge(im, 560, 16, (20, 46, 70), r, rough=0.3, blur=1)           # far shore across the bay
    L = Lay(); L.rect(0, 572, W, H, (12, 34, 58, 255)); im = L.on(im)     # the bay
    # Bay Bridge in the distance: towers and strings of lights
    L = Lay()
    for tx in (90, 250, 410): L.rect(tx - 3, 500, tx + 3, 574, (14, 30, 50, 255))
    L.rect(0, 556, 520, 560, (14, 30, 50, 255)); im = L.on(im)
    bl = Image.new("RGB", (W * SS, H * SS), (0, 0, 0)); d = ImageDraw.Draw(bl)
    for a, b in ((-70, 90), (90, 250), (250, 410), (410, 570)):
        for i in range(0, 17):
            t = i / 16.0; x = a + (b - a) * t; y = 504 + 46 * (1 - (2 * t - 1) ** 2)
            d.ellipse([s(x - 1), s(y - 1), s(x + 1), s(y + 1)], fill=(255, 236, 190))
    im = ImageChops.screen(ImageChops.screen(im.convert("RGB"), bl.filter(ImageFilter.GaussianBlur(3 * SS))), bl).convert("RGBA")
    # Telegraph Hill, with trees as a ragged crest, and windows on its slopes
    hill = [(x, 610 - 150 * math.exp(-((x - 800) / 300.0) ** 2) + 5 * math.sin(x / 9.0) + 3 * math.sin(x / 3.7)) for x in range(-20, W + 20, 5)]
    L = Lay(); L.poly([(-20, H + 10)] + hill + [(W + 20, H + 10)], (10, 26, 34, 255)); im = L.on(im)
    im = lights(im, r, 420, 1250, 520, 700, 150, (255, 206, 140))
    im = ridge(im, 668, 14, (5, 14, 20), r, rough=1.5)
    # The tower: a fluted column, an arcade near the top, and a narrower crown
    cx, base, top = 800, 470, 232
    L = Lay()
    L.rect(cx - 27, top + 34, cx + 27, base, (232, 214, 180, 255))
    for i in range(-3, 4): L.rect(cx + i * 7.6 - 0.8, top + 40, cx + i * 7.6 + 0.8, base, (190, 170, 140, 255))
    L.rect(cx + 12, top + 34, cx + 27, base, (160, 146, 130, 120))           # shaded side
    L.rect(cx - 30, top + 22, cx + 30, top + 36, (240, 224, 192, 255))        # gallery band
    L.rect(cx - 27, top - 8, cx + 27, top + 22, (232, 214, 180, 255))
    for i in range(-2, 3):                                                    # arches
        ax = cx + i * 10.4; L.rect(ax - 3, top + 2, ax + 3, top + 20, (40, 50, 66, 255)); L.ell(ax, top + 2, 3, 3.4, (40, 50, 66, 255))
    L.rect(cx - 29, top - 12, cx + 29, top - 8, (240, 224, 192, 255))
    L.rect(cx - 21, top - 34, cx + 21, top - 12, (224, 206, 174, 255))
    for i in range(-2, 3): L.rect(cx + i * 8 - 2, top - 30, cx + i * 8 + 2, top - 16, (40, 50, 66, 255))
    L.rect(cx - 23, top - 38, cx + 23, top - 34, (240, 224, 192, 255))
    im = L.on(im); im = glow(im, cx, 340, 150, (255, 210, 150), 0.32)
    # trees in front of its foot
    L = Lay()
    for i in range(26):
        x = 690 + i * 9 + r.uniform(-3, 3); L.ell(x, 474 + 0.0009 * (x - 800) ** 2 + r.uniform(-4, 4), r.uniform(9, 15), r.uniform(10, 17), (9, 24, 30, 255))
    im = L.on(im)
    save(im, "coit-tower")

# ------------------------------------------------------------ Painted Ladies, golden hour
if want("painted-ladies"):
    r = random.Random(8)
    im = sky([(0, (40, 44, 96)), (0.38, (128, 84, 130)), (0.6, (226, 132, 104)), (0.74, (250, 186, 120)), (1, (252, 214, 160))])
    im = glow(im, 1010, 400, 220, (255, 200, 130), 0.7)
    # Downtown behind: a haze of towers, the pyramid among them
    L = Lay(); C = (120, 86, 128, 255)
    rr = random.Random(5); x = 250
    while x < 1100:
        w = rr.uniform(22, 48); h = 0.7 * rr.uniform(60, 190) * (0.55 + 0.45 * math.exp(-((x - 700) / 260.0) ** 2)); L.rect(x, 250 - h, x + w, 270, C); x += w + rr.uniform(2, 12)
    L.rect(240, 248, 1140, 560, C)                                                      # the city's mass below the towers
    L.poly([(598, 250), (622, 78), (646, 250)], C); L.rect(619, 62, 625, 86, C)       # the pyramid and its spire
    L.rect(772, 96, 810, 250, C); L.poly([(772, 96), (791, 70), (810, 96)], C)       # a tall rounded tower
    im = L.on(im)
    # The row: six houses stepping down the hill
    cols = [(206, 150, 138), (150, 176, 168), (222, 196, 140), (140, 160, 196), (198, 150, 166), (168, 178, 140)]
    L = Lay(); DARKW, LIT, TRIM = (60, 44, 60, 255), (255, 222, 150, 255), (244, 232, 214, 255)
    for i, c in enumerate(cols):
        x0 = 262 + i * 128; yb = 398; w = 116; body_top = yb - 150
        shade = tuple(int(v * 0.72) for v in c) + (255,)
        L.rect(x0, body_top, x0 + w, yb, c + (255,))
        L.rect(x0 + w - 16, body_top, x0 + w, yb, shade)                                 # side in shadow
        L.poly([(x0 - 6, body_top), (x0 + w / 2, body_top - 74), (x0 + w + 6, body_top)], c + (255,))   # gable
        L.poly([(x0 - 6, body_top), (x0 + w / 2, body_top - 74), (x0 + w / 2 + 7, body_top - 66), (x0 + 6, body_top + 2)], TRIM)
        L.poly([(x0 + w + 6, body_top), (x0 + w / 2, body_top - 74), (x0 + w / 2 - 7, body_top - 66), (x0 + w - 6, body_top + 2)], TRIM)
        L.ell(x0 + w / 2, body_top - 30, 9, 11, TRIM); L.ell(x0 + w / 2, body_top - 30, 6, 8, LIT if i % 2 else DARKW)
        bx = x0 + 12                                                                      # bay window, two floors
        L.rect(bx, body_top + 12, bx + 46, yb - 22, TRIM)
        for fy in (body_top + 22, body_top + 82):
            for k in range(3): L.rect(bx + 4 + k * 14, fy, bx + 14 + k * 14, fy + 38, LIT if (i + k + fy) % 3 else DARKW)
        L.rect(x0 + 72, body_top + 22, x0 + 96, body_top + 60, TRIM); L.rect(x0 + 76, body_top + 26, x0 + 92, body_top + 58, LIT if i % 3 else DARKW)
        L.rect(x0 + 70, yb - 62, x0 + 98, yb - 14, TRIM); L.rect(x0 + 75, yb - 57, x0 + 93, yb - 14, (86, 50, 50, 255))  # door
        for k in range(5): L.rect(x0 + 62 - k * 3, yb - 14 + k * 4, x0 + 104 + k * 3, yb - 10 + k * 4, (150, 132, 130, 255))  # steps
        L.rect(x0 - 4, body_top - 2, x0 + w + 4, body_top + 4, TRIM)
    im = L.on(im)
    # Alamo Square in front: a dark green slope, trees at the edges
    base = lambda x: 398.0                                          # the street the row stands on
    xs = list(range(-10, W + 20, 10))
    L = Lay()
    L.poly([(x, base(x) - 3) for x in xs] + [(W + 20, H + 10), (-10, H + 10)], (132, 112, 128, 255))   # pavement
    L.poly([(x, base(x) + 5) for x in xs] + [(W + 20, H + 10), (-10, H + 10)], (62, 52, 74, 255))      # roadway
    im = L.on(im)
    slope = [(x, base(x) + 24 + 0.02 * x + 5 * math.sin(x / 140.0)) for x in xs]
    L = Lay(); L.poly(slope + [(W + 20, H + 10), (-10, H + 10)], (34, 60, 44, 255)); im = L.on(im)
    L = Lay(); L.poly([(x, y + 44 + 8 * math.sin(x / 90.0)) for x, y in slope] + [(W + 20, H + 10), (-10, H + 10)], (20, 40, 32, 255)); im = L.on(im)
    L = Lay()
    for cx, cy, rad in [(60, 318, 150), (196, 392, 84), (1226, 366, 140), (1104, 430, 76)]:
        for k in range(14):
            a = r.uniform(0, 6.28); d = r.uniform(0, rad * 0.6); L.ell(cx + d * math.cos(a), cy + d * math.sin(a) * 0.8, rad * 0.42, rad * 0.4, (18, 34, 30, 255))
        L.rect(cx - 7, cy, cx + 7, cy + 220, (18, 28, 26, 255))
    im = L.on(im)
    save(im, "painted-ladies")

# ------------------------------------------------------------ Fleet Week: jets over the bay
if want("fleet-week"):
    r = random.Random(21)
    im = sky([(0, (10, 30, 82)), (0.45, (30, 84, 150)), (0.7, (104, 160, 204)), (0.735, (170, 204, 226)), (0.74, (20, 62, 104)), (1, (6, 24, 52))])
    L = Lay()                                                                           # thin high cloud
    for _ in range(9):
        cx, cy = r.uniform(0, W), r.uniform(60, 330); L.ell(cx, cy, r.uniform(140, 300), r.uniform(8, 20), (255, 255, 255, 46))
    im = L.on(im, 16)
    im = ridge(im, 520, 14, (60, 96, 130), r, rough=0.4, blur=1.2)                       # far hills
    L = Lay(); L.rect(0, 532, W, H, (16, 54, 96, 255)); im = L.on(im)
    L = Lay()                                                                           # Alcatraz: rock, cell house, lighthouse, water tower
    ax = 930
    L.poly(smooth([(ax - 110, 534), (ax - 96, 520), (ax - 60, 512), (ax - 10, 506), (ax + 44, 510), (ax + 84, 520), (ax + 110, 534)], 2), (22, 44, 70, 255))
    L.rect(ax - 62, 498, ax + 30, 512, (30, 54, 82, 255)); L.rect(ax - 8, 470, ax - 3, 500, (30, 54, 82, 255)); L.rect(ax + 48, 494, ax + 58, 512, (30, 54, 82, 255))
    im = L.on(im)
    L = Lay()                                                                           # light on the water, sailboats
    for _ in range(90):
        x, y = r.uniform(0, W), r.uniform(540, 716); w = r.uniform(10, 60) * (0.4 + (y - 540) / 180.0); L.rect(x, y, x + w, y + 1.2, (150, 196, 230, 70))
    for bx, by, sz in [(210, 590, 1.0), (330, 566, 0.6), (620, 610, 1.3), (1130, 580, 0.8), (760, 556, 0.5)]:
        L.poly([(bx, by), (bx, by - 44 * sz), (bx + 20 * sz, by - 3 * sz)], (244, 246, 250, 255)); L.poly([(bx - 3 * sz, by), (bx - 3 * sz, by - 32 * sz), (bx - 16 * sz, by - 3 * sz)], (226, 232, 242, 255))
        L.poly([(bx - 20 * sz, by + 1), (bx + 24 * sz, by + 1), (bx + 18 * sz, by + 7 * sz), (bx - 15 * sz, by + 7 * sz)], (20, 36, 60, 255))
    im = L.on(im)
    # Six jets in a delta, climbing to the right, each trailing smoke
    jet = [(24, 0), (10, -2.6), (2, -3), (-5, -15), (-11, -15), (-8, -3), (-16, -3), (-20, -9), (-23, -9), (-21, 0)]
    jet = jet + [(x, -y) for x, y in reversed(jet[1:-1])]
    ang = -0.42; ux, uy = math.cos(ang), math.sin(ang); px_, py_ = -uy, ux
    lead = (860, 190); spots = [(0, 0), (-1, -1), (-1, 1), (-2, -2), (-2, 0), (-2, 2)]
    smoke = Lay(); planes = Lay()
    for back, side in spots:
        x = lead[0] + back * 52 * ux + side * 40 * px_; y = lead[1] + back * 52 * uy + side * 40 * py_
        for k in range(60):                                                             # smoke widens and thins behind
            t = k / 59.0; d = 24 + t * 620
            smoke.ell(x - ux * d, y - uy * d, 3 + t * 9, 3 + t * 9, (255, 255, 255, int(150 * (1 - t) ** 1.4)))
        planes.poly(place(jet, x, y, 1.25, rot=ang), (16, 38, 96, 255))
        planes.poly(place([(24, 0), (12, -1.6), (12, 1.6)], x, y, 1.25, rot=ang), (240, 196, 60, 255))
        planes.poly(place([(-16, -3), (-20, -9), (-23, -9), (-21, 0), (-23, 9), (-20, 9), (-16, 3)], x, y, 1.25, rot=ang), (240, 196, 60, 255))
    im = smoke.on(im, 3.5); im = planes.on(im)
    save(im, "fleet-week")

# ------------------------------------------------------------ Sea lions on the docks, sunset
if want("sea-lions"):
    r = random.Random(31)
    im = sky([(0, (44, 30, 80)), (0.3, (140, 62, 96)), (0.5, (236, 120, 80)), (0.6, (252, 186, 110)), (0.615, (236, 150, 96)), (0.62, (150, 70, 80)), (1, (30, 18, 44))])
    im = glow(im, 420, 440, 200, (255, 190, 120), 0.9)
    L = Lay(); L.ell(420, 430, 44, 44, (255, 232, 190, 255)); im = L.on(im)
    L = Lay(); L.rect(0, 446, W, H, (120, 60, 80, 255)); im = L.on(im)                 # the bay at sunset
    im = ridge(im, 436, 10, (104, 52, 86), r, rough=0.3, blur=1)
    L = Lay()                                                                           # the sun's path on the water
    for _ in range(140):
        y = r.uniform(450, 716); spread = 30 + (y - 446) * 0.9; x = 420 + r.gauss(0, spread); w = r.uniform(8, 44)
        L.rect(x - w / 2, y, x + w / 2, y + 1.4, (255, 206, 140, 150))
    im = L.on(im, 0.6)
    L = Lay(); D = (44, 22, 44, 255)                                                    # masts and a pier shed, far right
    for mx, mh in [(900, 120), (938, 150), (984, 96), (1040, 136), (1090, 110), (1150, 160), (1206, 124)]:
        L.rect(mx - 1, 446 - mh, mx + 1, 446, D); L.line([(mx, 446 - mh), (mx + 26, 440)], D, 0.6); L.rect(mx - 22, 440, mx + 30, 448, D)
    L.rect(1010, 404, 1280, 446, D); L.poly([(1000, 404), (1060, 380), (1280, 380), (1280, 404)], D)
    im = L.on(im)
    # Floating docks, nearer ones larger and lower, with sea lions hauled out on them
    sit = smooth([(0, 78), (10, 60), (28, 44), (46, 34), (60, 20), (66, 6), (75, 0), (88, 3), (93, 9), (82, 14), (77, 26), (76, 44), (84, 64), (97, 75), (96, 79), (70, 78), (40, 79)], 3)
    lie = smooth([(0, 30), (12, 18), (38, 9), (70, 6), (92, 10), (106, 3), (117, 6), (119, 13), (108, 19), (99, 24), (96, 31), (60, 32), (20, 32)], 3)
    pup = smooth([(0, 26), (10, 14), (30, 8), (48, 10), (58, 3), (67, 6), (66, 13), (57, 18), (54, 27), (30, 28)], 3)
    def dock(x0, x1, y, th, plank, edge):
        L.rect(x0, y, x1, y + th, plank); L.rect(x0, y + th, x1, y + th + th * 0.5, edge)
        for x in range(int(x0), int(x1), max(6, int(th * 2.2))): L.rect(x, y, x + 1, y + th, edge)
    for (x0, x1, y, th, sc, crew) in [
        (120, 520, 506, 7, 0.55, [(sit, 150, False), (lie, 250, False), (lie, 330, True), (sit, 470, True), (pup, 400, False)]),
        (640, 1000, 520, 8, 0.62, [(lie, 690, False), (sit, 800, False), (sit, 850, True), (lie, 930, True)]),
        (-20, 600, 596, 12, 1.0, [(sit, 60, False), (lie, 190, False), (pup, 330, True), (sit, 420, False), (lie, 560, True)]),
        (700, 1300, 622, 14, 1.15, [(lie, 780, False), (sit, 930, True), (sit, 1010, False), (lie, 1180, True), (pup, 1090, False)])]:
        L = Lay(); dock(x0, x1, y, th, (96, 56, 60, 255), (52, 26, 44, 255))
        for shape, x, flip in crew:
            bottom = max(p[1] for p in shape)
            L.poly(place([(px, py - bottom) for px, py in shape], x, y + 1, sc, flip), (34, 16, 34, 255))
        im = L.on(im)
    save(im, "sea-lions")


def clouds(base, rnd, n, y0, y1, alpha=60, blur=14):
    L = Lay()
    for _ in range(n):
        cx, cy = rnd.uniform(-50, W + 50), rnd.uniform(y0, y1)
        for k in range(5): L.ell(cx + rnd.uniform(-90, 90), cy + rnd.uniform(-10, 10), rnd.uniform(60, 150), rnd.uniform(10, 24), (255, 255, 255, alpha))
    return L.on(base, blur)

def canopy(L, rnd, cx, cy, rad, color, n=12):
    for _ in range(n):
        a = rnd.uniform(0, 6.28); d = rnd.uniform(0, rad * 0.6)
        L.ell(cx + d * math.cos(a), cy + d * math.sin(a) * 0.7, rad * 0.45, rad * 0.4, color)

def mini_bridge(L, x0, x1, deck_y, height, color, towers=(0.3, 0.7), w=1.6):
    """A small suspension bridge seen from far off: deck, two towers and the hung cables."""
    L.rect(x0, deck_y, x1, deck_y + max(1.5, height * 0.03), color)
    tx = [x0 + (x1 - x0) * t for t in towers]; top = deck_y - height
    for t in tx:
        L.rect(t - height * 0.035, top, t - height * 0.012, deck_y + height * 0.25, color); L.rect(t + height * 0.012, top, t + height * 0.035, deck_y + height * 0.25, color)
        for k in range(4): L.rect(t - height * 0.035, top + k * height * 0.24, t + height * 0.035, top + k * height * 0.24 + height * 0.035, color)
    def y(x):
        if x <= tx[0]: u = (tx[0] - x) / max(1.0, tx[0] - x0); return top + (deck_y - top) * min(1, u) ** 1.4
        if x >= tx[1]: u = (x - tx[1]) / max(1.0, x1 - tx[1]); return top + (deck_y - top) * min(1, u) ** 1.4
        m = (tx[0] + tx[1]) / 2; u = (x - m) / (tx[1] - m); return (deck_y - height * 0.06) - ((deck_y - height * 0.06) - top) * u * u
    L.line([(x, y(x)) for x in range(int(x0), int(x1) + 1, 3)], color, w)
    return y

# ------------------------------------------------------------ Sutro Tower above the fog, morning
if want("sutro-tower"):
    r = random.Random(41)
    im = sky([(0, (28, 74, 150)), (0.45, (96, 150, 208)), (0.62, (196, 208, 222)), (0.7, (244, 222, 196)), (1, (236, 228, 226))])
    im = glow(im, 250, 470, 240, (255, 226, 180), 0.6)
    im = clouds(im, r, 5, 60, 240, 40, 18)
    L = Lay(); C = (128, 150, 176, 255)                               # downtown's tallest tips, far off, clear of the fog
    L.poly([(214, 470), (226, 396), (238, 470)], C); L.rect(224, 388, 228, 400, C)
    L.rect(292, 404, 316, 470, C); L.ell(304, 404, 12, 14, C); L.rect(350, 428, 368, 470, C); L.rect(150, 436, 172, 470, C)
    im = L.on(im, 0.6)
    hill = [(x, 470 - 96 * math.exp(-((x - 820) / 210.0) ** 2) - 34 * math.exp(-((x - 1080) / 120.0) ** 2) + 3 * math.sin(x / 7.0)) for x in range(380, W + 20, 4)]
    L = Lay(); L.poly([(380, 520)] + hill + [(W + 20, 520)], (44, 66, 74, 255)); im = L.on(im)
    # The tower: three legs pinched at the waist, three platforms, three masts, banded red and white
    cx, base, RED, WHT = 820, 378, (204, 62, 50, 255), (240, 240, 240, 255)
    def leg(y): 
        t = (base - y) / 230.0                                          # 0 at the foot, 1 at the top platform
        return 46 - 30 * math.sin(min(1, t) * math.pi * 0.62) if t < 1 else 26
    L = Lay()
    bands = [(base, 320, RED), (320, 268, WHT), (268, 214, RED), (214, 148, WHT)]
    for y0, y1, col in bands:
        for side in (-1, 0, 1):
            L.line([(cx + side * leg(y), y) for y in range(int(y0), int(y1) - 1, -4)] + [(cx + side * leg(y1), y1)], col, 4.2 if side else 3.2)
        steps = 2
        for k in range(steps):                                        # cross bracing
            ya, yb_ = y0 + (y1 - y0) * k / steps, y0 + (y1 - y0) * (k + 1) / steps
            L.line([(cx - leg(ya), ya), (cx + leg(yb_), yb_)], col, 1.2); L.line([(cx + leg(ya), ya), (cx - leg(yb_), yb_)], col, 1.2)
    for y, wdt in [(322, 1.25), (238, 1.5), (150, 1.9)]: L.rect(cx - leg(y) * wdt - 4, y - 4, cx + leg(y) * wdt + 4, y + 4, WHT)
    for dx, topy in [(-36, 70), (0, 44), (36, 70)]:
        for k in range(6):
            ya = 146 - k * (146 - topy) / 6.0; L.rect(cx + dx - 2.2, ya - (146 - topy) / 6.0, cx + dx + 2.2, ya, RED if k % 2 == 0 else WHT)
    im = L.on(im)
    # The sea of fog: bright where the sun reaches it, deeper blue toward the bottom
    for base_y, amp, col, a, bl in [(452, 16, (248, 246, 244), 235, 9), (482, 18, (196, 208, 228), 245, 8), (520, 22, (140, 160, 198), 250, 7),
                                    (570, 24, (92, 116, 162), 255, 6), (630, 20, (56, 78, 124), 255, 5), (690, 14, (32, 50, 90), 255, 4)]:
        im = ridge(im, base_y, amp, col, r, rough=0.5, blur=bl, alpha=a)
    save(im, "sutro-tower")

# ------------------------------------------------------------ Palace of Fine Arts, afternoon
if want("palace-of-fine-arts"):
    r = random.Random(52)
    im = sky([(0, (40, 96, 168)), (0.4, (116, 170, 216)), (0.62, (214, 220, 214)), (0.66, (236, 214, 180)), (1, (236, 214, 180))])
    im = clouds(im, r, 6, 40, 230, 54, 16)
    L = Lay(); G1, G2 = (30, 70, 52, 255), (20, 52, 42, 255)             # trees behind
    for cxx, cyy, rad in [(120, 380, 130), (300, 400, 100), (460, 420, 70), (1060, 420, 76), (1200, 380, 130), (930, 430, 50), (590, 432, 46)]: canopy(L, r, cxx, cyy, rad, G1)
    im = moved(L, -70).on(im)
    B = Lay(); OCH, SHD, DARK, DOME = (222, 186, 140, 255), (184, 146, 108, 255), (70, 50, 48, 255), (196, 112, 70, 255)
    cx, wl = 760, 470
    def column(x, top, w=12):
        B.rect(x - w / 2, top, x + w / 2, wl, OCH); B.rect(x + w / 2 - 3, top, x + w / 2, wl, SHD); B.rect(x - w / 2 - 2, top - 5, x + w / 2 + 2, top, OCH); B.rect(x - w / 2 - 2, wl - 5, x + w / 2 + 2, wl, OCH)
    for side in (-1, 1):                                              # the colonnades to either side
        x0 = cx + side * 150; x1 = cx + side * 440
        for k in range(8): column(x0 + (x1 - x0) * k / 7.0, 372, 10)
        B.rect(min(x0, x1) - 10, 356, max(x0, x1) + 10, 372, OCH); B.rect(min(x0, x1) - 10, 368, max(x0, x1) + 10, 372, SHD)
        for k in range(4): bx = x0 + (x1 - x0) * (k + 0.5) / 4.0; B.rect(bx - 20, 336, bx + 20, 356, OCH); B.rect(bx - 20, 352, bx + 20, 356, SHD)
    B.ell(cx, 236, 112, 100, DOME); B.rect(cx - 120, 236, cx + 120, 300, (0, 0, 0, 0))   # the dome, cut flat at its foot
    B.d.rectangle([s(cx - 120), s(236), s(cx + 120), s(300)], fill=(0, 0, 0, 0))
    B.ell(cx + 40, 200, 50, 46, (176, 96, 62, 255)); B.ell(cx - 6, 196, 96, 60, DOME)       # a shaded flank
    B.rect(cx - 116, 232, cx + 116, 278, OCH); B.rect(cx - 116, 270, cx + 116, 278, SHD)   # the drum, with its panels
    for k in range(-2, 3): B.rect(cx + k * 44 - 16, 240, cx + k * 44 + 16, 264, SHD)
    B.rect(cx - 132, 278, cx + 132, 300, OCH); B.rect(cx - 132, 294, cx + 132, 300, SHD)   # entablature
    B.rect(cx - 122, 300, cx + 122, wl, DARK)                                             # the open rotunda, in shadow
    B.rect(cx - 50, 352, cx + 50, wl, (132, 170, 196, 255)); B.ell(cx, 352, 50, 50, (132, 170, 196, 255))  # sky through the far arch
    B.rect(cx - 50, 440, cx + 50, wl, G2)
    for x in (cx - 118, cx - 94, cx - 66, cx + 66, cx + 94, cx + 118): column(x, 300, 14)
    B.rect(cx - 140, wl - 8, cx + 140, wl, OCH)
    B = moved(B, -70); wl = 400
    im = B.on(im)
    # The lagoon, with the building mirrored in it
    W0 = Lay()
    for y in range(wl, H + 1, 2):
        t = (y - wl) / float(H - wl); W0.rect(0, y, W, y + 2, lerp((70, 120, 132), (14, 40, 56), t) + (255,))
    im = W0.on(im)
    mirror = B.im.transpose(Image.FLIP_TOP_BOTTOM)
    shifted = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0)); shifted.paste(mirror, (0, s(2 * wl - H)))
    shifted.putalpha(shifted.getchannel("A").point(lambda a: int(a * 0.42)))
    shifted = shifted.filter(ImageFilter.GaussianBlur(2.2 * SS))
    keep = Image.new("L", (W * SS, H * SS), 0); ImageDraw.Draw(keep).rectangle([0, s(wl), W * SS, H * SS], fill=255)
    im = Image.composite(Image.alpha_composite(im, shifted), im, keep)
    L = Lay()
    for _ in range(60):
        y = r.uniform(wl + 6, H); x = r.uniform(0, W); wd = r.uniform(20, 90); L.rect(x, y, x + wd, y + 1.2, (200, 226, 232, 46))
    canopy(L, r, 40, wl + 10, 110, G2); canopy(L, r, 1250, wl + 16, 110, G2); L.rect(0, wl - 4, 330, wl + 6, G2); L.rect(1190, wl - 4, W, wl + 6, G2)
    im = L.on(im)
    save(im, "palace-of-fine-arts")

# ------------------------------------------------------------ Bay Bridge lights, night
if want("bay-bridge"):
    r = random.Random(63)
    im = sky([(0, (4, 8, 26)), (0.5, (12, 24, 58)), (0.66, (34, 44, 86)), (0.67, (8, 16, 40)), (1, (2, 6, 18))])
    im = stars(im, 240, r, H * 0.5)
    im = glow(im, 1120, 470, 200, (255, 190, 120), 0.35)               # the city's glow at the far end
    L = Lay(); C = (16, 24, 50, 255); rr = random.Random(9); x = 930
    while x < W + 20:                                                 # the city at the bridge's foot
        wd = rr.uniform(20, 44); h = rr.uniform(30, 150); L.rect(x, 470 - h, x + wd, 484, C); x += wd + rr.uniform(0, 6)
    im = L.on(im); im = lights(im, r, 940, W, 340, 478, 190, (255, 214, 150))
    deck, top, tw = 440, 190, [150, 560, 970]
    def cable(x):
        for a, b in zip(tw, tw[1:]):
            if a <= x <= b: m = (a + b) / 2.0; u = (x - m) / (b - m); return (deck - 24) - ((deck - 24) - top) * u * u
        e = tw[0] if x < tw[0] else tw[-1]; u = min(1, abs(x - e) / 300.0); return top + (deck - top) * u ** 1.4
    L = Lay(); T = (22, 32, 60, 255)
    for t in tw:                                                      # towers: two legs, cross braced
        L.rect(t - 20, top - 10, t - 11, 560, T); L.rect(t + 11, top - 10, t + 20, 560, T)
        for k in range(5):
            y0 = top + k * 50; L.rect(t - 20, y0, t + 20, y0 + 6, T); L.line([(t - 16, y0 + 6), (t + 16, y0 + 50)], T, 1.6); L.line([(t + 16, y0 + 6), (t - 16, y0 + 50)], T, 1.6)
    L.rect(-10, deck, W + 10, deck + 9, T); L.rect(-10, deck + 16, W + 10, deck + 20, T)
    for x in range(-10, W + 10, 16): L.line([(x, deck + 9), (x + 8, deck + 16), (x + 16, deck + 9)], T, 1)
    L.line([(x, cable(x)) for x in range(-20, W + 21, 5)], T, 2.4)
    im = L.on(im)
    bl = Image.new("RGB", (W * SS, H * SS), (0, 0, 0)); d = ImageDraw.Draw(bl)
    for x in range(-10, W + 10, 13):                                  # a curtain of lights on the hanger ropes
        if min(abs(x - t) for t in tw) < 22: continue
        y = cable(x) + 4
        while y < deck - 2:
            b = r.choice([255, 255, 220, 170, 110, 60]); d.ellipse([s(x - 0.9), s(y - 0.9), s(x + 0.9), s(y + 0.9)], fill=(b, b, int(b * 0.96))); y += 8
    for x in range(-20, W + 21, 9): yy = cable(x); d.ellipse([s(x - 1.1), s(yy - 1.1), s(x + 1.1), s(yy + 1.1)], fill=(255, 250, 235))
    for x in range(0, W, 26): d.ellipse([s(x - 1), s(deck - 3), s(x + 1), s(deck - 1)], fill=(255, 200, 120))
    for t in tw: d.ellipse([s(t - 2), s(top - 22), s(t + 2), s(top - 18)], fill=(255, 60, 50))
    im = ImageChops.screen(ImageChops.screen(im.convert("RGB"), bl.filter(ImageFilter.GaussianBlur(3 * SS))), bl).convert("RGBA")
    L = Lay()                                                         # light lying on the water
    for _ in range(260):
        x = r.uniform(0, W); y = r.uniform(476, 716); wd = r.uniform(6, 30) * (0.5 + (y - 470) / 250.0); a = int(110 * (1 - (y - 470) / 300.0))
        L.rect(x, y, x + wd, y + 1.2, (255, 232, 190, max(20, a)) if x < 900 else (255, 200, 130, max(20, a)))
    im = L.on(im, 0.5)
    save(im, "bay-bridge")

# ------------------------------------------------------------ The skyline from the water, night
if want("skyline"):
    r = random.Random(74)
    im = sky([(0, (6, 8, 30)), (0.45, (22, 26, 70)), (0.64, (70, 52, 104)), (0.655, (10, 14, 40)), (1, (2, 4, 16))])
    im = stars(im, 260, r, H * 0.42)
    im = glow(im, 700, 470, 330, (150, 110, 190), 0.3)
    wl = 470
    def block(L, x0, x1, top, col, lit, step=7, win=(255, 220, 150)):
        L.rect(x0, top, x1, wl, col)
        for yy in range(int(top) + 6, wl - 4, step):
            for xx in range(int(x0) + 4, int(x1) - 3, 6):
                if r.random() < lit: L.rect(xx, yy, xx + 2.4, yy + 3, win + (r.choice([255, 220, 170]),))
    L = Lay(); far, mid, near = (30, 32, 78, 255), (20, 22, 58, 255), (12, 14, 40, 255)
    rr = random.Random(3); x = 200
    while x < 1120: wd = rr.uniform(26, 60); block(L, x, x + wd, wl - rr.uniform(60, 190), far, 0.22); x += wd + rr.uniform(0, 10)
    im = L.on(im)
    L = Lay()
    for x0, x1, top in [(250, 300, 300), (312, 372, 250), (392, 440, 320), (470, 540, 230), (700, 760, 270), (780, 850, 210), (930, 990, 290), (1010, 1070, 330), (870, 915, 300)]: block(L, x0, x1, top, mid, 0.36)
    L.poly([(574, wl), (604, 150), (634, wl)], mid); L.rect(601, 122, 607, 156, mid)        # the pyramid
    for yy in range(200, wl - 4, 9):
        half = 30 * (yy - 150) / float(wl - 150)
        for xx in range(int(604 - half) + 3, int(604 + half) - 2, 6):
            if r.random() < 0.4: L.rect(xx, yy, xx + 2.2, yy + 3, (255, 220, 150, 230))
    block(L, 646, 692, 150, near, 0.5, win=(236, 240, 255)); L.ell(669, 150, 23, 34, near)       # the tallest tower, round-topped
    for k in range(7): L.rect(650, 124 + k * 5, 688, 126 + k * 5, (150, 190, 255, 200 - k * 20))
    im = L.on(im); im = glow(im, 669, 132, 46, (120, 160, 255), 0.5)
    L = Lay()                                                         # the waterfront: piers, and a clock tower
    for x0, x1 in [(180, 330), (350, 470), (560, 690), (760, 900), (930, 1100)]: L.rect(x0, wl - 16, x1, wl, near)
    L.rect(505, 372, 529, wl, (226, 214, 190, 255)); L.rect(501, 366, 533, 374, (240, 230, 206, 255)); L.rect(509, 344, 525, 366, (226, 214, 190, 255))
    L.poly([(509, 344), (517, 322), (525, 344)], (226, 214, 190, 255)); L.ell(517, 392, 7, 7, (255, 240, 190, 255)); L.rect(430, wl - 24, 604, wl, (160, 150, 140, 255))
    for xx in range(436, 600, 9): L.rect(xx, wl - 18, xx + 4, wl - 6, (255, 220, 150, 255))
    im = L.on(im); im = glow(im, 517, 392, 30, (255, 220, 150), 0.6)
    im = lights(im, r, 180, 1100, wl - 14, wl - 2, 110, (255, 214, 150))
    L = Lay()
    for _ in range(420):                                              # the city mirrored as streaks
        x = r.gauss(650, 260); y = r.uniform(wl + 4, 716); wd = r.uniform(4, 22) * (0.5 + (y - wl) / 240.0)
        col = r.choice([(255, 220, 150), (255, 220, 150), (150, 190, 255), (236, 240, 255)]); L.rect(x, y, x + wd, y + 1.2, col + (int(140 * (1 - (y - wl) / 290.0)),))
    im = L.on(im, 0.5)
    save(im, "skyline")

# ------------------------------------------------------------ The Conservatory in Golden Gate Park, midday
if want("conservatory"):
    r = random.Random(85)
    im = sky([(0, (36, 100, 182)), (0.4, (110, 172, 226)), (0.6, (196, 222, 236)), (1, (196, 222, 236))])
    im = clouds(im, r, 7, 40, 250, 70, 14)
    L = Lay(); G1, G2 = (36, 92, 60, 255), (24, 66, 48, 255)
    for cxx, cyy, rad in [(90, 330, 150), (300, 360, 110), (470, 380, 60), (1050, 380, 70), (1200, 330, 150), (640, 380, 40), (880, 380, 40)]: canopy(L, r, cxx, cyy, rad, G1)
    im = moved(L, -34).on(im)
    L = Lay(); WH, SH, GL, FR = (246, 246, 240, 255), (204, 212, 214, 255), (170, 214, 226, 255), (228, 232, 230, 255)
    cx, gy = 760, 424
    for side in (-1, 1):                                              # the wings: a low wall of arched glass under a pitched glass roof
        x0, x1 = cx + side * 70, cx + side * 300
        a, b = min(x0, x1), max(x0, x1)
        L.poly([(a - 6, 376), (a + 16, 340), (b - 16, 340), (b + 6, 376)], GL)
        for k in range(1, 12): xx = a + (b - a) * k / 12.0; L.line([(xx, 376), (xx + (cx - xx) * 0.02, 340)], WH, 1.2)
        L.rect(a - 8, 372, b + 8, 380, WH); L.rect(a, 380, b, gy, WH)
        for k in range(9):
            xx = a + 10 + (b - a - 20) * k / 8.0; L.rect(xx - 7, 394, xx + 7, gy - 4, GL); L.ell(xx, 394, 7, 7, GL)
        ex = cx + side * 318                                           # an end pavilion with its own small dome
        L.rect(ex - 30, 366, ex + 30, gy, WH); L.ell(ex, 366, 30, 26, GL); L.rect(ex - 32, 362, ex + 32, 370, WH); L.rect(ex - 2, 330, ex + 2, 344, WH)
        for k in (-1, 0, 1): L.rect(ex + k * 18 - 6, 384, ex + k * 18 + 6, gy - 4, GL); L.ell(ex + k * 18, 384, 6, 6, GL)
    L.rect(cx - 78, 330, cx + 78, gy, WH)                               # the central pavilion and its great dome
    L.ell(cx, 322, 80, 92, GL); L.d.rectangle([s(cx - 90), s(322), s(cx + 90), s(420)], fill=WH)
    L.rect(cx - 78, 330, cx + 78, gy, WH)
    for k in range(-4, 5):                                            # ribs
        L.line([(cx + k * 19, 322), (cx + k * 15, 268), (cx + k * 7, 236)], WH, 1.3)
    for yy in (300, 272, 250): L.line([(cx - 80 * math.sqrt(max(0, 1 - ((322 - yy) / 92.0) ** 2)), yy), (cx + 80 * math.sqrt(max(0, 1 - ((322 - yy) / 92.0) ** 2)), yy)], WH, 1.2)
    L.rect(cx - 84, 316, cx + 84, 332, WH); L.rect(cx - 84, 328, cx + 84, 332, SH)
    L.rect(cx - 12, 214, cx + 12, 234, WH); L.ell(cx, 214, 12, 12, GL); L.rect(cx - 1.5, 186, cx + 1.5, 204, WH)   # the lantern on top
    for k in range(-3, 4): L.rect(cx + k * 21 - 6, 346, cx + k * 21 + 6, 392, GL); L.ell(cx + k * 21, 346, 6, 6, GL)
    L.rect(cx - 16, 386, cx + 16, gy, (120, 150, 160, 255)); L.ell(cx, 386, 16, 14, (120, 150, 160, 255))
    L.rect(cx - 320, gy - 3, cx + 350, gy + 3, FR)
    im = moved(L, -34).on(im); gy = 390
    L = Lay()                                                         # the lawn, falling away toward us
    for y in range(gy, H + 1, 2): t = (y - gy) / float(H - gy); L.rect(0, y, W, y + 2, lerp((92, 164, 84), (14, 54, 36), t ** 0.8) + (255,))
    L.poly([(cx - 22, gy), (cx + 22, gy), (cx + 70, 560), (cx - 70, 560)], (214, 204, 184, 255))            # the steps and path
    im = L.on(im)
    L = Lay()                                                         # flower beds in arcs across the lawn
    for arc, (ry, cols) in enumerate([(52, [(232, 64, 70), (250, 250, 240)]), (84, [(250, 196, 50), (236, 110, 60)]), (120, [(226, 90, 150), (250, 250, 240)])]):
        for k in range(150):
            a = math.pi * (0.06 + 0.88 * k / 149.0); side = -1 if k % 2 else 1
            x = cx + (120 + arc * 150) * math.cos(a) * 1.15; y = gy + 24 + ry * math.sin(a) + r.uniform(-3, 3)
            if abs(x - cx) < 80: continue
            rad = 2.2 + arc * 0.9; L.ell(x + r.uniform(-4, 4), y, rad, rad * 0.8, r.choice(cols) + (255,))
    for px, ph in [(330, 150), (1190, 150), (470, 110), (1050, 110)]:  # palms
        L.rect(px - 3, gy + 16 - ph, px + 3, gy + 28, (88, 70, 54, 255))
        for k in range(9):
            a = -math.pi * (0.06 + 0.88 * k / 8.0); L.line([(px, gy + 16 - ph), (px + 34 * math.cos(a), gy + 16 - ph + 26 * math.sin(a) + 6), (px + 50 * math.cos(a), gy + 16 - ph + 24 * math.sin(a) + 26)], (30, 84, 56, 255), 3)
    im = L.on(im)
    save(im, "conservatory")

# ------------------------------------------------------------ The windmill and poppies, Golden Gate Park, morning
if want("windmill"):
    r = random.Random(96)
    im = sky([(0, (46, 104, 180)), (0.42, (126, 180, 226)), (0.6, (226, 226, 214)), (0.66, (250, 226, 190)), (1, (250, 226, 190))])
    im = glow(im, 300, 430, 220, (255, 226, 170), 0.6)
    im = clouds(im, r, 6, 50, 260, 64, 14)
    L = Lay(); G1, G2 = (32, 78, 58, 255), (22, 58, 46, 255)            # wind-shaped cypress behind
    for cxx, cyy, rad in [(60, 400, 120), (250, 420, 90), (420, 432, 60), (640, 436, 50), (1090, 420, 80), (1230, 396, 120), (540, 440, 44), (980, 436, 50)]: canopy(L, r, cxx, cyy, rad, G1)
    im = L.on(im)
    cx, base = 860, 478
    L = Lay(); BODY, SHADE, TRIM, DARK = (116, 84, 62, 255), (84, 60, 48, 255), (240, 236, 226, 255), (52, 40, 38, 255)
    L.poly([(cx - 62, base), (cx - 30, 262), (cx + 30, 262), (cx + 62, base)], BODY)        # the tapering shingled tower
    L.poly([(cx + 16, base), (cx + 8, 262), (cx + 30, 262), (cx + 62, base)], SHADE)
    for y in range(280, base, 13): half = 30 + 32 * (y - 262) / float(base - 262); L.line([(cx - half, y), (cx + half, y)], SHADE, 0.8)
    L.rect(cx - 78, 392, cx + 78, 400, TRIM)                                                # the gallery and its rail
    for k in range(-6, 7): L.rect(cx + k * 12.5 - 0.8, 376, cx + k * 12.5 + 0.8, 392, TRIM)
    L.rect(cx - 78, 374, cx + 78, 377, TRIM)
    for k in (-1, 1): L.line([(cx + k * 76, 400), (cx + k * 48, 430)], TRIM, 2)
    for wy, ww in [(300, 8), (345, 9), (432, 11)]: L.rect(cx - ww, wy, cx + ww, wy + 2.4 * ww, TRIM); L.rect(cx - ww + 2.5, wy + 2.5, cx + ww - 2.5, wy + 2.4 * ww - 2.5, DARK)
    L.rect(cx - 12, base - 34, cx + 12, base, TRIM); L.rect(cx - 9, base - 31, cx + 9, base, DARK)
    L.poly(smooth([(cx - 36, 264), (cx - 34, 240), (cx - 14, 222), (cx + 14, 222), (cx + 34, 240), (cx + 36, 264)], 2), DARK)   # the cap
    L.rect(cx - 38, 260, cx + 38, 266, TRIM)
    im = L.on(im)
    L = Lay(); hub = (cx, 244)                                        # four latticed sails
    for k in range(4):
        a = math.radians(32 + 90 * k); ux, uy = math.cos(a), math.sin(a); px_, py_ = -uy, ux
        tip = (hub[0] + ux * 190, hub[1] + uy * 190)
        L.line([hub, tip], TRIM, 3)
        q = lambda d, o: (hub[0] + ux * d + px_ * o, hub[1] + uy * d + py_ * o)
        L.line([q(34, 0), q(34, 30), q(188, 30), q(188, 0)], TRIM, 1.4)
        for d in range(34, 189, 14): L.line([q(d, 0), q(d, 30)], TRIM, 1.0)
        for o in (10, 20): L.line([q(34, o), q(188, o)], TRIM, 0.8)
    L.ell(hub[0], hub[1], 6, 6, DARK)
    im = L.on(im)
    L = Lay()                                                         # the meadow
    for y in range(470, H + 1, 2): t = (y - 470) / float(H - 470); L.rect(0, y, W, y + 2, lerp((96, 156, 76), (14, 50, 34), t ** 0.75) + (255,))
    im = L.on(im)
    im = ridge(im, 476, 8, (84, 146, 72), r, rough=0.4)
    L = Lay()                                                         # poppies: small and many far off, larger up close
    for _ in range(900):
        t = r.random() ** 1.6; y = 486 + t * 234; x = r.uniform(-10, W + 10); sz = 1.6 + t * 9
        col = r.choice([(255, 140, 20), (255, 122, 16), (255, 164, 40), (250, 104, 20)])
        if sz > 5: L.line([(x, y), (x + r.uniform(-2, 2), y + sz * 2.2)], (40, 96, 50, 255), 1.0)
        L.ell(x, y, sz, sz * 0.72, col + (255,)); L.ell(x, y - sz * 0.1, sz * 0.36, sz * 0.26, (220, 80, 10, 255))
    im = L.on(im)
    save(im, "windmill")

# ------------------------------------------------------------ Lands End, sunset
if want("lands-end"):
    r = random.Random(107)
    im = sky([(0, (40, 34, 90)), (0.3, (120, 62, 110)), (0.48, (232, 120, 84)), (0.565, (252, 196, 120)), (0.57, (190, 110, 104)), (1, (30, 30, 70))])
    im = glow(im, 760, 400, 220, (255, 196, 120), 0.85)
    L = Lay(); L.ell(760, 404, 34, 34, (255, 236, 196, 255)); im = L.on(im)
    L = Lay()                                                         # the ocean
    for y in range(408, H + 1, 2): t = (y - 408) / float(H - 408); L.rect(0, y, W, y + 2, lerp((176, 104, 104), (22, 26, 66), t ** 0.7) + (255,))
    im = L.on(im)
    im = ridge(im, 398, 14, (104, 60, 96), r, rough=0.4, blur=0.8)      # the Marin headlands across the strait
    L = Lay(); mini_bridge(L, 900, 1290, 404, 70, (150, 52, 50, 255), towers=(0.22, 0.74)); im = L.on(im)
    L = Lay()
    for _ in range(150):
        y = r.uniform(412, 700); spread = 20 + (y - 408) * 0.7; x = 760 + r.gauss(0, spread); wd = r.uniform(8, 40); L.rect(x - wd / 2, y, x + wd / 2, y + 1.3, (255, 206, 140, 150))
    im = L.on(im, 0.5)
    ROCK, ROCK2 = (40, 28, 50, 255), (24, 16, 34, 255)
    L = Lay()                                                         # sea stacks, with foam at their feet
    for sx, sy, sc in [(600, 470, 1.0), (680, 452, 0.6), (980, 520, 1.3), (1090, 500, 0.7)]:
        L.poly(smooth(place([(-40, 0), (-30, -24), (-12, -44), (6, -38), (20, -52), (34, -28), (44, 0)], sx, sy, sc), 2), ROCK)
        L.ell(sx, sy + 1, 54 * sc, 4 * sc, (250, 232, 220, 150))
    im = L.on(im)
    cliff = [(-10, 250), (60, 236), (150, 250), (230, 290), (300, 300), (360, 340), (410, 380), (450, 430), (500, 470), (540, 520), (600, 560), (640, 620), (700, 660), (760, 720)]
    L = Lay(); L.poly(cliff + [(-10, 730)], ROCK); im = L.on(im)       # the headland we stand on
    L = Lay(); L.poly([(-10, 430), (120, 420), (240, 470), (330, 540), (420, 600), (520, 690), (560, 730), (-10, 730)], ROCK2); im = L.on(im)
    L = Lay()                                                         # foam along its foot
    for k in range(40):
        t = k / 39.0; x = 500 + t * 270 + r.uniform(-6, 6); y = 474 + t * 240 + r.uniform(-4, 4); L.ell(x + 14, y, r.uniform(10, 26), 3, (250, 232, 220, 130))
    im = L.on(im, 1)
    L = Lay()                                                         # cypress, blown flat by the sea wind
    for tx, ty, sc in [(70, 240, 1.3), (170, 256, 1.0), (262, 298, 0.9), (336, 326, 0.7), (20, 250, 0.9)]:
        L.line([(tx, ty + 6), (tx + 6 * sc, ty - 40 * sc), (tx + 22 * sc, ty - 76 * sc)], ROCK2, 4.5 * sc)
        L.line([(tx + 8 * sc, ty - 46 * sc), (tx + 40 * sc, ty - 62 * sc)], ROCK2, 2.6 * sc)
        for (ox, oy, rx, ry) in [(34, -88, 48, 13), (62, -78, 40, 10), (12, -98, 30, 10), (56, -66, 30, 8)]: L.ell(tx + ox * sc, ty + oy * sc, rx * sc, ry * sc, ROCK2)
    im = L.on(im)
    save(im, "lands-end")

# ------------------------------------------------------------ Baker Beach, last light
if want("baker-beach"):
    r = random.Random(118)
    im = sky([(0, (36, 40, 104)), (0.3, (110, 74, 136)), (0.46, (226, 124, 110)), (0.545, (250, 190, 130)), (0.55, (150, 100, 120)), (1, (30, 30, 60))])
    im = glow(im, 330, 392, 240, (255, 190, 130), 0.8)
    L = Lay()
    for y in range(394, H + 1, 2): t = (y - 394) / float(H - 394); L.rect(0, y, W, y + 2, lerp((178, 116, 124), (40, 40, 84), t ** 0.6) + (255,))
    im = L.on(im)
    hl = [(x, 392 - 96 * math.exp(-((x - 250) / 230.0) ** 2) - 44 * math.exp(-((x - 560) / 130.0) ** 2) + 4 * math.sin(x / 23.0)) for x in range(-10, 800, 6)]
    L = Lay(); L.poly([(-10, 396)] + hl + [(800, 396)], (84, 56, 104, 255)); im = L.on(im, 0.6)   # the Marin headlands
    # The bridge, running away from us to the left: the near tower tall, the far one small
    L = Lay(); OR, DK = (206, 70, 50, 255), (150, 46, 40, 255)
    def deck(x): return 326 + (1300 - x) * 0.072
    def tower(x, h, w):
        b = deck(x) + h * 0.32
        for sgn in (-1, 1): L.rect(x + sgn * w - w * 0.22, deck(x) - h, x + sgn * w + w * 0.22, b, OR)
        for k in range(4): yy = deck(x) - h + k * h * 0.26; L.rect(x - w, yy, x + w, yy + h * 0.045, OR)
    near, far = (1010, 230, 20), (690, 112, 10)
    def cab(x):
        nx, fx = near[0], far[0]; nt, ft = deck(nx) - near[1], deck(fx) - far[1]
        if x >= nx: u = min(1, (x - nx) / 330.0); return nt + (deck(x) - 8 - nt) * u ** 1.3
        if x <= fx: u = min(1, (fx - x) / 170.0); return ft + (deck(x) - 4 - ft) * u ** 1.3
        u = (x - fx) / float(nx - fx); line = ft + (nt - ft) * u; sag = (deck(x) - 10 - line) * (1 - (2 * u - 1) ** 2); return line + sag
    for x in range(520, W + 10, 9):
        if min(abs(x - near[0]), abs(x - far[0])) > 12: L.line([(x, cab(x)), (x, deck(x))], (206, 80, 60, 170), 0.7)
    L.line([(x, cab(x)) for x in range(516, W + 20, 4)], OR, 2.6)
    L.poly([(500, deck(500)), (W + 10, deck(W + 10)), (W + 10, deck(W + 10) + 9), (500, deck(500) + 3)], OR)
    L.poly([(500, deck(500) + 3), (W + 10, deck(W + 10) + 9), (W + 10, deck(W + 10) + 14), (500, deck(500) + 5)], DK)
    tower(*far); tower(*near)
    im = L.on(im)
    L = Lay()                                                         # the Presidio bluff on our right, and cypress on it
    bluff = [(880, 730), (930, 620), (980, 540), (1040, 470), (1100, 420), (1160, 388), (1230, 360), (1290, 350), (1290, 730)]
    L.poly(smooth(bluff, 1), (34, 30, 54, 255))
    for cxx, cyy, rad in [(1250, 344, 60), (1170, 378, 44), (1100, 414, 34)]: canopy(L, r, cxx, cyy, rad, (22, 24, 42, 255))
    im = L.on(im)
    L = Lay()                                                         # the sand, sweeping round from the left
    sand = [(x, 600 - 0.00016 * (x - 1150) ** 2 + 40 * math.exp(-((x - 60) / 300.0) ** 2)) for x in range(-10, 1160, 10)]
    L.poly(sand + [(1160, 730), (-10, 730)], (120, 86, 96, 255))
    im = L.on(im)
    L = Lay(); L.poly([(x, y + 46 + 0.02 * x) for x, y in sand] + [(1160, 730), (-10, 730)], (70, 50, 70, 255)); im = L.on(im, 6)
    L = Lay()                                                         # waves sliding up it
    for k, (off, a) in enumerate([(-58, 110), (-34, 150), (-12, 200), (-3, 235)]):
        L.line([(x, y + off + 5 * math.sin(x / 60.0 + k * 1.7) + 3 * math.sin(x / 23.0 + k)) for x, y in sand], (250, 226, 214, a), 2.2 - k * 0.2)
    for _ in range(120):
        y = r.uniform(400, 560); x = r.uniform(0, 900); wd = r.uniform(10, 50); L.rect(x, y, x + wd, y + 1.2, (255, 206, 160, 70))
    im = L.on(im, 0.7)
    save(im, "baker-beach")

# ------------------------------------------------------------ An afternoon game at the ballpark by the bay
if want("ballpark"):
    r = random.Random(129)
    im = sky([(0, (34, 96, 180)), (0.3, (104, 166, 224)), (0.42, (190, 216, 234)), (1, (190, 216, 234))])
    im = clouds(im, r, 6, 30, 170, 70, 14)
    im = ridge(im, 236, 10, (96, 130, 150), r, rough=0.4, blur=1)       # the hills across the bay
    L = Lay(); L.rect(0, 244, W, 330, (44, 104, 150, 255))              # the cove beyond right field
    for _ in range(60): x = r.uniform(700, W); y = r.uniform(250, 300); L.rect(x, y, x + r.uniform(8, 30), y + 1, (190, 220, 240, 110))
    for bx, by, sz in [(830, 276, 0.5), (960, 262, 0.4), (1100, 284, 0.6), (1210, 268, 0.45)]:
        L.poly([(bx, by), (bx, by - 40 * sz), (bx + 18 * sz, by - 3 * sz)], (246, 248, 250, 255)); L.poly([(bx - 16 * sz, by), (bx + 20 * sz, by), (bx + 14 * sz, by + 6 * sz), (bx - 12 * sz, by + 6 * sz)], (30, 50, 70, 255))
    im = L.on(im)
    arc = lambda x: 560 - 250 * math.sqrt(max(0.0, 1 - ((x - 640) / 760.0) ** 2))            # the outfield fence
    L = Lay(); ST, ST2, WALL, BRICK = (70, 90, 110, 255), (54, 70, 90, 255), (18, 62, 44, 255), (150, 74, 54, 255)
    for k in range(7):                                                # bleachers, left and center
        L.poly([(-10, arc(-10) - 14 - k * 13)] + [(x, arc(x) - 14 - k * 13) for x in range(0, 700, 20)] + [(700, arc(700) - 14 - (k + 1) * 13)] + [(x, arc(x) - 14 - (k + 1) * 13) for x in range(680, -30, -20)], ST if k % 2 else ST2)
    for _ in range(900):                                              # the crowd
        x = r.uniform(0, 690); k = r.uniform(0, 7); L.ell(x, arc(x) - 18 - k * 13, 1.5, 1.5, r.choice([(236, 120, 40), (240, 240, 240), (30, 30, 30), (236, 120, 40), (200, 170, 140)]) + (255,))
    L.rect(520, 150, 760, 250, (22, 30, 40, 255)); L.rect(526, 156, 754, 244, (34, 48, 62, 255))        # the scoreboard, blank
    for (x0, y0, x1, y1, c) in [(536, 166, 640, 206, (60, 110, 80)), (650, 166, 744, 184, (200, 120, 50)), (650, 190, 744, 206, (80, 96, 120)), (536, 214, 744, 234, (50, 66, 84))]: L.rect(x0, y0, x1, y1, c + (255,))
    L.rect(560, 250, 572, 300, (22, 30, 40, 255)); L.rect(708, 250, 720, 300, (22, 30, 40, 255))
    L.poly([(700, arc(700) - 44)] + [(x, arc(x) - 44) for x in range(720, W + 20, 20)] + [(x, arc(x)) for x in range(W + 10, 690, -20)], BRICK)   # the tall brick wall in right
    for x in range(730, W, 46): yy = arc(x); L.rect(x, yy - 30, x + 22, yy - 4, (60, 34, 34, 255)); L.ell(x + 11, yy - 30, 11, 9, (60, 34, 34, 255))
    L.poly([(-10, arc(-10) - 14)] + [(x, arc(x) - 14) for x in range(0, 720, 20)] + [(x, arc(x)) for x in range(700, -30, -20)], WALL)                # the fence in left and center
    for lx in (150, 1130):                                            # light towers
        L.rect(lx - 4, 170, lx + 4, 330, (60, 70, 84, 255)); L.rect(lx - 46, 146, lx + 46, 194, (50, 60, 74, 255))
        for i in range(6):
            for j in range(3): L.ell(lx - 37 + i * 15, 156 + j * 14, 4, 4, (250, 250, 236, 255))
    L.poly(smooth(place([(-22, 10), (-26, -14), (-18, -30), (-10, -16), (-8, -36), (0, -18), (4, -36), (12, -16), (18, -28), (24, -6), (20, 14), (0, 22)], 330, 232, 1.5), 2), (140, 86, 50, 255))   # a giant glove
    L.rect(326, 262, 334, 300, (60, 70, 84, 255))
    L.rect(88, 190, 92, 320, (250, 214, 60, 255)); L.rect(1188, 150, 1192, 300, (250, 214, 60, 255))    # foul poles
    im = L.on(im)
    L = Lay(); GA, GB, DIRT = (58, 142, 70, 255), (48, 126, 62, 255), (176, 124, 84, 255)
    L.poly([(x, arc(x)) for x in range(-10, W + 20, 10)] + [(W + 10, 730), (-10, 730)], GA)              # the outfield, mown in bands
    for k in range(0, 9, 2):
        a0, a1 = math.radians(206 + k * 14), math.radians(206 + (k + 1) * 14)
        L.poly([(640, 690), (640 + 1400 * math.cos(a0), 690 + 700 * math.sin(a0)), (640 + 1400 * math.cos(a1), 690 + 700 * math.sin(a1))], GB)
    im2 = L.on(Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0)))
    mask = Lay(); mask.poly([(x, arc(x)) for x in range(-10, W + 20, 10)] + [(W + 10, 730), (-10, 730)], (255, 255, 255, 255))
    im = Image.composite(Image.alpha_composite(im, im2), im, mask.im.getchannel("A"))
    L = Lay()
    L.ell(640, 600, 250, 100, DIRT)                                    # the infield
    L.poly([(640, 664), (790, 596), (640, 536), (490, 596)], GA)
    L.line([(640, 672), (240, 490)], (250, 250, 250, 255), 1.6); L.line([(640, 672), (1040, 490)], (250, 250, 250, 255), 1.6)
    L.ell(640, 602, 16, 7, DIRT); L.rect(636, 600, 644, 602, (250, 250, 250, 255)); L.ell(640, 672, 34, 14, DIRT)
    for bx, by in [(790, 596), (640, 536), (490, 596)]: L.poly([(bx - 6, by), (bx, by - 3), (bx + 6, by), (bx, by + 3)], (250, 250, 250, 255))
    L.poly([(634, 672), (640, 669), (646, 672), (640, 676)], (250, 250, 250, 255))
    for px, py in [(640, 598), (700, 560), (580, 560), (790, 580), (492, 580), (420, 440), (640, 410), (880, 440), (640, 660), (652, 668)]:   # players
        L.ell(px, py - 9, 2.6, 2.6, (240, 240, 240, 255)); L.rect(px - 2.4, py - 7, px + 2.4, py, (240, 240, 240, 255) if py != 660 else (60, 60, 60, 255))
    im = L.on(im)
    L = Lay()                                                         # the rows of seats below us
    for k in range(4):
        y = 664 + k * 16; L.poly([(-10, y + 20 * math.cos(0)), (640, y - 12), (W + 10, y + 20), (W + 10, 730), (-10, 730)], (16 + k * 2, 44 - k * 6, 36 - k * 4, 255))
    for _ in range(260): x = r.uniform(0, W); k = r.randrange(0, 4); y = 664 + k * 16 + 20 * abs(x - 640) / 650.0 * 1.6 - 8; L.ell(x, y, 3, 3, r.choice([(236, 120, 40), (30, 30, 30), (200, 200, 200)]) + (255,))
    im = L.on(im)
    save(im, "ballpark")
