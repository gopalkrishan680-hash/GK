#!/usr/bin/env python3
"""Builds app/src/main/assets/metro_data.txt.

Operational network: station order + coordinates derived from OpenStreetMap route
relations (© OpenStreetMap contributors, ODbL), as exported by
github.com/RKartik9/delhi-metro (public/data/routes.json + stations.geojson).

Upcoming (Phase 4) sections are hand-entered from public announcements (PIB press
release for Rithala–Narela–Kundli; news reports for Golden & Magenta). Their
coordinates are APPROXIMATE (interpolated between known stations) and only used
to sketch them on the map.

Usage: build_data.py <path-to-delhi-metro-repo> <output-file>
"""
import json
import math
import sys

repo, out_path = sys.argv[1], sys.argv[2]
routes = json.load(open(f"{repo}/public/data/routes.json"))
geo = json.load(open(f"{repo}/public/data/stations.geojson"))
colors = json.load(open(f"{repo}/public/data/colors.json"))
coord = {f["properties"]["name"]: (f["geometry"]["coordinates"][1], f["geometry"]["coordinates"][0]) for f in geo["features"]}
layout = {f["properties"]["name"]: f["properties"].get("layout", "") for f in geo["features"]}

# Display names / ids for the operational lines.
LINES = [
    ("RED", "Red Line", "Red Line"),
    ("YELLOW", "Yellow Line", "Yellow Line"),
    ("BLUE", "Blue Line", "Blue Line"),
    ("BLUE_B", "Blue Line (Vaishali branch)", "Blue Line Branch"),
    ("GREEN", "Green Line", "Green Line"),
    ("GREEN_B", "Green Line (branch)", "Green Line Branch"),
    ("VIOLET", "Violet Line", "Violet Line"),
    ("PINK", "Pink Line", "Pink Line"),
    ("MAGENTA", "Magenta Line", "Magenta Line"),
    ("GREY", "Grey Line", "Grey Line"),
    ("AIRPORT", "Airport Express", "Airport Express"),
    ("AQUA", "Aqua Line (Noida Metro)", "Aqua Line"),
    ("RAPID", "Rapid Metro Gurugram", "Rapid Metro Gurgaon"),
]
OPERATOR_NOTE = {
    "AIRPORT": "Separate (higher) fare",
    "AQUA": "Operated by NMRC – separate ticket",
    "RAPID": "Operated separately – separate ticket",
}


def km(a, b):
    (la1, lo1), (la2, lo2) = a, b
    p = math.pi / 180
    h = math.sin((la2 - la1) * p / 2) ** 2 + math.cos(la1 * p) * math.cos(la2 * p) * math.sin((lo2 - lo1) * p / 2) ** 2
    return 2 * 6371 * math.asin(math.sqrt(h))


def interp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def fill(names, anchors):
    """Approximate coordinates for stations between known anchor stations."""
    pts = {n: anchors[n] for n in names if n in anchors}
    idx = [i for i, n in enumerate(names) if n in pts]
    res = {}
    for i, n in enumerate(names):
        if n in pts:
            res[n] = pts[n]
            continue
        lo = max([j for j in idx if j < i], default=None)
        hi = min([j for j in idx if j > i], default=None)
        if lo is not None and hi is not None:
            res[n] = interp(pts[names[lo]], pts[names[hi]], (i - lo) / (hi - lo))
        elif lo is not None:  # extrapolate past the last anchor
            prev = [j for j in idx if j < lo]
            step = ((pts[names[lo]][0] - pts[names[prev[-1]]][0]) / (lo - prev[-1]),
                    (pts[names[lo]][1] - pts[names[prev[-1]]][1]) / (lo - prev[-1])) if prev else (0.008, 0)
            k = i - lo
            res[n] = (pts[names[lo]][0] + step[0] * k, pts[names[lo]][1] + step[1] * k)
    return res


out = ["# Metro Saathi data. Station order/coords © OpenStreetMap contributors (ODbL).",
       "# L|id|name|color|status|note   S|name|lat|lon|kmFromStart|layout   W|a|b|walkMin   C|line|a|b|km (extra link)"]

for lid, name, key in LINES:
    st = routes[key]
    out.append(f"L|{lid}|{name}|{colors[key]}|op|{OPERATOR_NOTE.get(lid, '')}")
    dist = 0.0
    for i, s in enumerate(st):
        if i:
            dist += km(coord[st[i - 1]], coord[s]) * 1.12  # track is a bit longer than straight line
        la, lo = coord[s]
        out.append(f"S|{s}|{la:.6f}|{lo:.6f}|{dist:.2f}|{layout.get(s, '')}")

# Newly opened Magenta Phase-4 section (Majlis Park – Deepali Chowk, opened Mar 2026 per reports).
NEW_MAGENTA = ["Majlis Park", "Bhalswa", "Haiderpur Badli Mor", "North Pitampura", "Prashant Vihar",
               "Madhuban Chowk", "Deepali Chowk"]
c = fill(NEW_MAGENTA, coord)
out.append(f"L|MAGENTA_N|Magenta Line (Majlis Park–Deepali Chowk)|{colors['Magenta Line']}|op|"
           "New Phase-4 section (2026) – station names/order unverified, please check DMRC")
dist = 0.0
for i, s in enumerate(NEW_MAGENTA):
    if i:
        dist += km(c[NEW_MAGENTA[i - 1]], c[s]) * 1.12
    out.append(f"S|{s}|{c[s][0]:.6f}|{c[s][1]:.6f}|{dist:.2f}|")

UPCOMING = [
    ("UP_RED", "Red Line extension (Rithala–Narela–Kundli)", colors["Red Line"],
     "Approved Dec 2024 (PIB). Under construction.",
     ["Rithala", "Rohini Sector 25", "Rohini Sector 26", "Rohini Sector 31", "Rohini Sector 32",
      "Rohini Sector 36", "Barwala", "Rohini Sector 35", "Rohini Sector 34",
      "Bawana Industrial Area-1 Sector 3,4", "Bawana Industrial Area-1 Sector 1,2", "Bawana JJ Colony",
      "Sanoth", "New Sanoth", "Depot Station", "Bhorgarh Village", "Anaj Mandi Narela",
      "Narela DDA Sports Complex", "Narela", "Narela Sector 5", "Kundli", "Nathpur"],
     {"Narela": (28.8530, 77.0930), "Nathpur": (28.8880, 77.1150)}),
    ("UP_GOLDEN", "Golden Line (Aerocity–Tughlakabad)", "#C9A227",
     "Phase 4, under construction. First section expected end-2026 (dates often change).",
     ["Delhi Aerocity", "Mahipalpur", "Vasant Kunj", "Kishangarh", "Chhatarpur", "Chhatarpur Mandir",
      "IGNOU", "Neb Sarai", "Saket G Block", "Ambedkar Nagar", "Khanpur", "Sangam Vihar - Tigri",
      "Anandmayee Marg Junction", "Tughlakabad Railway Colony", "Tughlakabad"],
     {}),
    ("UP_MAGENTA_W", "Magenta Line extension (Krishna Park Ext.–Deepali Chowk)", colors["Magenta Line"],
     "Under construction, expected late 2026.",
     ["Krishna Park Extension", "Keshopur", "Paschim Vihar", "Peeragarhi", "Mangolpuri", "West Enclave",
      "Pushpanjali", "Deepali Chowk"],
     {}),
    ("UP_MAGENTA_E", "Magenta Line extension (Majlis Park–R.K. Ashram Marg)", colors["Magenta Line"],
     "Under construction (partly underground), expected 2027.",
     ["Majlis Park", "Azadpur", "Ashok Vihar", "Derawal Nagar", "Ghanta Ghar", "Pul Bangash",
      "Sadar Bazar", "Nabi Karim", "Ramakrishna Ashram Marg"],
     {}),
]
known = dict(coord)
known.update(c)
for lid, name, color, note, stations, extra in UPCOMING:
    anchors = dict(known)
    anchors.update(extra)
    cc = fill(stations, anchors)
    out.append(f"L|{lid}|{name}|{color}|up|{note}")
    for s in stations:
        la, lo = cc[s]
        out.append(f"S|{s}|{la:.6f}|{lo:.6f}||")
    for s in stations:
        known.setdefault(s, cc[s])

# Pink Line became a ring in 2026: Yamuna Vihar connects back to Maujpur - Babarpur.
a, b = "Yamuna Vihar", "Maujpur - Babarpur"
out.append(f"C|PINK|{a}|{b}|{km(coord[a], coord[b]) * 1.12:.2f}")

# Walking interchanges between differently named stations.
out += [
    "W|New Delhi|New Delhi Airport Express Terminal|8",
    "W|Dhaula Kuan|Durgabai Deshmukh South Campus|12",
    "W|Noida Sector 52|Noida Sector 51|8",
]
missing = [w for w in out if w.startswith("W|") and any(x not in coord for x in w.split("|")[1:3])]
assert not missing, missing
open(out_path, "w").write("\n".join(out) + "\n")
print("wrote", out_path, sum(1 for l in out if l.startswith("S|")), "station rows")
