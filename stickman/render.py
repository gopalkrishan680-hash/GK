"""Render the stickman explainer video for the 'imaginary conversations' narration.

usage: python3 render.py AUDIO.wav OUT.mp4 [--preview SECONDS ...] [--jobs 4]
"""
import json, math, os, subprocess, sys, random
from multiprocessing import Pool
from kit import *

SCRIPT = json.load(open(os.path.join(HERE, "script.json")))
INTRO = 2.0          # black-screen opening line before the narration starts
AUDIO_LEN = 338.93
OUTRO = 4.0
TOTAL = INTRO + AUDIO_LEN + OUTRO

BOSS = (52, 74, 128)      # boss: navy suit
FRIEND = (90, 170, 110)   # friend: green hoodie


def T(phrase, nth=0):
    """audio time at which `phrase` is spoken (interpolated inside the timed chunk)."""
    hits = 0
    for i, (s, txt) in enumerate(SCRIPT):
        e = SCRIPT[i + 1][0] if i + 1 < len(SCRIPT) else AUDIO_LEN
        j = txt.find(phrase)
        while j >= 0:
            if hits == nth:
                return s + (e - s) * j / len(txt)
            hits += 1
            j = txt.find(phrase, j + 1)
    raise KeyError(phrase)


CAP = Captions(SCRIPT, AUDIO_LEN)


def bump(t, t0):
    """small squash when a pose changes"""
    x = t - t0
    return 1 + (0.07 * math.exp(-x * 9) * math.cos(x * 30) if x >= 0 else 0)


def pose(seq, t):
    """seq = [(t0, name), ...] -> (name, switch_time)"""
    cur = seq[0]
    for p in seq:
        if t >= p[0]:
            cur = p
    return cur[1], cur[0]


def actor(img, seq, h, cx, by, t, flip=False, tint=None, seed=0, alpha=1.0, gray=False, t0=-9):
    name, ts = pose(seq, t)
    s = bump(t, ts) * (pop(t, t0) if t0 > -9 else 1)
    if s <= 0:
        return
    im = spr(name, h, flip, tint, 1.0, gray)
    br = 1 + 0.012 * math.sin(t * 2.6 + seed)
    paste(img, im, cx, by - 3 * abs(math.sin(t * 2.2 + seed)), s * br, 1.5 * math.sin(t * 1.7 + seed), alpha=alpha)


def walk(img, x, by, h, t, flip):
    k = int(t * 12) % 8
    paste(img, spr(f"walk{k}", h, flip), x, by)


def floaty(img, im, cx, cy, t, t0, amp=8, seed=0, alpha=1.0, anchor="center"):
    s = pop(t, t0)
    if s > 0:
        paste(img, im, cx + amp * 0.5 * math.sin(t * 1.3 + seed), cy + amp * math.sin(t * 2.0 + seed), s, 2 * math.sin(t * 1.1 + seed), anchor=anchor, alpha=alpha)


def ghost_marks(img, cx, cy, t, n=3, seed=0):
    for i in range(n):
        ph = (t * 0.6 + i / n + seed) % 1
        paste(img, qmark(70 + 10 * i, RED), cx + 120 * math.sin(i * 2.1 + seed), cy - 160 * ph, 0.6 + 0.5 * ph, 20 * math.sin(t * 3 + i), anchor="center", alpha=1 - ph)


def sparkle(img, t, n=14, seed=1, col=(255, 255, 255), area=(0, 0, W, 900)):
    d = ImageDraw.Draw(img)
    rnd = random.Random(seed)
    for i in range(n):
        x = rnd.uniform(area[0], area[2]); y = rnd.uniform(area[1], area[3]); ph = rnd.random() * 6
        r = 3 + 3 * (0.5 + 0.5 * math.sin(t * 3 + ph))
        d.ellipse((x - r, y - r, x + r, y + r), fill=col)


def cloud_with(img, cx, cy, w, h, t, t0, seed=3, tail_dir=-1, inner=None, alpha=1.0):
    s = pop(t, t0, 0.45)
    if s <= 0:
        return
    c = thought_cloud(w, h, seed=seed, tail_dir=tail_dir)
    paste(img, c, cx, cy + c.height / 2 - h / 2, s * (1 + 0.01 * math.sin(t * 2)), anchor="center", alpha=alpha)
    if inner and s > 0.85:
        inner(img)


def bubble(img, txt, cx, by, t, t0, t1=None, size=46, w=None, tail="left", bg=(255, 255, 255)):
    if t < t0 or (t1 is not None and t > t1):
        return
    a = 1 - fade(t, t1 - 0.25, 0.25) if t1 else 1
    paste(img, speech(txt, size, w, tail, bg), cx, by, pop(t, t0) * (1 + 0.012 * math.sin(t * 3)), alpha=a)


def bolts(img, cx, cy, t, t0, n=3, r=140):
    if t < t0:
        return
    for i in range(n):
        a = i * 2 * math.pi / n + t * 0.8
        s = pop(t, t0 + i * 0.1) * (0.8 + 0.2 * math.sin(t * 9 + i))
        paste(img, bolt(70), cx + r * math.cos(a), cy + r * 0.6 * math.sin(a), s, 15 * math.sin(t * 6 + i), anchor="center")


def title(img, txt, cx, cy, t, t0, size=110, col=(255, 255, 255), wob=True):
    s = pop(t, t0, 0.4)
    if s > 0:
        paste(img, label(txt, size, col), cx, cy, s * (1 + (0.015 * math.sin(t * 3) if wob else 0)), anchor="center")


# =================================================================== scenes
SCENES = []


def scene(a, b, zoom=(1.0, 1.06), focus=(W / 2, H / 2)):
    def deco(fn):
        SCENES.append(dict(a=a, b=b, fn=fn, zoom=zoom, focus=focus))
        return fn
    return deco


# --- 1. bathroom argument -------------------------------------------------
@scene(0, T("लेकिन सबसे funny"), zoom=(1.0, 1.08), focus=(900, 520))
def s_bathroom(img, t):
    img.paste(bg("bathroom"))
    t_quiet = T("सामने वाला बिल्कुल चुप")
    gs = 1.0 - 0.25 * ease((t - t_quiet) / 0.6)
    gimg = spr("p_cross", 440, True, None, 1.0, True)
    paste(img, gimg, 1060, 860 - 2 * math.sin(t * 2), gs, 1.2 * math.sin(t * 1.4), alpha=0.75)
    actor(img, [(0, "p_explain"), (T("ज़बरदस्त"), "p_point_side"), (t_quiet, "p_cross"), (T("यार!"), "p_thumbs")], 470, 560, 870, t)
    bubble(img, "और आपको क्या लगता है?!", 1060, 380, t, 0.6, T("ज़बरदस्त") - 0.1, 44)
    bubble(img, "Point नंबर 1… Point नंबर 2!", 520, 360, t, T("ज़बरदस्त"), t_quiet, 46, tail="left")
    bolts(img, 820, 470, t, T("perfect counter"))
    if t > T("ऐसी line"):
        bubble(img, "BOOM!", 560, 360, t, T("ऐसी line"), T("यार!") - 0.1, 70)
    if t > t_quiet:
        paste(img, label("…", 90, (220, 230, 245)), 1060, 470 + 40 * (1 - gs) * 4, pop(t, t_quiet + 0.4), anchor="center")
    bubble(img, "काश… उस वक़्त ये बोला होता!", 560, 350, t, T("ये line मुझे"), None, 46)


# --- 2. it was all in your head ------------------------------------------
@scene(T("लेकिन सबसे funny"), 30.17, zoom=(1.12, 1.0), focus=(900, 500))
def s_reveal(img, t):
    img.paste(bg("bathroom"))
    t0 = T("लेकिन सबसे funny"); t_head = T("सिर्फ़ तुम्हारे दिमाग"); t_none = T("ना सामने कोई")
    # ghost drifts from the floor into a thought cloud above the hero
    k = ease((t - t_head) / 1.0)
    ga = 0.75 * (1 - ease((t - t_none) / 1.2))
    actor(img, [(t0, "p_shrug"), (t_head, "p_think"), (T("emotions"), "p_question")], 470, 640, 870, t)
    if t > t_head - 0.1:
        cloud_with(img, 980, 250, 620, 360, t, t_head, seed=5, tail_dir=-1)
    gx, gy, gh = lerp(1180, 980, k), lerp(860, 380, k), lerp(440, 230, k)
    paste(img, spr("p_cross", int(gh), True, None, 1.0, True), gx, gy, alpha=ga)
    if t > t_none:
        title(img, "कोई था ही नहीं!", 1000, 250, t, t_none + 0.8, 70, YEL)
    if t > T("emotions"):
        a = 0.5 + 0.3 * math.sin(t * 5)
        overlay(img, vignette((220, 40, 40)), a * fade(t, T("emotions")))
        floaty(img, heart(110), 360, 330, t, T("emotions") + 0.2, seed=1)
        floaty(img, bolt(80), 900, 520, t, T("emotions") + 0.4, seed=2)
        title(img, "Emotions = REAL", 640, 150, t, T("real हो"), 80, YEL)
    if t < t_head:
        title(img, "funny बात?", 640, 200, t, t0 + 0.2, 80, YEL)


# --- 3. why? ---------------------------------------------------------------
@scene(30.17, 40.94, zoom=(1.0, 1.05))
def s_why(img, t):
    img.paste(bg("paper"))
    title(img, "क्यों?", 960, 150, t, 30.3, 150, YEL)
    actor(img, [(30.17, "p_question"), (35.9, "p_think")], 520, 520, 900, t, t0=30.2)
    ghost_marks(img, 560, 330, t)
    for i, (cx, t0) in enumerate([(1080, 31.3), (1500, 32.6)]):
        def inner(im, cx=cx, i=i):
            paste(im, spr("turn_fl" if i else "turn_fr", 210, i == 0, None, 1.0, True), cx, 610, alpha=0.8)
        cloud_with(img, cx, 470, 380, 330, t, t0, seed=7 + i, inner=inner)
    if t > 35.9:
        paste(img, tag("REHEARSAL", RED, 46), 1290, 800, pop(t, T("rehearse")), 3 * math.sin(t * 2), anchor="center")
        paste(img, tag("जो कभी होगी ही नहीं", (90, 90, 110), 40), 1290, 880, pop(t, T("शायद ज़िंदगी")), anchor="center")


# --- 4. office: boss says something --------------------------------------
@scene(40.94, T("फिर घर पहुँचते"), zoom=(1.0, 1.07), focus=(900, 560))
def s_office(img, t):
    img.paste(bg("office"))
    tw = T("वहाँ से चले")
    actor(img, [(40.94, "p_point_side")], 480, 1380, 880, t, flip=True, tint=BOSS, seed=2)
    paste(img, tag("BOSS", BOSS, 38), 1380, 330, pop(t, 41.3), anchor="center")
    bubble(img, "ये क्या काम किया है तुमने?!", 1330, 300, t, T("कुछ ऐसा कह"), tw, 44, tail="right")
    if t < tw:
        actor(img, [(40.94, "turn_fr"), (T("smile"), "p_stand")], 470, 760, 880, t, seed=1)
        if t > T("लेकिन उस वक़्त"):
            paste(img, label("…", 90), 760, 330, pop(t, T("लेकिन उस वक़्त")), anchor="center")
        if t > T("smile"):
            paste(img, tag("smile :)", GREEN, 36), 760, 300, pop(t, T("smile")), anchor="center")
    else:
        x = 760 - (t - tw) * 330
        walk(img, x, 880, 430, t, True)


# --- 5. home: the brain starts a new scene --------------------------------
@scene(T("फिर घर पहुँचते"), T("लेकिन अगले दिन"), zoom=(1.0, 1.1), focus=(1100, 450))
def s_home(img, t):
    img.paste(bg("home_night"))
    sparkle(img, t, 10, 2, (250, 240, 200), (1310, 150, 1710, 510))
    t0 = T("फिर घर पहुँचते"); tscene = T("नया scene")
    actor(img, [(t0, "p_think"), (T("Boss तुम्हारे"), "p_idea")], 470, 380, 900, t)

    def inner(im):
        boss_h = 330 - 120 * ease((t - T("एक-एक करके")) / 2.5)
        paste(im, spr("p_point_side", int(boss_h), True, BOSS), 1450, 700 - 2 * math.sin(t * 9))
        if t > T("एक-एक करके"):
            paste(im, label("!!", 60, (120, 190, 255)), 1460, 700 - boss_h - 30, pop(t, T("एक-एक करके") + 0.5), anchor="center")
        actor(im, [(tscene, "p_point_side"), (T("बिल्कुल नहीं डरते"), "p_cross")], 360, 1050, 720, t, seed=3)
        bubble(im, "सुनिए Sir, अब मेरी बात सुनिए!", 1080, 330, t, T("confidently"), T("एक-एक करके"), 40)
        bolts(im, 1250, 470, t, T("एक-एक करके"), 4, 150)
    cloud_with(img, 1200, 440, 1060, 600, t, tscene, seed=11, inner=inner)
    if t > T("बिल्कुल नहीं डरते"):
        paste(img, tag("डर? ज़ीरो!", RED, 44), 1250, 860, pop(t, T("बिल्कुल नहीं डरते")), anchor="center")


# --- 6. next day: OK sir ----------------------------------------------------
@scene(T("लेकिन अगले दिन"), T("और रात को"), zoom=(1.0, 1.12), focus=(900, 520))
def s_oksir(img, t):
    img.paste(bg("office"))
    t0 = T("लेकिन अगले दिन")
    title(img, "अगले दिन…", 960, 140, t, t0, 80, YEL)
    actor(img, [(t0, "p_point_side")], 520, 1300, 880, t, flip=True, tint=BOSS, seed=2)
    paste(img, tag("BOSS", BOSS, 38), 1300, 310, pop(t, t0 + 0.2), anchor="center")
    actor(img, [(t0, "turn_fr"), (68.6, "p_thumbs")], 400, 720, 880, t, seed=1)
    bubble(img, "OK sir…", 760, 420, t, 68.83, None, 56)
    d = ImageDraw.Draw(img)  # sweat drop
    if t > t0 + 1:
        y = 520 + ((t * 60) % 60)
        d.ellipse((790, y, 812, y + 30), fill=(120, 190, 255), outline=INK, width=3)


# --- 7. night: season 2 ------------------------------------------------------
@scene(T("और रात को"), T("Psychology में"), zoom=(1.0, 1.08), focus=(800, 500))
def s_season2(img, t):
    img.paste(bg("bedroom"))
    sparkle(img, t, 8, 4, (250, 240, 200), (1390, 130, 1750, 450))
    t0 = T("और रात को")
    paste(img, spr("a_sleep", 300), 700, 740 + 3 * math.sin(t * 2))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((300, 700, 1240, 860), radius=30, fill=(120, 150, 210), outline=INK, width=6)

    def inner(im):
        c = card(420, 170, (30, 30, 36))
        paste(im, c, 1080, 330, 1 + 0.03 * math.sin(t * 4), anchor="center")
        paste(im, label("SEASON 2", 72, RED), 1080, 330, pop(t, T("season 2")), anchor="center")
    cloud_with(img, 1080, 330, 620, 380, t, t0 + 0.4, seed=13, inner=inner)


# --- 8. psychology: imagined conversations ---------------------------------
@scene(T("Psychology में"), 92.2, zoom=(1.0, 1.06))
def s_psych(img, t):
    img.paste(bg("paper"))
    t0 = T("Psychology में")
    paste(img, tag("PSYCHOLOGY", BLUE, 46), 960, 90, pop(t, t0), anchor="center")
    if t < 80.44:
        actor(img, [(t0, "p_present")], 500, 420, 900, t, t0=t0)
        c = card(900, 440)
        paste(img, c, 1240, 470, pop(t, t0 + 0.2), anchor="center")
        title(img, "Imagined Conversations", 1240, 380, t, T("imagined conversations"), 64, YEL)
        title(img, "Mental Simulation", 1240, 540, t, T("mental simulation"), 64, (120, 200, 255))
    else:
        floaty(img, brain(330), 960, 330, t, 80.5, seed=1)
        if t < 85.23:
            title(img, "जो सच में हो रहा है", 960, 620, t, 81.5, 64)
        items = [("जो हो चुका", "PAST", T("पहले हो चुकी"), (150, 120, 220)),
                 ("जो हो सकता है", "FUTURE", T("future में"), BLUE),
                 ("जो कभी नहीं होगा", "NEVER", T("या जो कभी"), RED)]
        for i, (hi, en, ts, col) in enumerate(items):
            x = 420 + i * 540
            s = pop(t, ts)
            if s > 0:
                paste(img, card(420, 220), x, 720, s, anchor="center")
                paste(img, tag(en, col, 44), x, 655, s, anchor="center")
                paste(img, plain(hi, 48), x, 750, s, anchor="center")


# --- 9. the brain prepares --------------------------------------------------
@scene(92.2, T("जैसे कोई actor"), zoom=(1.0, 1.06))
def s_prepare(img, t):
    img.paste(bg("paper"))
    floaty(img, brain(300), 560, 360, t, 92.3, seed=2)
    if t < 96.91:
        for i in range(4):
            a = t * 1.2 + i * math.pi / 2
            paste(img, qmark(80), 560 + 230 * math.cos(a), 360 + 150 * math.sin(a), pop(t, T("uncertainty") + 0.1 * i), anchor="center")
        paste(img, tag("UNCERTAINTY", (90, 90, 110), 44), 560, 640, pop(t, T("uncertainty")), anchor="center")
    else:
        paste(img, tag("PREPARE MODE", GREEN, 48), 560, 640, pop(t, 96.95), anchor="center")
    actor(img, [(92.2, "p_point_up"), (T("difficult conversation"), "p_think")], 470, 1480, 900, t, t0=92.4)

    def inner(im):
        paste(im, spr("turn_fr", 200), 1020, 380)
        paste(im, spr("turn_fl", 200, False, None, 1.0, True), 1240, 380, alpha=0.8)
        paste(im, label("?!", 50, RED), 1130, 190, 1 + 0.1 * math.sin(t * 6), anchor="center")
    if t > T("difficult conversation"):
        cloud_with(img, 1130, 260, 520, 320, t, T("difficult conversation"), seed=17, tail_dir=1, inner=inner)


# --- 10. the actor rehearses ------------------------------------------------
@scene(T("जैसे कोई actor"), 121.19, zoom=(1.0, 1.1), focus=(960, 480))
def s_stage(img, t):
    img.paste(bg("stage"))
    t0 = T("जैसे कोई actor"); tdiff = 113.96
    overlay(img, spot(), 0.9)
    actor(img, [(t0, "p_explain"), (T("dialogues practice"), "p_present")], 480, 960, 900, t, t0=t0)
    bubble(img, "To be… or not to be?", 1260, 380, t, T("dialogues practice"), tdiff, 44)
    if t > tdiff:
        paste(img, tag("ACTOR: rehearsal STOP ✓".replace(" ✓", ""), GREEN, 42), 960, 170, pop(t, tdiff + 0.2), anchor="center")
    if t > T("लेकिन हमारे दिमाग"):
        tb = T("लेकिन हमारे दिमाग")
        floaty(img, brain(220), 1520, 420, t, tb, seed=3)
        n = 1 + int((t - tb) * 4)
        paste(img, tag(f"TAKE {n}", RED, 44), 1520, 600, pop(t, tb + 0.2), 4 * math.sin(t * 8), anchor="center")


@lru_cache(None)
def spot():
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.polygon([(860, 0), (1060, 0), (1300, 900), (620, 900)], fill=(255, 240, 180, 70))
    d.ellipse((620, 860, 1300, 940), fill=(255, 240, 180, 90))
    return im.filter(ImageFilter.GaussianBlur(18))


# --- 11. imagination vs real life -----------------------------------------
@scene(121.19, 140.66, zoom=(1.0, 1.04))
def s_split(img, t):
    img.paste(bg("split"))
    paste(img, tag("REAL LIFE", (100, 100, 115), 50), 480, 90, pop(t, 121.4), anchor="center")
    paste(img, tag("IMAGINATION", (225, 150, 0), 50), 1440, 90, pop(t, T("imaginary conversations")), anchor="center")
    actor(img, [(121.19, "turn_front"), (T("insult"), "p_stand")], 430, 480, 880, t, seed=1, t0=121.3)
    actor(img, [(121.19, "p_cross"), (T("comeback"), "p_thumbs")], 470, 1440, 880, t, seed=2, t0=T("imaginary conversations"))
    paste(img, star(110), 1440, 340 - 8 * math.sin(t * 3), pop(t, T("ज़्यादा intelligent")), 8 * math.sin(t * 2), anchor="center")
    for i, (w_, ph) in enumerate([("Intelligent", "intelligent"), ("Confident", "confident और"), ("Fearless", "fearless")]):
        paste(img, tag(w_, [BLUE, GREEN, RED][i], 40), 1120, 470 + i * 130, pop(t, T(ph)), 3 * math.sin(t * 2 + i), anchor="center")
    if t > T("insult"):
        bubble(img, "…", 520, 420, t, T("तुम चुप रह गए"), None, 70)
        bubble(img, "Mic drop!", 1380, 400, t, T("comeback"), None, 54)
        bolts(img, 1440, 600, t, T("comeback"), 3, 200)
        if t > T("comeback"):
            paste(img, spr("p_cross", 300, True, None, 1.0, True), 1780, 880, alpha=0.7)
            ghost_marks(img, 1780, 520, t, 3, 2)


# --- 12. control: writer, director, main character -------------------------
@scene(140.66, 159.27, zoom=(1.0, 1.06))
def s_control(img, t):
    img.paste(bg("paper"))
    title(img, "CONTROL", 960, 120, t, 140.8, 110, YEL)
    tr = T("Real life में दूसरे")
    if t < tr:
        actor(img, [(140.66, "p_present")], 480, 460, 900, t, t0=140.8)
        for i, (txt, ph) in enumerate([("सामने वाला क्या बोलेगा", "क्या बोलेगा"), ("तुम्हारा जवाब", "क्या जवाब"), ("The End कैसा होगा", "end कैसे")]):
            s = pop(t, T(ph))
            if s > 0:
                paste(img, card(720, 110), 1260, 330 + i * 170, s, anchor="center")
                paste(img, plain(txt, 50), 1260, 330 + i * 170, s, anchor="center")
                paste(img, check(70), 960, 330 + i * 170, s, anchor="center")
    else:
        tw = T("writer"); td = T("director"); tm = T("main character")
        if t < tw:
            actor(img, [(tr, "p_shrug")], 460, 960, 900, t)
            paste(img, tag("UNPREDICTABLE", RED, 50), 960, 300, pop(t, T("unpredictable")), 4 * math.sin(t * 5), anchor="center")
            ghost_marks(img, 960, 420, t, 4)
        for i, (ts, name, lab, extra) in enumerate([(tw, "a_write", "WRITER", None), (td, "p_point_side", "DIRECTOR", None), (tm, "p_thumbs", "MAIN CHARACTER", "star")]):
            s = pop(t, ts)
            if s > 0:
                x = 400 + i * 560
                paste(img, card(460, 600, (255, 250, 230)), x, 560, s, anchor="center")
                paste(img, spr(name, 330 if name != "a_write" else 250), x, 760, s * (1 + 0.015 * math.sin(t * 3 + i)))
                paste(img, tag(lab, [BLUE, RED, (225, 150, 0)][i], 42), x, 820, s, anchor="center")
                if extra:
                    paste(img, star(90), x + 150, 330, s, 10 * math.sin(t * 3), anchor="center")
                if i == 1:
                    clapper(img, x + 140, 360, t, s)


def clapper(img, cx, cy, t, s=1.0):
    if s <= 0:
        return
    im, d = canvas(150, 130)
    k = SS
    d.rectangle((10 * k, 45 * k, 140 * k, 125 * k), fill=(40, 40, 46), outline=INK, width=4 * k)
    ang = 0.35 * abs(math.sin(t * 3))
    pts = [(10, 40), (140, 40 - 130 * math.sin(ang)), (140, 20 - 130 * math.sin(ang)), (10, 20)]
    d.polygon([(x * k, y * k) for x, y in pts], fill="white", outline=INK, width=4 * k)
    paste(img, done(im), cx, cy, s, anchor="center")


# --- 13. night before the interview ----------------------------------------
@scene(159.27, 190.06, zoom=(1.0, 1.08), focus=(960, 450))
def s_night(img, t):
    tbed = T("तुम रात को bed")
    if t < tbed:
        img.paste(bg("paper"))
        title(img, "कल…", 960, 140, t, 159.4, 110, YEL)
        actor(img, [(159.27, "p_think"), (T("impress"), "p_thumbs")], 460, 960, 900, t, t0=159.4)
        for i, (txt, ph, col) in enumerate([("Job Interview", "job interview", BLUE), ("Important Meeting", "important meeting", GREEN), ("Special Person", "special person", RED)]):
            x = [400, 1520, 400][i]; y = [380, 430, 700][i]
            paste(img, tag(txt, col, 46), x, y, pop(t, T(ph)), 3 * math.sin(t * 2 + i), anchor="center")
        if t > T("special person"):
            floaty(img, heart(110), 1520, 650, t, T("special person") + 0.3, seed=3)
        return
    img.paste(bg("bedroom"))
    sparkle(img, t, 8, 4, (250, 240, 200), (1390, 130, 1750, 450))
    paste(img, spr("a_relax", 360), 700, 760 + 2 * math.sin(t * 2))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((520, 720, 1240, 860), radius=30, fill=(120, 150, 210), outline=INK, width=6)
    tfull = T("देखते ही देखते")
    if t < tfull:
        qs = [("अगर वो ये पूछेगा तो?", "अगर वो ये", (380, 330)), ("गलत मतलब निकाल लिया तो?", "गलत मतलब", (1000, 230)), ("कुछ stupid बोल गया तो?", "stupid", (1500, 420))]
        for i, (txt, ph, (x, y)) in enumerate(qs):
            s = pop(t, T(ph))
            if s > 0:
                paste(img, thought_cloud(560, 230, seed=20 + i, tail_dir=1 if x < 960 else -1), x, y, s, anchor="center")
                paste(img, plain(txt, 40), x, y - 30, s, anchor="center")
    else:
        def inner(im):
            tg, tb = T("ending बहुत अच्छा"), T("embarrass")
            if t < tb:
                paste(im, spr("b_happy" if t > tg else "b_explain", 300), 1180, 520)
                if t > tg:
                    paste(im, check(110), 1420, 260, pop(t, tg), anchor="center")
            else:
                paste(im, spr("b_worried", 300), 1180, 520, bump(t, tb))
                paste(im, check(110, False), 1420, 260, pop(t, tb), anchor="center")
                paste(im, label("उफ़्फ़!", 60, RED), 940, 260, pop(t, tb + 0.2), anchor="center")
        cloud_with(img, 1180, 330, 760, 460, t, tfull, seed=31, tail_dir=-1, inner=inner)


# --- 14. possible outcomes ----------------------------------------------------
@scene(190.06, T("लेकिन problem"), zoom=(1.0, 1.05))
def s_outcomes(img, t):
    img.paste(bg("paper"))
    paste(img, tag("POSSIBLE OUTCOMES", BLUE, 50), 960, 90, pop(t, 190.1), anchor="center")
    floaty(img, spr("e_thinking", 260), 330, 520, t, 190.1, 6)
    d = ImageDraw.Draw(img)
    outs = [("b_happy", 230, GREEN), ("b_curious", 520, YEL), ("b_worried", 810, RED)]
    torg = T("thoughts organize")
    for i, (n, y, col) in enumerate(outs):
        k = ease((t - 190.4 - 0.5 * i) / 0.6)
        if k > 0:
            x1 = lerp(470, 900, k)
            d.line([(470, 520), (x1, lerp(520, y, k))], fill=INK, width=8)
            if k >= 1:
                paste(img, spr(n, 200), 1000, y + 100, pop(t, 190.4 + 0.5 * i + 0.6) * (1 + 0.02 * math.sin(t * 3 + i)))
    if t > T("useful"):
        c = card(560, 420, (255, 250, 230))
        paste(img, c, 1560, 520, pop(t, T("useful")), anchor="center")
        paste(img, tag("USEFUL ✓".replace(" ✓", ""), GREEN, 46), 1560, 340, pop(t, T("useful")), anchor="center")
        for i, txt in enumerate(["Thoughts organize", "Prepare for talk", "Less panic"]):
            ts = torg + 0.5 * i if i < 2 else T("prepare हो सकते")
            s = pop(t, ts)
            if s > 0:
                paste(img, check(56), 1340, 450 + i * 100, s, anchor="center")
                paste(img, plain(txt, 42), 1590, 450 + i * 100, s, anchor="center")


# --- 15. overthinking spiral ------------------------------------------------
@scene(T("लेकिन problem"), 220.79, zoom=(1.0, 1.12), focus=(960, 520))
def s_overthink(img, t):
    img.paste(bg("storm"))
    t0 = T("लेकिन problem")
    tang = T("गुस्सा भी")
    title(img, "OVERTHINKING", 960, 110, t, T("overthinking"), 100, RED)
    vs = [("Version 1", T("एक conversation imagine"), (420, 330)), ("Version 2", T("दूसरा version"), (1500, 330)),
          ("Version 3", T("तीसरा"), (400, 640)), ("WORST CASE", T("worst case"), (1520, 650))]
    for i, (txt, ts, (x, y)) in enumerate(vs):
        s = pop(t, ts)
        if s > 0:
            jit = (3 * math.sin(t * 13 + i) if t > T("conflict") else 0)
            paste(img, thought_cloud(440, 230, seed=40 + i, tail_dir=1 if x < 960 else -1), x + jit, y, s, anchor="center")
            paste(img, label(txt, 48 if i < 3 else 54, RED if i == 3 else (255, 255, 255)), x + jit, y - 25, s, anchor="center")
    if t < tang:
        actor(img, [(t0, "p_stand"), (T("एक conversation imagine"), "p_think"), (T("worst case"), "p_question")], 470, 960, 900, t)
    else:
        paste(img, spr("e_angry", 400), 960, 870 - 4 * abs(math.sin(t * 10)), bump(t, tang))
        overlay(img, vignette((230, 30, 30)), (0.6 + 0.3 * math.sin(t * 6)) * fade(t, tang))
        for i in range(2):
            ph = (t * 1.5 + i * 0.5) % 1
            paste(img, label("~", 90, (240, 240, 240)), 860 + 200 * i, 440 - ph * 120, 0.6 + ph, anchor="center", alpha=1 - ph)
    if t > T("conflict"):
        bolts(img, 960, 470, t, T("conflict"), 4, 300)
        paste(img, tag("CONFLICT", RED, 48), 960, 230, pop(t, T("conflict")), 5 * math.sin(t * 7), anchor="center")


# --- 16. imagined → real emotions ------------------------------------------
@scene(220.79, 235.6, zoom=(1.0, 1.06))
def s_emotion(img, t):
    img.paste(bg("paper"))
    tw = 227.72
    if t < tw:
        floaty(img, brain(330), 430, 450, t, 220.9, seed=1)
        paste(img, tag("IMAGINE", BLUE, 46), 430, 680, pop(t, T("vividly")), anchor="center")
        k = ease((t - T("तो उससे")) / 0.7)
        if k > 0:
            d = ImageDraw.Draw(img)
            x1 = lerp(720, 1180, k)
            d.line([(720, 450), (x1, 450)], fill=INK, width=14)
            if k >= 1:
                d.polygon([(1180, 410), (1250, 450), (1180, 490)], fill=INK)
        floaty(img, heart(160), 1480, 380, t, T("real emotional"), seed=2)
        floaty(img, bolt(100), 1640, 520, t, T("real emotional") + 0.3, seed=3)
        paste(img, tag("REAL EMOTIONS", RED, 50), 1500, 680, pop(t, T("trigger")), anchor="center")
    else:
        actor(img, [(tw, "p_cross")], 480, 620, 900, t, t0=tw)
        paste(img, spr("e_angry", 150), 620, 330, pop(t, T("नाराज़")) * 0)  # (kept for timing symmetry)
        if t > T("नाराज़"):
            bolts(img, 620, 360, t, T("नाराज़"), 3, 130)
        actor(img, [(tw, "p_shrug")], 460, 1330, 900, t, gray=True, alpha=0.75, seed=4, t0=tw + 0.3)
        bubble(img, "मैंने क्या किया?!", 1330, 400, t, T("सिर्फ़ इसलिए"), None, 46, tail="right")
        paste(img, tag("कभी मिले ही नहीं!", (90, 90, 110), 42), 960, 150, pop(t, T("बिना मिले")), anchor="center")


# --- 17. not only arguments ---------------------------------------------------
@scene(235.6, 255.05, zoom=(1.0, 1.05))
def s_kinds(img, t):
    img.paste(bg("paper"))
    paste(img, tag("सिर्फ़ arguments नहीं…", BLUE, 50), 960, 80, pop(t, 235.7), anchor="center")
    if t < T("success") + 0.3:
        actor(img, [(235.6, "p_open")], 480, 960, 880, t, alpha=1 - fade(t, T("success"), 0.3))
    panels = [(T("success"), "SUCCESS", ["b_celebrate"], "star"), (T("पुराने दोस्त"), "पुराना दोस्त", ["b_explain", "friend"], None),
              (T("दिल की बात"), "जो अब life में नहीं", ["b_sad", "gone"], "heart"), (T("future self"), "FUTURE SELF", ["b_happy", "future"], None)]
    for i, (ts, lab, who, icon) in enumerate(panels):
        s = pop(t, ts)
        if s <= 0:
            continue
        x, y = 500 + (i % 2) * 920, 340 + (i // 2) * 400
        paste(img, card(820, 340, (255, 250, 230)), x, y, s, anchor="center")
        paste(img, spr(who[0], 250), x - 180, y + 150, s * (1 + 0.015 * math.sin(t * 3 + i)))
        if len(who) > 1:
            if who[1] == "friend":
                paste(img, spr("b_happy", 230, True, FRIEND), x + 170, y + 150, s)
            elif who[1] == "gone":
                paste(img, spr("b_happy", 230, True, None, 1.0, True), x + 170, y + 150, s, alpha=0.35 + 0.15 * math.sin(t * 2))
            elif who[1] == "future":
                paste(img, spr("b_explain", 230, True, (150, 120, 220)), x + 170, y + 150, s)
        paste(img, tag(lab, [(225, 150, 0), GREEN, RED, (150, 120, 220)][i], 38), x, y - 150, s, anchor="center")
        if icon == "star":
            paste(img, star(110), x + 200, y - 20, s, 10 * math.sin(t * 2), anchor="center")
        if icon == "heart":
            paste(img, heart(70), x, y - 20, s * (1 + 0.1 * math.sin(t * 5)), anchor="center")


# --- 18. wishes, fears, regrets ------------------------------------------------
@scene(255.05, 267.3, zoom=(1.08, 1.0))
def s_feelings(img, t):
    img.paste(bg("dark"))
    sparkle(img, t, 30, 9, (90, 100, 150), (0, 0, W, H))
    actor(img, [(255.05, "p_think")], 460, 960, 900, t)
    words = [("Wishes", "wishes", (520, 260), YEL), ("Fears", "fears", (1420, 260), (120, 160, 255)),
             ("Regrets", "regrets", (430, 520), RED), ("Unresolved emotions", "unresolved", (1450, 520), (200, 150, 255))]
    for i, (w_, ph, (x, y), col) in enumerate(words):
        ts = 255.06 if ph == "wishes" else T(ph)
        floaty(img, label(w_, 70, col), x, y, t, ts, seed=i)
    if t > T("कभी हम वो कहना"):
        bubble(img, "जो कह नहीं पाए…", 520, 820, t, T("कभी हम वो कहना"), None, 44)
        bubble(img, "जो सुनना चाहते थे…", 1420, 820, t, T("कभी वो सुनना"), None, 44, tail="right")


# --- 19. should we stop? -------------------------------------------------------
@scene(267.3, 279.18, zoom=(1.0, 1.06))
def s_stop(img, t):
    img.paste(bg("paper"))
    title(img, "बंद कर दें?", 960, 150, t, 267.4, 110, YEL)
    tn = T("ज़रूरी नहीं")
    actor(img, [(267.3, "p_question"), (tn, "p_shrug"), (T("normal हिस्सा"), "p_thumbs")], 480, 960, 900, t, t0=267.4)
    if t < tn:
        paste(img, check(150, False), 1450, 450, pop(t, 268.5) * (1 + 0.05 * math.sin(t * 6)), anchor="center")
    else:
        paste(img, tag("ज़रूरी नहीं!", GREEN, 54), 1460, 380, pop(t, tn), anchor="center")
        paste(img, tag("Human thinking = NORMAL", BLUE, 44), 1460, 520, pop(t, T("human thinking")), anchor="center")
    if t > T("एक simple difference"):
        paste(img, tag("1 SIMPLE DIFFERENCE", RED, 50), 460, 400, pop(t, T("एक simple difference")), 3 * math.sin(t * 3), anchor="center")


# --- 20. prepare vs trap ----------------------------------------------------------
@scene(279.18, 300.41, zoom=(1.0, 1.05))
def s_compare(img, t):
    img.paste(bg("split"))
    paste(img, tag("PREPARE ✓".replace(" ✓", ""), GREEN, 54), 480, 90, pop(t, T("prepare कर रही")), anchor="center")
    paste(img, tag("TRAP", RED, 54), 1440, 90, pop(t, T("फँसा रही")), anchor="center")
    paste(img, check(90), 680, 90, pop(t, T("prepare कर रही") + 0.2), anchor="center")
    paste(img, check(90, False), 1610, 90, pop(t, T("फँसा रही") + 0.2), anchor="center")
    tm = T("important meeting")
    if t > tm:
        paste(img, spr("b_laptop", 300), 480, 700, pop(t, tm) * (1 + 0.015 * math.sin(t * 3)))
        for i, txt in enumerate(["Point 1", "Point 2", "Point 3"]):
            paste(img, tag(txt, BLUE, 36), 300 + i * 180, 300, pop(t, tm + 0.4 + 0.3 * i), anchor="center")
        paste(img, tag("USEFUL", GREEN, 44), 480, 790, pop(t, T("useful")), anchor="center")
    th = T("तीन घंटे")
    if t > th:
        clock(img, 1240, 360, 110, t, 3.0)
        paste(img, tag("3 घंटे!", RED, 44), 1240, 520, pop(t, th), anchor="center")
        paste(img, spr("e_tired", 300), 1620, 700 + 3 * math.sin(t * 2), pop(t, th + 0.3))
        paste(img, tag("जो हुआ ही नहीं", (90, 90, 110), 40), 1560, 790, pop(t, T("जो हुआ ही नहीं")), anchor="center")
    tr = T("सिर्फ़ imagination")
    if t > tr:
        overlay(img, dim(), 0.85 * fade(t, tr))
        title(img, "Imagination ≠ Reality", 960, 500, t, tr, 110, YEL)


@lru_cache(None)
def dim():
    return Image.new("RGBA", (W, H), (20, 20, 30, 210))


# --- 21. pause and ask ---------------------------------------------------------
@scene(300.41, 317.09, zoom=(1.0, 1.08), focus=(800, 500))
def s_pause(img, t):
    img.paste(bg("bathroom"))
    tp = T("रुकना")
    frozen = t > tp
    tt = min(t, tp)
    paste(img, spr("p_cross", 440, True, None, 1.0, True), 1060, 860, alpha=0.75 * (1 - fade(t, T("मैं इस conversation"), 0.8)))
    actor(img, [(300.41, "p_point_side"), (tp, "p_think"), (T("feeling"), "p_idea")], 470, 560, 870, t if frozen else tt)
    if not frozen:
        bolts(img, 820, 470, t, 301.0)
    else:
        a = fade(t, tp, 0.2)
        d = ImageDraw.Draw(img)
        if t < tp + 2.5:
            d.rounded_rectangle((900, 300, 960, 520), 12, fill=(255, 255, 255), outline=INK, width=6)
            d.rounded_rectangle((1000, 300, 1060, 520), 12, fill=(255, 255, 255), outline=INK, width=6)
    tq = T("मैं इस conversation")
    if t > tq and t < 310.19:
        c = card(860, 170, (255, 250, 230))
        paste(img, c, 1240, 380, pop(t, tq), anchor="center")
        paste(img, plain("मैं actually चाहता क्या हूँ?", 56), 1240, 380, pop(t, tq), anchor="center")
    if t > 310.19:
        paste(img, tag("Closure?", BLUE, 50), 1150, 300, pop(t, 310.2), anchor="center")
        paste(img, tag("वो जवाब?", (150, 120, 220), 50), 1520, 300, pop(t, T("या बस वो जवाब")), anchor="center")
        if t > T("realize"):
            paste(img, tag("असली ज़रूरत: अपनी FEELING समझना", RED, 44), 1250, 480, pop(t, T("feeling")), anchor="center")
            floaty(img, heart(130), 1250, 640, t, T("feeling"), seed=4)


# --- 22. last question ----------------------------------------------------------
@scene(317.09 + 1.9, AUDIO_LEN, zoom=(1.0, 1.08))
def s_last(img, t):
    tlast = T("आप के साथ")
    if t < tlast - 0.3:
        img.paste(bg("paper"))
        title(img, "LAST QUESTION", 960, 110, t, T("last question"), 100, YEL)
        title(img, "सबसे लंबी conversation… किसके साथ?", 960, 250, t, T("सबसे लंबी"), 64)
        people = [("दोस्त", T("दोस्त के साथ"), dict(tint=FRIEND)), ("Boss", T("boss के साथ"), dict(tint=BOSS)),
                  ("जिससे कुछ कह नहीं पाए", T("कुछ कह नहीं पाए"), dict(gray=True))]
        if t < T("दोस्त के साथ") + 0.3:
            actor(img, [(317.09, "p_question")], 480, 960, 900, t, alpha=1 - fade(t, T("दोस्त के साथ"), 0.3))
            ghost_marks(img, 960, 420, t)
        for i, (lab, ts, kw) in enumerate(people):
            x = 400 + i * 560
            s = pop(t, ts)
            if s > 0:
                paste(img, spr("turn_front", 380, False, kw.get("tint"), 1.0, kw.get("gray", False)), x, 830, s * (1 + 0.012 * math.sin(t * 2.6 + i)), alpha=0.6 if kw.get("gray") else 1)
                paste(img, tag(lab, kw.get("tint") or (100, 100, 115), 40), x, 880, s, anchor="center")
        return
    img.paste(bg("dark"))
    sparkle(img, t, 40, 12, (110, 120, 180), (0, 0, W, H))
    # the hero facing himself
    actor(img, [(tlast, "turn_right")], 460, 700, 900, t, seed=1)
    actor(img, [(tlast, "turn_right")], 460, 1220, 900, t, flip=True, seed=1, alpha=0.55, gray=True)
    paste(img, tag("खुद से?", (225, 150, 0), 54), 960, 200, pop(t, tlast), anchor="center")
    if t > T("सबसे interesting"):
        cloud_with(img, 960, 380, 520, 260, t, T("सबसे interesting"), seed=51)
        paste(img, heart(90), 960, 370, pop(t, T("सबसे interesting") + 0.4) * (1 + 0.08 * math.sin(t * 4)), anchor="center")


# --- 21b. "feeling" → last question bridge (317.09–319) handled by s_pause extension
SCENES[-2]["b"] = 317.09 + 1.9


# =================================================================== frame
def frame(G):
    """render global time G (seconds) -> RGB image"""
    if G < INTRO:
        img = Image.new("RGB", (W, H), (8, 8, 10))
        a = win(G, 0.1, INTRO - 0.05, 0.4)
        paste(img, label("ये बहस… कभी हुई ही नहीं थी।", 84, (255, 255, 255), INK, FONT2), W / 2, H / 2, 0.97 + 0.03 * G / INTRO, anchor="center", alpha=a)
        return img
    t = G - INTRO
    if t >= AUDIO_LEN:
        img = Image.new("RGB", (W, H), (8, 8, 10))
        x = t - AUDIO_LEN
        sparkle(img, t, 40, 12, (70, 80, 130), (0, 0, W, H))
        paste(img, label("दिमाग में बहस", 190, YEL), W / 2, H / 2 - 60, pop(x, 0.2, 0.5) * (1 + 0.01 * x), anchor="center")
        paste(img, label("Imaginary Conversations", 70, (255, 255, 255)), W / 2, H / 2 + 110, pop(x, 0.7), anchor="center")
        return img
    sc = SCENES[0]
    for s in SCENES:
        if s["a"] <= t:
            sc = s
    img = Image.new("RGB", (W, H), PAPER)
    sc["fn"](img, t)
    k = clamp((t - sc["a"]) / max(0.1, sc["b"] - sc["a"]))
    z = lerp(sc["zoom"][0], sc["zoom"][1], ease(k))
    img = camera(img, z, *sc["focus"])
    # quick fade-in from paper at scene starts (not at the very first frame)
    if sc["a"] > 0 and t - sc["a"] < 0.18:
        img = Image.blend(Image.new("RGB", (W, H), (255, 255, 255)), img, (t - sc["a"]) / 0.18)
    CAP.draw(img, t)
    return img


def render_range(args):
    i0, i1, path = args
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
                          "-i", "-", "-c:v", "libx264", "-preset", "veryfast", "-crf", "19", "-pix_fmt", "yuv420p", path], stdin=subprocess.PIPE)
    for i in range(i0, i1):
        p.stdin.write(frame(i / FPS).tobytes())
    p.stdin.close(); p.wait()
    return path


def main():
    audio, out = sys.argv[1], sys.argv[2]
    args = sys.argv[3:]
    if args and args[0] == "--preview":
        for s in args[1:]:
            frame(float(s)).save(out.replace(".png", f"_{s}.png"))
        return
    jobs = 4
    n = int(TOTAL * FPS)
    tmp = out + ".parts"; os.makedirs(tmp, exist_ok=True)
    nseg = jobs * 3
    bounds = [n * k // nseg for k in range(nseg + 1)]
    parts = [(bounds[k], bounds[k + 1], f"{tmp}/p{k:02d}.mp4") for k in range(nseg)]
    with Pool(jobs) as pool:
        for p in pool.imap_unordered(render_range, parts):
            print("done", p, flush=True)
    with open(f"{tmp}/list.txt", "w") as f:
        for _, _, p in parts:
            f.write(f"file '{os.path.abspath(p)}'\n")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", f"{tmp}/list.txt", "-i", audio,
                    "-filter_complex", f"[1:a]adelay={int(INTRO*1000)}:all=1,apad[a]", "-map", "0:v", "-map", "[a]",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out], check=True)
    print("wrote", out)


if __name__ == "__main__":
    main()
