"""Cut character poses out of the two character-sheet images into transparent PNG sprites."""
import sys, json, numpy as np, cv2
from PIL import Image

S1, S2, OUT = sys.argv[1], sys.argv[2], sys.argv[3]

def mids(cs, lo, hi):
    b = [lo] + [(a + c) // 2 for a, c in zip(cs, cs[1:])] + [hi]
    return list(zip(b, b[1:]))

boxes = {}
def row(src, names, centers, y0, y1, lo, hi):
    for n, (a, b) in zip(names, mids(centers, lo, hi)):
        if n: boxes[n] = (src, a, y0, b, y1)

# ---- sheet 1 ----
row(1, ["turn_front","turn_fl","turn_left","turn_bl","turn_back","turn_br","turn_right","turn_fr"],
    [440,550,645,745,840,940,1032,1125], 36, 200, 385, 1172)
row(1, ["face_front","face_l","face_side","face_r"], [1237,1320,1405,1488], 62, 156, 1192, 1530)
row(1, ["e_neutral","e_happy","e_bigsmile","e_laugh","e_excited","e_surprised","e_shocked","e_curious","e_thinking","e_confused"],
    [72,182,295,407,517,630,740,852,963,1075], 322, 412, 15, 1135)
row(1, ["e_doubtful","e_worried","e_anxious","e_sad","e_disappointed","e_tired","e_bored","e_focused","e_determined","e_angry"],
    [72,182,295,407,517,630,740,852,963,1075], 446, 535, 15, 1135)
row(1, ["p_stand","p_explain","p_present","p_point_up","p_point_side","p_think","p_question","p_idea","p_cross","p_open","p_shrug","p_thumbs"],
    [73,195,330,458,580,703,820,942,1068,1190,1325,1457], 598, 768, 12, 1525)
row(1, ["a_walk","a_run","a_climb","a_jump","a_chair","a_floor","a_laptop","a_read","a_write","a_phone","a_relax","a_sleep"],
    [67,153,240,345,430,512,600,685,768,852,938,1018], 860, 962, 12, 1056)
row(1, [f"walk{i}" for i in range(8)], [1103,1163,1218,1272,1326,1380,1435,1489], 868, 975, 1075, 1520)
boxes["hero"] = (1, 12, 12, 200, 318)
# ---- sheet 2 ----
row(2, ["b_happy","b_thinking","b_curious","b_surprised","b_excited","b_confused","b_worried","b_sad"],
    [121,318,497,680,867,1059,1237,1422], 352, 498, 15, 1525)
row(2, ["b_explain","b_point_up","b_present","b_book","b_laptop","b_idea","b_write","b_celebrate"],
    [106,304,493,677,861,1039,1243,1438], 560, 727, 15, 1525)
row(2, ["s_sit_talk","s_sit_think","s_laptop","s_read","s_relax","s_walk"],
    [101,264,425,590,755,913], 790, 966, 15, 990)

ims = {1: np.array(Image.open(S1).convert("RGB")), 2: np.array(Image.open(S2).convert("RGB"))}

def cut(src, x0, y0, x1, y1, keep_frac=0.12):
    a = ims[src][y0:y1, x0:x1].copy()
    h, w = a.shape[:2]
    mn = a.min(axis=2); mx = a.max(axis=2)
    bg_like = (mn > 225) & ((mx.astype(int) - mn) < 25)          # near-white paper
    # background = near-white reachable from crop border
    lab_n, lab = cv2.connectedComponents(bg_like.astype(np.uint8), connectivity=4)
    border = set(np.unique(np.concatenate([lab[0], lab[-1], lab[:, 0], lab[:, -1]])))
    bg = np.isin(lab, [l for l in border if l != 0]) & bg_like
    fg = (~bg).astype(np.uint8)
    # drop small components (labels, neighbours' fragments, sparkles)
    n, cl, st, _ = cv2.connectedComponentsWithStats(fg, connectivity=8)
    if n <= 1: return None
    areas = st[1:, cv2.CC_STAT_AREA]; big = areas.max()
    keep = [i + 1 for i, ar in enumerate(areas) if ar >= keep_frac * big]
    # also drop components touching left/right crop edge that are not the biggest (neighbour figures)
    bi = int(np.argmax(areas)) + 1
    k2 = []
    for i in keep:
        x, y, ww, hh, _ = st[i]
        if i != bi and (x == 0 or x + ww >= w): continue
        k2.append(i)
    m = np.isin(cl, k2)
    alpha = cv2.GaussianBlur(m.astype(np.float32), (3, 3), 0)
    ys, xs = np.where(m)
    yA, yB, xA, xB = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
    rgba = np.dstack([a, (alpha * 255).astype(np.uint8)])[yA:yB, xA:xB]
    return Image.fromarray(rgba, "RGBA")

meta = {}
for name, (src, x0, y0, x1, y1) in boxes.items():
    im = cut(src, x0, y0, x1, y1)
    if im is None: print("empty", name); continue
    im.save(f"{OUT}/{name}.png"); meta[name] = im.size
json.dump(meta, open(f"{OUT}/index.json", "w"), indent=0)
print(len(meta), "sprites")
