# Metro Saathi – Delhi NCR Metro app (unofficial)

Kotlin + Jetpack Compose. **Poori tarah offline** – tunnel me bhi chalti hai.

## Features
- **Saare chalu stations (276)**: Red, Yellow, Blue (+branch), Green (+branch), Violet, Pink (ring), Magenta
  (Majlis Park–Deepali Chowk wale naye section ke saath), Grey, Airport Express, Aqua (Noida), Rapid Metro (Gurugram)
- **Aane wali lines**: Red Line extension (Rithala–Narela–Kundli), Golden Line (Aerocity–Tughlakabad),
  Magenta extensions (Krishna Park Ext.–Deepali Chowk, Majlis Park–R.K. Ashram Marg). Map par dashed line
  me dikhti hain.
- **Route planner**: sabse tez rasta aur kam line-change wala rasta, saath me samay, stations, interchange,
  "towards" disha aur agla station
- **Kiraya**: token, smart card, aur Sunday/holiday (DMRC ke 25 Aug 2025 wale slab). Ye andaaza hai.
- **Utarne ka alarm**: metro me baithte hi lagaiye, destination se ~3 minute pehle bajega
- **GPS se sabse paas ka station** (bina Google services ke chalta hai)
- **Map app kholte hi khulta hai**, 2 style me:
  - **Diagram (DMRC style)**: lines sirf seedhi aur 45° par, stations ke beech barabar doori. Ye
    `tools/schematic.py` ke layout algorithm se banta hai.
  - **Asli map**: stations apni asli location par
- **Chalu / Aane wale tabs**: dono me map aur list view. Aane wale tab me nayi lines highlight hoti hain
  aur chalu network halka (faded) dikhta hai.
- Pinch zoom, double-tap, station par tap, aur dark mode support
- **Line-wise station list**: interchanges aur km ke saath
- **Favourites aur recent routes**
- **Info**: fare chart, helpline (tap karke call), aur kaam ki tips

## Data
`tools/build_data.py` se `app/src/main/assets/metro_data.txt` banti hai:
- Chalu network ka order aur coordinates: © OpenStreetMap contributors (ODbL), via
  github.com/RKartik9/delhi-metro
- Distance coordinates se nikali gayi hai (seedhi doori × 1.12), isliye kiraya aur samay approximate hain
- Aane wali lines: PIB press release (Red extension) aur news reports. Inke coordinates andaaze se rakhe gaye hain.

⚠️ Naye Magenta section (Majlis Park–Deepali Chowk) ke station names aur order ko DMRC se verify karna baaki hai.

Ye unofficial app hai, iska DMRC se koi sambandh nahi hai.
