"""Drawing toolkit for the yellow-hoodie stickman explainer videos.

Everything is plain PIL: sprites cut from the character sheets, doodle props drawn
with thick outlines (supersampled for smooth edges), Hindi captions via raqm.
"""
import json, math, os, random
from functools import lru_cache
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance

HERE = os.path.dirname(os.path.abspath(__file__))
W, H, FPS = 1920, 1080, 30
INK = (30, 30, 34)
PAPER = (250, 247, 240)
YEL = (247, 196, 26)
RED = (226, 54, 54)
BLUE = (66, 133, 222)
GREEN = (60, 170, 90)
SS = 2  # supersampling for static drawings

FONT = os.path.join(HERE, "fonts", "baloo-2-800.ttf")
FONT2 = os.path.join(HERE, "fonts", "martel-800.ttf")


@lru_cache(None)
def font(size, f=FONT):
    return ImageFont.truetype(f, size)


# ---------------------------------------------------------------- easing
def clamp(x, a=0.0, b=1.0):
    return a if x < a else b if x > b else x


def ease(x):
    x = clamp(x)
    return x * x * (3 - 2 * x)


def back(x):  # overshoot pop 0->1
    x = clamp(x)
    c = 1.9
    return 1 + (c + 1) * (x - 1) ** 3 + c * (x - 1) ** 2


def lerp(a, b, x):
    return a + (b - a) * x


def pop(t, t0, d=0.35):
    """scale factor for an element that appears at t0"""
    if t < t0:
        return 0.0
    return back((t - t0) / d)


def fade(t, t0, d=0.3):
    return ease((t - t0) / d)


def win(t, t0, t1, d=0.3):
    """1 inside [t0,t1] with fades at both ends"""
    return min(fade(t, t0, d), 1 - fade(t, t1 - d, d))


# ---------------------------------------------------------------- sprites
SPR = os.path.join(HERE, "sprites")


@lru_cache(None)
def raw(name):
    return Image.open(os.path.join(SPR, name + ".png")).convert("RGBA")


def _recolor(im, rgb):
    """recolour the yellow hoodie to another colour (keeps shading)."""
    a = np.array(im).astype(np.float32)
    r, g, b = a[..., 0], a[..., 1], a[..., 2]
    mx = np.maximum(np.maximum(r, g), b); mn = np.minimum(np.minimum(r, g), b)
    sat = (mx - mn) / (mx + 1e-3)
    yellow = (sat > 0.35) & (r > 120) & (g > 80) & (b < r * 0.75) & (g > r * 0.45)
    lum = (0.5 * r + 0.5 * g)[yellow] / 235.0
    for i, c in enumerate(rgb):
        a[..., i][yellow] = np.clip(c * lum * 1.05, 0, 255)
    return Image.fromarray(a.astype(np.uint8), "RGBA")


@lru_cache(None)
def spr(name, h, flip=False, tint=None, alpha=1.0, gray=False):
    im = raw(name)
    if tint:
        im = _recolor(im, tint)
    if gray:
        rgb = im.convert("RGB"); a = im.getchannel("A")
        rgb = ImageEnhance.Color(rgb).enhance(0.0)
        rgb = Image.blend(rgb, Image.new("RGB", rgb.size, (150, 170, 210)), 0.35)
        im = rgb.convert("RGBA"); im.putalpha(a)
    s = h / im.height
    im = im.resize((max(1, round(im.width * s)), max(1, round(h))), Image.LANCZOS)
    if s > 1.5:
        im = im.filter(ImageFilter.UnsharpMask(radius=2, percent=90, threshold=2))
    if flip:
        im = im.transpose(Image.FLIP_LEFT_RIGHT)
    if alpha < 1:
        im = with_alpha(im, alpha)
    return im


def with_alpha(im, alpha):
    im = im.copy()
    im.putalpha(im.getchannel("A").point(lambda v: int(v * alpha)))
    return im


_SC = {}


def _scaled(im_id, im, s, angle):
    key = (im_id, s, angle)
    if key in _SC:
        return _SC[key][1]
    if len(_SC) > 2500:
        _SC.clear()
    src = im
    if s != 1:
        im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.BILINEAR)
    if angle:
        im = im.rotate(angle, resample=Image.BICUBIC, expand=True)
    _SC[key] = (src, im)
    return im


def paste(dst, im, cx, by, s=1.0, angle=0.0, anchor="bottom", alpha=1.0):
    """paste RGBA image with centre-x cx and bottom y by (or centre if anchor='center')."""
    if s <= 0.01 or alpha <= 0.01:
        return
    s = round(s, 2); angle = round(angle * 2) / 2
    im2 = _scaled(id(im), im, s, angle)
    if alpha < 0.99:
        im2 = with_alpha(im2, round(alpha, 2))
    x = int(cx - im2.width / 2)
    y = int(by - im2.height) if anchor == "bottom" else int(by - im2.height / 2)
    dst.paste(im2, (x, y), im2)


def char(dst, name, h, cx, by, t, t0=-9, flip=False, tint=None, sway=1.0, alpha=1.0, gray=False, seed=0):
    """a living character: pops in at t0, breathes and sways slightly."""
    s = pop(t, t0) if t0 > -9 else 1.0
    if s <= 0:
        return
    im = spr(name, h, flip, tint, 1.0, gray)
    br = 1 + 0.012 * math.sin(t * 2.6 + seed)
    ang = sway * 1.6 * math.sin(t * 1.7 + seed)
    paste(dst, im, cx, by - 3 * sway * abs(math.sin(t * 2.2 + seed)), s * br, ang, alpha=alpha)


# ---------------------------------------------------------------- vector doodles (draw on supersampled RGBA)
def canvas(w, h, bg=(0, 0, 0, 0)):
    im = Image.new("RGBA", (w * SS, h * SS), bg)
    return im, ImageDraw.Draw(im)


def done(im):
    return im.resize((im.width // SS, im.height // SS), Image.LANCZOS)


def S(*v):
    return [x * SS for x in v]


def thought_cloud(w, h, fill=(255, 255, 255), outline=INK, lw=6, seed=3, tail=True, tail_dir=-1):
    """returns RGBA thought bubble of size ~ (w, h+tail)."""
    th = int(h * 0.35) if tail else 0
    im, d = canvas(w, h + th)
    rnd = random.Random(seed)
    cx, cy, rx, ry = w / 2, h / 2, w / 2 - h * 0.16, h / 2 - h * 0.16
    n = max(8, int((rx + ry) / 38))
    bumps = []
    for i in range(n):
        a = 2 * math.pi * i / n
        r = min(rx, ry) * (0.32 + 0.08 * rnd.random())
        bumps.append((cx + rx * 0.86 * math.cos(a), cy + ry * 0.86 * math.sin(a), r))
    for x, y, r in bumps:
        d.ellipse(S(x - r - lw, y - r - lw, x + r + lw, y + r + lw), fill=outline)
    d.ellipse(S(cx - rx - lw, cy - ry - lw, cx + rx + lw, cy + ry + lw), fill=outline)
    for x, y, r in bumps:
        d.ellipse(S(x - r, y - r, x + r, y + r), fill=fill)
    d.ellipse(S(cx - rx, cy - ry, cx + rx, cy + ry), fill=fill)
    if tail:
        bx = w / 2 + tail_dir * w * 0.22
        for i, r in enumerate([h * 0.07, h * 0.045, h * 0.03]):
            x = bx + tail_dir * i * h * 0.09; y = h - h * 0.02 + i * th * 0.33 + r
            d.ellipse(S(x - r - lw, y - r - lw, x + r + lw, y + r + lw), fill=outline)
            d.ellipse(S(x - r, y - r, x + r, y + r), fill=fill)
    return done(im)


def text_img(txt, size, fill=(255, 255, 255), stroke=INK, sw=None, f=FONT, shadow=True):
    fn = font(size, f)
    sw = max(2, size // 11) if sw is None else sw
    l, t, r, b = fn.getbbox(txt, stroke_width=sw)
    pad = sw + 8
    im = Image.new("RGBA", (r - l + 2 * pad, b - t + 2 * pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if shadow:
        sh = Image.new("RGBA", im.size, (0, 0, 0, 0))
        ImageDraw.Draw(sh).text((pad - l + 4, pad - t + 6), txt, font=fn, fill=(0, 0, 0, 120), stroke_width=sw, stroke_fill=(0, 0, 0, 120))
        im = Image.alpha_composite(im, sh.filter(ImageFilter.GaussianBlur(4)))
        d = ImageDraw.Draw(im)
    d.text((pad - l, pad - t), txt, font=fn, fill=fill, stroke_width=sw if stroke else 0, stroke_fill=stroke)
    return im


@lru_cache(None)
def label(txt, size=60, fill=(255, 255, 255), stroke=INK, f=FONT, shadow=True):
    return text_img(txt, size, fill, stroke, None, f, shadow)


@lru_cache(None)
def plain(txt, size=48, fill=INK, f=FONT):
    return text_img(txt, size, fill, None, 0, f, False)


@lru_cache(None)
def tag(txt, bg, size=40, fg=(255, 255, 255)):
    t = plain(txt, size, fg)
    w, h = t.width + 36, t.height + 8
    im, d = canvas(w + 8, h + 8)
    d.rounded_rectangle(S(4, 4, w + 4, h + 4), radius=SS * h // 2, fill=bg, outline=INK, width=SS * 4)
    im = done(im)
    im.paste(t, ((im.width - t.width) // 2, (im.height - t.height) // 2 + 1), t)
    return im


@lru_cache(None)
def speech(txt, size=46, w=None, tail="left", bg=(255, 255, 255), fg=INK):
    fn = font(size)
    lines = wrap(txt, fn, w or 520)
    tw = max(fn.getlength(l) for l in lines)
    lh = int(size * 1.35)
    bw, bh = int(tw + 70), int(lh * len(lines) + 40)
    th = 46
    im, d = canvas(bw + 12, bh + th + 12)
    d.rounded_rectangle(S(6, 6, bw + 6, bh + 6), radius=SS * 34, fill=bg, outline=INK, width=SS * 5)
    if tail:
        x0 = bw * (0.22 if tail == "left" else 0.78)
        dx = -1 if tail == "left" else 1
        pts = [(x0 - 22, bh), (x0 + 22, bh), (x0 + dx * 34, bh + th)]
        d.polygon([(SS * (x + 6), SS * (y + 6)) for x, y in pts], fill=bg)
        d.line([(SS * (x + 6), SS * (y + 6)) for x, y in [pts[0], pts[2], pts[1]]], fill=INK, width=SS * 5, joint="curve")
    im = done(im)
    d = ImageDraw.Draw(im)
    for i, l in enumerate(lines):
        lw_ = fn.getlength(l)
        d.text((6 + (bw - lw_) / 2, 6 + 18 + i * lh), l, font=fn, fill=fg)
    return im


def wrap(txt, fn, maxw):
    words, lines, cur = txt.split(), [], ""
    for w_ in words:
        nxt = (cur + " " + w_).strip()
        if fn.getlength(nxt) > maxw and cur:
            lines.append(cur); cur = w_
        else:
            cur = nxt
    if cur:
        lines.append(cur)
    return lines


@lru_cache(None)
def card(w, h, bg=(255, 255, 255), lw=5, r=28):
    im, d = canvas(w + 2 * lw, h + 2 * lw)
    d.rounded_rectangle(S(lw, lw, w + lw, h + lw), radius=SS * r, fill=bg, outline=INK, width=SS * lw)
    return done(im)


# ---- icons (cached RGBA)
@lru_cache(None)
def heart(sz=120, col=RED):
    im, d = canvas(sz, sz)
    s = sz * SS
    pts = []
    for i in range(200):
        a = 2 * math.pi * i / 200
        x = 16 * math.sin(a) ** 3
        y = -(13 * math.cos(a) - 5 * math.cos(2 * a) - 2 * math.cos(3 * a) - math.cos(4 * a))
        pts.append((s / 2 + x * s / 38, s / 2 + y * s / 38 - s * 0.03))
    d.polygon(pts, fill=col, outline=INK, width=SS * 5)
    return done(im)


@lru_cache(None)
def star(sz=120, col=YEL):
    im, d = canvas(sz, sz)
    c = sz * SS / 2
    pts = [(c + c * (0.92 if i % 2 == 0 else 0.42) * math.sin(math.pi * i / 5), c - c * (0.92 if i % 2 == 0 else 0.42) * math.cos(math.pi * i / 5)) for i in range(10)]
    d.polygon(pts, fill=col, outline=INK, width=SS * 5)
    return done(im)


@lru_cache(None)
def bolt(sz=110, col=YEL):
    im, d = canvas(sz, int(sz * 1.4))
    k = sz * SS / 100
    pts = [(55, 0), (10, 75), (45, 75), (30, 140), (92, 52), (55, 52), (75, 0)]
    d.polygon([(x * k, y * k) for x, y in pts], fill=col, outline=INK, width=SS * 5)
    return done(im)


@lru_cache(None)
def qmark(sz=110, col=RED):
    return label("?", sz, col)


@lru_cache(None)
def check(sz=110, ok=True):
    im, d = canvas(sz, sz)
    s = sz * SS
    d.ellipse((SS * 4, SS * 4, s - SS * 4, s - SS * 4), fill=GREEN if ok else RED, outline=INK, width=SS * 5)
    if ok:
        d.line([(s * .27, s * .52), (s * .44, s * .69), (s * .74, s * .33)], fill="white", width=int(s * .1), joint="curve")
    else:
        d.line([(s * .3, s * .3), (s * .7, s * .7)], fill="white", width=int(s * .1))
        d.line([(s * .7, s * .3), (s * .3, s * .7)], fill="white", width=int(s * .1))
    return done(im)


@lru_cache(None)
def brain(sz=300):
    im, d = canvas(sz, int(sz * 0.8))
    s = sz * SS
    pink, dk = (246, 160, 180), (190, 90, 120)
    blobs = [(.30, .38, .22), (.52, .30, .24), (.72, .40, .21), (.30, .58, .2), (.55, .58, .24), (.75, .60, .17)]
    for x, y, r in blobs:
        d.ellipse((s * (x - r) - SS * 6, s * (y - r) - SS * 6, s * (x + r) + SS * 6, s * (y + r) + SS * 6), fill=INK)
    for x, y, r in blobs:
        d.ellipse((s * (x - r), s * (y - r), s * (x + r), s * (y + r)), fill=pink)
    for path in [[(.5, .14), (.47, .3), (.52, .45), (.48, .66)], [(.25, .4), (.36, .44), (.38, .55)],
                 [(.66, .3), (.62, .42), (.72, .5)], [(.62, .62), (.7, .7), (.8, .62)], [(.3, .66), (.38, .7)]]:
        d.line([(s * x, s * y) for x, y in path], fill=dk, width=SS * 5, joint="curve")
    return done(im)


def clock(dst, cx, cy, r, t, speed=1.0):
    im, d = canvas(2 * r + 12, 2 * r + 12)
    c = (r + 6) * SS
    d.ellipse((SS * 6, SS * 6, c * 2 - SS * 6, c * 2 - SS * 6), fill="white", outline=INK, width=SS * 7)
    for i in range(12):
        a = i * math.pi / 6
        d.line([(c + 0.78 * r * SS * math.sin(a), c - 0.78 * r * SS * math.cos(a)), (c + 0.9 * r * SS * math.sin(a), c - 0.9 * r * SS * math.cos(a))], fill=INK, width=SS * 4)
    am, ah = t * speed * 2 * math.pi, t * speed * 2 * math.pi / 12
    d.line([(c, c), (c + 0.75 * r * SS * math.sin(am), c - 0.75 * r * SS * math.cos(am))], fill=INK, width=SS * 6)
    d.line([(c, c), (c + 0.5 * r * SS * math.sin(ah), c - 0.5 * r * SS * math.cos(ah))], fill=RED, width=SS * 8)
    im = done(im)
    dst.paste(im, (int(cx - im.width / 2), int(cy - im.height / 2)), im)


# ---------------------------------------------------------------- backgrounds
def _d(im):
    return ImageDraw.Draw(im)


@lru_cache(None)
def bg(kind):
    im, d = canvas(W, H, PAPER + (255,))
    k = SS
    L = lambda *p, c=INK, w=6: d.line([(x * k, y * k) for x, y in p], fill=c, width=w * k, joint="curve")
    R = lambda x0, y0, x1, y1, f, w=6, r=0: d.rounded_rectangle((x0 * k, y0 * k, x1 * k, y1 * k), radius=r * k, fill=f, outline=INK, width=w * k)
    E = lambda x0, y0, x1, y1, f, w=6: d.ellipse((x0 * k, y0 * k, x1 * k, y1 * k), fill=f, outline=INK, width=w * k)
    if kind == "paper":
        for x in range(0, W, 60):
            d.line([(x * k, 0), (x * k, H * k)], fill=(236, 232, 222), width=2 * k)
        for y in range(0, H, 60):
            d.line([(0, y * k), (W * k, y * k)], fill=(236, 232, 222), width=2 * k)
    elif kind == "bathroom":
        d.rectangle((0, 0, W * k, 820 * k), fill=(214, 236, 242))
        for x in range(0, W, 120):
            d.line([(x * k, 0), (x * k, 820 * k)], fill=(185, 214, 222), width=3 * k)
        for y in range(0, 820, 120):
            d.line([(0, y * k), (W * k, y * k)], fill=(185, 214, 222), width=3 * k)
        d.rectangle((0, 820 * k, W * k, H * k), fill=(196, 202, 210))
        L((0, 820), (W, 820))
        # mirror + sink
        R(1180, 170, 1640, 640, (200, 226, 238), 10, 40)
        L((1240, 230), (1310, 300), c=(255, 255, 255), w=10); L((1240, 290), (1280, 330), c=(255, 255, 255), w=10)
        R(1150, 700, 1670, 760, (255, 255, 255), 8, 20)
        R(1370, 760, 1450, 1000, (255, 255, 255), 8, 0)
        L((1410, 700), (1410, 650), (1460, 650), w=10)
        R(150, 120, 330, 560, (247, 196, 26), 6, 10)  # towel
        L((150, 470), (330, 470), w=4)
    elif kind in ("office", "office2"):
        d.rectangle((0, 0, W * k, 840 * k), fill=(243, 232, 210))
        d.rectangle((0, 840 * k, W * k, H * k), fill=(196, 170, 140)); L((0, 840), (W, 840))
        R(1260, 120, 1760, 520, (190, 222, 245), 8, 6)
        for y in range(150, 520, 40):
            L((1265, y), (1755, y), c=(160, 190, 215), w=4)
        L((1510, 120), (1510, 520), w=6)
        R(120, 560, 760, 620, (170, 120, 80), 7, 6)  # desk
        R(150, 620, 190, 900, (150, 100, 60), 6, 0); R(690, 620, 730, 900, (150, 100, 60), 6, 0)
        R(300, 380, 560, 540, (60, 64, 76), 7, 10); R(318, 398, 542, 522, (120, 190, 230), 0, 4)
        R(400, 540, 460, 562, (60, 64, 76), 5, 0)
        R(1830, 560, 1900, 840, (120, 80, 50), 6, 4)  # door edge
        R(860, 700, 960, 840, (205, 90, 60), 6, 8)   # plant pot
        for a in (-40, -10, 20, 50):
            L((910, 700), (910 + a * 1.6, 560 + abs(a)), c=(60, 150, 80), w=12)
        if kind == "office2":
            pass
    elif kind == "home_night":
        d.rectangle((0, 0, W * k, 850 * k), fill=(58, 66, 110))
        d.rectangle((0, 850 * k, W * k, H * k), fill=(92, 72, 70)); L((0, 850), (W, 850))
        R(1300, 140, 1720, 520, (24, 30, 64), 9, 6); L((1510, 140), (1510, 520), w=7); L((1300, 330), (1720, 330), w=7)
        E(1560, 180, 1660, 280, (250, 236, 170), 5); d.ellipse((1590 * k, 170 * k, 1680 * k, 262 * k), fill=(24, 30, 64))
        R(200, 600, 760, 820, (180, 70, 70), 7, 30); R(170, 560, 260, 860, (160, 60, 60), 7, 20); R(700, 560, 790, 860, (160, 60, 60), 7, 20)
        L((880, 850), (880, 420), w=7); d.polygon([(820 * k, 420 * k), (940 * k, 420 * k), (910 * k, 340 * k), (850 * k, 340 * k)], fill=(250, 220, 140), outline=INK, width=6 * k)
    elif kind == "bedroom":
        d.rectangle((0, 0, W * k, 860 * k), fill=(44, 52, 96))
        d.rectangle((0, 860 * k, W * k, H * k), fill=(70, 56, 60)); L((0, 860), (W, 860))
        R(1380, 120, 1760, 460, (20, 26, 58), 9, 6); L((1570, 120), (1570, 460), w=7)
        E(1430, 170, 1520, 260, (250, 236, 170), 5); d.ellipse((1458 * k, 160 * k, 1540 * k, 244 * k), fill=(20, 26, 58))
        R(260, 640, 1260, 860, (140, 96, 70), 8, 10)   # bed frame
        R(240, 520, 300, 880, (120, 80, 56), 7, 10)
    elif kind == "stage":
        d.rectangle((0, 0, W * k, H * k), fill=(40, 26, 30))
        d.polygon([(760 * k, 0), (1160 * k, 0), (1500 * k, 900 * k), (420 * k, 900 * k)], fill=(90, 74, 60))
        d.rectangle((0, 860 * k, W * k, H * k), fill=(150, 98, 56)); L((0, 860), (W, 860))
        for x in range(0, W, 140):
            L((x, 860), (x - 60, H), c=(120, 76, 40), w=4)
        for side in (0, 1):
            for i in range(5):
                x0 = (i * 70) if side == 0 else (W - 70 - i * 70)
                R(x0, -20, x0 + 80, 880, (190, 36, 46), 5, 30)
        d.rectangle((0, 0, W * k, 90 * k), fill=(170, 30, 40)); L((0, 90), (W, 90))
    elif kind == "split":
        d.rectangle((0, 0, W // 2 * k, H * k), fill=(214, 218, 226))
        d.rectangle((W // 2 * k, 0, W * k, H * k), fill=(255, 236, 170))
        L((W // 2, 0), (W // 2, H), w=8)
    elif kind == "dark":
        d.rectangle((0, 0, W * k, H * k), fill=(20, 22, 34))
    elif kind == "storm":
        d.rectangle((0, 0, W * k, H * k), fill=(62, 58, 74))
    return done(im).convert("RGB")


@lru_cache(None)
def vignette(col=(200, 20, 20)):
    y, x = np.mgrid[0:H, 0:W]
    r = np.sqrt(((x - W / 2) / (W / 2)) ** 2 + ((y - H / 2) / (H / 2)) ** 2)
    a = np.clip((r - 0.55) / 0.6, 0, 1) * 200
    im = np.zeros((H, W, 4), np.uint8); im[..., :3] = col; im[..., 3] = a
    return Image.fromarray(im, "RGBA")


def overlay(dst, rgba, alpha):
    if alpha <= 0.01:
        return
    dst.paste(rgba, (0, 0), rgba if alpha >= 0.99 else with_alpha(rgba, round(alpha, 2)))


def camera(img, z, fx=W / 2, fy=H / 2):
    if abs(z - 1) < 1e-3:
        return img
    cw, chh = W / z, H / z
    x0 = clamp(fx - cw / 2, 0, W - cw); y0 = clamp(fy - chh / 2, 0, H - chh)
    return img.resize((W, H), Image.BILINEAR, box=(x0, y0, x0 + cw, y0 + chh))


# ---------------------------------------------------------------- captions
LATIN = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
KEY_HI = {"बहस", "दिमाग", "दिमाग़", "गुस्सा", "नाराज़", "चुप", "डरते", "ज़बरदस्त", "सवाल", "रुकना", "पूछना", "feeling", "ज़रूरी", "दिल"}


def is_key(w):
    core = w.strip("—,.?!:।\"'…")
    return (core and core[0] in LATIN) or core in KEY_HI


class Captions:
    def __init__(self, script, total, size=78, maxw=1560):
        self.size, self.lines = size, []
        fn = font(size)
        for i, (s, txt) in enumerate(script):
            e = script[i + 1][0] if i + 1 < len(script) else total
            # split on sentence ends first, then wrap
            words = txt.split()
            groups, cur = [], []
            for w_ in words:
                cur.append(w_)
                if fn.getlength(" ".join(cur)) > maxw * 0.62 or w_.endswith(("।", "?", "!", ",")) and len(cur) >= 3:
                    groups.append(cur); cur = []
            if cur:
                if groups and len(cur) <= 2:
                    groups[-1] += cur
                else:
                    groups.append(cur)
            tot = sum(len(" ".join(g)) for g in groups)
            t = s
            for g in groups:
                d = (e - s) * len(" ".join(g)) / tot
                self.lines.append((t, t + d, g)); t += d

    @lru_cache(512)
    def _img(self, idx):
        words = self.lines[idx][2]
        fn = font(self.size); sw = 9
        sp = fn.getlength(" ") + 16
        widths = [fn.getlength(w_) for w_ in words]
        tw = sum(widths) + sp * (len(words) - 1)
        l, t, r, b = fn.getbbox("कभी ऐसा बात्रूम Ag", stroke_width=sw)
        h = b - t + 30
        im = Image.new("RGBA", (int(tw + 2 * sw + 40), h + 20), (0, 0, 0, 0))
        sh = Image.new("RGBA", im.size, (0, 0, 0, 0))
        ds, d = ImageDraw.Draw(sh), ImageDraw.Draw(im)
        x = 20 + sw
        for w_, ww in zip(words, widths):
            col = YEL if is_key(w_) else (255, 255, 255)
            ds.text((x + 4, 10 - t + 7), w_, font=fn, fill=(0, 0, 0, 150), stroke_width=sw, stroke_fill=(0, 0, 0, 150))
            d.text((x, 10 - t), w_, font=fn, fill=col, stroke_width=sw, stroke_fill=(15, 15, 18))
            x += ww + sp
        sh = sh.filter(ImageFilter.GaussianBlur(5))
        return Image.alpha_composite(sh, im)

    def draw(self, dst, t, y=1000):
        for i, (s, e, _) in enumerate(self.lines):
            if s <= t < e:
                im = self._img(i)
                k = 0.7 + 0.3 * back((t - s) / 0.22)
                paste(dst, im, W / 2, y + im.height / 2 - 40, k, anchor="center")
                return
