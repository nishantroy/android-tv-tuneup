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
        im = self.im.filter(ImageFilter.GaussianBlur(blur * SS)) if blur else self.im
        return Image.alpha_composite(base, im)

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
WHEN = {"golden-gate": "dawn.dusk.fog", "coit-tower": "night", "painted-ladies": "dusk", "fleet-week": "day", "sea-lions": "dusk"}

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
