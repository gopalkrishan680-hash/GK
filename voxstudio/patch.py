import sys, re
src, dst = sys.argv[1], sys.argv[2]
h = open(src, encoding='utf-8').read()

def rep(old, new, count=1):
    global h
    n = h.count(old)
    if n != count:
        sys.exit(f'expected {count} got {n}: {old[:90]!r}')
    h = h.replace(old, new)

def cut_line(start):
    """remove one whole line that starts with `start`"""
    global h
    lines = h.split('\n'); idx = [i for i, l in enumerate(lines) if l.startswith(start)]
    if len(idx) != 1: sys.exit(f'line {start!r}: {len(idx)}')
    del lines[idx[0]]; h = '\n'.join(lines)

def cut_between(a, b):
    """remove from a (inclusive) up to b (exclusive)"""
    global h
    i = h.index(a); j = h.index(b, i); h = h[:i] + h[j:]

# ---------------------------------------------------------------- 1. duplicates out
# Format: only one control (in Export), the Script-tab copy goes
rep('''        <label class="inline">Format <select id="aspect"><option value="16:9">16:9 YouTube</option><option value="9:16">9:16 Shorts / Reels</option><option value="1:1">1:1 Square</option></select></label>\n''', '')
rep('<select id="aspect2">', '<select id="aspect">')
rep('''$('#aspect2').onchange = e => { S.settings.aspect = e.target.value; $('#aspect').value = e.target.value; sizePreview(); touch(); save(); };\n''', '')
# Captions: one control (Look card) + the CC button on the preview; the Export copy goes
rep('''        <div class="row"><label class="inline">Captions in this video <select id="captions2"><option value="words">On — word by word</option><option value="on">On — full subtitles</option><option value="off">Off</option></select></label></div>\n''', '')
rep('''$('#captions2').onchange = e => { S.settings.captions = e.target.value; showCaptions(); touch(); save(); redraw(); };\n''', '')
# "Extras for YouTube": frame-thumbnail = Thumbnail maker's "Use the preview frame"; Copy chapters = YouTube kit
# description (and it was broken: printed [object Object]). Keep only the SRT download, moved into Export.
cut_between('      <div class="card">\n        <h3>Extras for YouTube</h3>', '    </div>\n  </section>')
rep('''<div class="row"><button class="btn small" id="checkBtn">Check the video for problems</button></div>''',
    '''<div class="row"><button class="btn small" id="checkBtn">Check the video for problems</button><button class="btn small ghost" id="srtBtn">Download subtitles (SRT)</button></div>''')
cut_line("$('#thumbBtn').onclick")
cut_line("$('#chapBtn').onclick")
# dead first copy of ytChapters() (the later one wins)
cut_between('function ytChapters() {\n  const res = resolve(); const ch =', '\n</script>')

# ---------------------------------------------------------------- 2. simpler screens: advanced options folded away
# Pictures tab
rep('''      <div class="row wrap-row">
        <label class="inline">Picture look <select id="look"></select></label>
        <label class="inline">Gemini watermark <select id="gemMark"><option value="auto">Remove automatically</option><option value="off">Keep</option></select></label> <button class="btn ghost small" id="gemAll">Remove ✦ from all pictures</button>
        <label class="inline">Decor layers <select id="decor"><option value="on">On (old maps, pages behind · modern: circles, tape, grid)</option><option value="off">Off</option></select></label>
        <label class="inline">Pictures per AI image <select id="perImage"><option value="6">6 (3×2 sheet, cut automatically) — fastest</option><option value="4">4 (2×2 sheet, cut automatically)</option><option value="1">1 (one prompt per picture)</option></select></label>
      </div>''', '''      <div class="row wrap-row">
        <label class="inline">Picture look <select id="look"></select></label>
        <label class="inline">Pictures per AI image <select id="perImage"><option value="6">6 (3×2 sheet, cut automatically) — fastest</option><option value="4">4 (2×2 sheet, cut automatically)</option><option value="1">1 (one prompt per picture)</option></select></label>
      </div>
      <details class="more"><summary>More picture options</summary>
        <div class="row wrap-row">
          <label class="inline">Gemini watermark <select id="gemMark"><option value="auto">Remove automatically</option><option value="off">Keep</option></select></label> <button class="btn ghost small" id="gemAll">Remove ✦ from all pictures</button>
          <label class="inline">Decor layers <select id="decor"><option value="on">On (old maps, pages behind · modern: circles, tape, grid)</option><option value="off">Off</option></select></label>
        </div>
      </details>''')

# Look card: the everyday options stay, the rest go into "More look settings"
i = h.index('<label>Background <select id="theme">'); j = h.index('<label>Captions <select id="captions">')
look_adv1 = h[i:j]; h = h[:i] + h[j:]
i = h.index('<label>Caption word colour'); j = h.index('<label>Channel name (corner)')
look_adv2 = h[i:j]; h = h[:i] + h[j:]
i = h.index('<label>Video start <select id="hook">'); j = h.index('        </div>\n      </div>\n      <div class="card">\n        <h3>Sound</h3>')
look_adv3 = h[i:j]; h = h[:i] + h[j:]
rep('''        </div>
      </div>
      <div class="card">
        <h3>Sound</h3>''', '''        </div>
        <details class="more"><summary>More look settings</summary>
          <div class="form">
          ''' + look_adv1 + look_adv2 + look_adv3 + '''</div>
        </details>
      </div>
      <div class="card">
        <h3>Sound</h3>''')
# Sound card
i = h.index('<label>Effects volume'); j = h.index('<label>Music volume')
snd_adv = h[i:j]; h = h[:i] + h[j:]
i = h.index('<label>Music ducking'); j = h.index('</div>', i)
snd_adv += h[i:j]; h = h[:i] + h[j:]
rep('''        </div>
      </div>
      <div class="card">
        <h3>Export</h3>''', '''        </div>
        <details class="more"><summary>More sound settings</summary>
          <div class="form">
          ''' + snd_adv + '''</div>
        </details>
      </div>
      <div class="card">
        <h3>Export</h3>''')
rep('<p class="muted">Long + Short from the same project: export once in 16:9, switch Format to 9:16 and export again — the layout re-arranges itself.</p>',
    '<p class="muted">Long + Short from one project: export in 16:9, switch Format to 9:16 and export again — the layout re-arranges itself.</p>')

# ---------------------------------------------------------------- 3. Mythology: less gold + a real (no glow) option
rep('''<option value="myth">🕉️ Mythology / Divine (dark temple, gold frames, glowing figures, mandala, embers)</option></select></label>''',
    '''<option value="myth">🕉️ Mythology / Divine (dark temple, frames, figures, mandala)</option></select></label>
        <label class="inline" id="glowWrap" hidden>Divine glow <select id="glow"><option value="soft">✨ Soft — thoda kam gold (recommended)</option><option value="full">🔆 Full — strong gold (pehle jaisa)</option><option value="off">📷 Real look — natural colours, no glow</option></select></label>''')
rep("hook: 'off', hookText: '', depth: 'on', shortEnd: '', shortCaps: 'off'\n};", "hook: 'off', hookText: '', depth: 'on', shortEnd: '', shortCaps: 'off', glow: 'soft'\n};")
rep("const isMyth = () => S.settings.mode === 'myth';",
    """const isMyth = () => S.settings.mode === 'myth';
// divine glow strength for the mythology style: soft (default) = less gold, full = the old strong gold, off = real look
const glowK = () => !isMyth() ? 1 : ({ full: 1, soft: 0.45, off: 0 })[S.settings.glow] ?? 0.45;
const realMyth = () => isMyth() && S.settings.glow === 'off';
const glowTag = () => isMyth() ? '|g' + (S.settings.glow || 'soft') : '';""")
# caches must know the glow level
rep("const ck = key + '|' + mode + (mode === 'duo' || mode === 'spot' ? accent() : '');", "const ck = key + '|' + mode + (mode === 'duo' || mode === 'spot' ? accent() : '') + glowTag();")
rep("const ck = key + W + 'x' + H; let c = R.paper.get(ck); if (c) return c;", "const ck = key + W + 'x' + H + glowTag(); let c = R.paper.get(ck); if (c) return c;")
rep("function toned(key, mode) { const ck = key + '|tn|' + mode + (mode === 'duo' || mode === 'spot' ? accent() : '');", "function toned(key, mode) { const ck = key + '|tn|' + mode + (mode === 'duo' || mode === 'spot' ? accent() : '') + glowTag();")
rep("(mod ? '|m' : '') + (isMyth() ? '|y' : ''); let c = R.filt.get(ck);", "(mod ? '|m' : '') + (isMyth() ? '|y' + glowTag() : ''); let c = R.filt.get(ck);")
# photo colour: the warm/golden push follows the glow level (real = the picture's own colours)
rep("""    const sat = 1.18, hi = clamp((l - 150) / 105, 0, 1);
    let R2 = l + (r - l) * sat, G2 = l + (g - l) * sat, B2 = l + (b - l) * sat;
    R2 = sc(clamp(R2 * 1.05 + 6 + hi * 10, 0, 255)); G2 = sc(clamp(G2 * 1.0 + 2 + hi * 6, 0, 255)); B2 = sc(clamp(B2 * 0.86 - 2, 0, 255));""",
"""    if (gk <= 0) continue;   // real look: keep the picture's own colours
    const sat = 1 + 0.18 * gk, hi = clamp((l - 150) / 105, 0, 1);
    let R2 = l + (r - l) * sat, G2 = l + (g - l) * sat, B2 = l + (b - l) * sat;
    R2 = clamp(R2 * (1 + 0.05 * gk) + (6 + hi * 10) * gk, 0, 255); G2 = clamp(G2 + (2 + hi * 6) * gk, 0, 255); B2 = clamp(B2 * (1 - 0.14 * gk) - 2 * gk, 0, 255);
    R2 = lerp(R2, sc(R2), gk); G2 = lerp(G2, sc(G2), gk); B2 = lerp(B2, sc(B2), gk);""")
rep("function mythTone(d, mode) {\n", "function mythTone(d, mode) {\n  const gk = glowK();\n")
# cut-out figures: gold outline + glow scaled; real look = soft dark shadow, no gold
rep("""  if (isMyth()) {   // mythology: thin gold outline + warm divine glow behind the figure
    const gold = cv(cut.width, cut.height), gx = gold.getContext('2d'); gx.drawImage(cut, 0, 0); gx.globalCompositeOperation = 'source-in'; gx.fillStyle = '#f3c75f'; gx.fillRect(0, 0, gold.width, gold.height);
    x.save(); x.globalAlpha = 0.55; x.filter = `blur(${Math.round(sh * 2.4)}px)`; x.drawImage(gold, pad, pad); x.drawImage(gold, pad, pad); x.restore();
    for (let a = 0; a < 16; a++) x.drawImage(gold, pad + Math.cos(a / 16 * 6.283) * r * 0.5, pad + Math.sin(a / 16 * 6.283) * r * 0.5);""",
"""  if (isMyth() && glowK() <= 0) {   // mythology, real look: no gold, no glow, just a soft shadow
    const dark = cv(cut.width, cut.height), dx = dark.getContext('2d'); dx.drawImage(cut, 0, 0); dx.globalCompositeOperation = 'source-in'; dx.fillStyle = 'rgba(0,0,0,0.55)'; dx.fillRect(0, 0, dark.width, dark.height);
    x.save(); x.filter = `blur(${Math.round(sh * 1.3)}px)`; x.drawImage(dark, pad + sh * 0.3, pad + sh * 0.8); x.restore();
    x.drawImage(cut, pad, pad); c.pad = pad; R.filt.set(ck, c); return c;
  }
  if (isMyth()) {   // mythology: thin gold outline + warm divine glow behind the figure (strength = Divine glow)
    const gk = glowK(), gold = cv(cut.width, cut.height), gx = gold.getContext('2d'); gx.drawImage(cut, 0, 0); gx.globalCompositeOperation = 'source-in'; gx.fillStyle = gk >= 1 ? '#f3c75f' : '#e8d3a2'; gx.fillRect(0, 0, gold.width, gold.height);
    x.save(); x.globalAlpha = 0.55 * gk; x.filter = `blur(${Math.round(sh * (1.2 + 1.2 * gk))}px)`; x.drawImage(gold, pad, pad); if (gk >= 1) x.drawImage(gold, pad, pad); x.restore();
    x.save(); x.globalAlpha = 0.45 + 0.55 * gk; for (let a = 0; a < 16; a++) x.drawImage(gold, pad + Math.cos(a / 16 * 6.283) * r * 0.5, pad + Math.sin(a / 16 * 6.283) * r * 0.5); x.restore();""")
# entrance bloom
old = h[h.index("  if (tr.bloom > 0.02) {"):]; old = old[:old.index('\n')]
new = old.replace("if (tr.bloom > 0.02) {", "const bl = (tr.bloom || 0) * glowK(); if (bl > 0.02) {").replace('tr.bloom', 'bl').replace("const bl = (bl || 0)", "const bl = (tr.bloom || 0)")
rep(old, new)
# temple background: warm centre light softer
rep("gl.addColorStop(0, GL[0]); gl.addColorStop(0.45, GL[1]); gl.addColorStop(1, 'rgba(0,0,0,0)'); x.fillStyle = gl; x.fillRect(0, 0, W, H);",
    "gl.addColorStop(0, GL[0]); gl.addColorStop(0.45, GL[1]); gl.addColorStop(1, 'rgba(0,0,0,0)'); x.save(); x.globalAlpha = 0.45 + 0.55 * glowK(); x.fillStyle = gl; x.fillRect(0, 0, W, H); x.restore();")
# mandala / lotus / mist / light rays
rep("function drawMythBack(ctx, W, H, gt, s) {\n  const T = theme(),", "function drawMythBack(ctx, W, H, gt, s) {\n  const gk = glowK(), T = theme(),")
rep("ctx.save(); ctx.globalAlpha = cos ? 0.08 : 0.11; ctx.translate(side", "ctx.save(); ctx.globalAlpha = (cos ? 0.08 : 0.11) * (0.4 + 0.6 * gk); ctx.translate(side")
rep("ctx.save(); ctx.globalAlpha = 0.1; ctx.translate(W / 2, H * 1.02);", "ctx.save(); ctx.globalAlpha = 0.1 * (0.4 + 0.6 * gk); ctx.translate(W / 2, H * 1.02);")
rep("g.addColorStop(0, `rgba(${ec},0.07)`);", "g.addColorStop(0, `rgba(${ec},${0.07 * (0.4 + 0.6 * gk)})`);")
rep("  if (P && !P.rays) return;\n", "  if ((P && !P.rays) || gk <= 0) return;\n")
rep("al = 0.035 + 0.025 * Math.sin(gt * 0.4 + k * 1.7), L =", "al = (0.035 + 0.025 * Math.sin(gt * 0.4 + k * 1.7)) * gk, L =")
# embers / stars / dust in front: fainter; real look keeps only falling petals
rep("function drawMythFront(ctx, W, H, gt, s) {   // glowing embers / diya sparks drifting up",
    """function drawMythFront(ctx, W, H, gt, s) {   // strength follows Divine glow
  const gk = glowK(), P = stylePack(), kind = P ? P.particles : 'embers'; if (gk <= 0 && kind !== 'petals') return;
  ctx.save(); ctx.globalAlpha *= 0.45 + 0.55 * gk; drawMythFront0(ctx, W, H, gt, s); ctx.restore();
}
function drawMythFront0(ctx, W, H, gt, s) {   // glowing embers / diya sparks drifting up""")
# gold titles
rep("""  const g = ctx.createLinearGradient(0, -bh / 2, 0, bh / 2); g.addColorStop(0, '#fff1c2'); g.addColorStop(0.5, '#f2c35a'); g.addColorStop(1, '#b97a1f');
  shown.forEach((l, i) => { const yy = -bh / 2 + lh * (i + 0.5); ctx.shadowColor = 'rgba(0,0,0,0.85)'; ctx.shadowBlur = 18 * s; ctx.lineWidth = size * 0.1; ctx.strokeStyle = 'rgba(40,16,4,0.9)'; ctx.strokeText(l, 0, yy); ctx.shadowColor = 'rgba(255,170,60,0.6)'; ctx.shadowBlur = 22 * s;""",
"""  const gk = glowK(), G3 = gk >= 1 ? ['#fff1c2', '#f2c35a', '#b97a1f'] : gk > 0 ? ['#fff6df', '#ecd08e', '#bf9550'] : ['#ffffff', '#f4efe6', '#ddd5c8'];
  const OL = gk >= 1 ? 'rgba(242,195,90,0.9)' : gk > 0 ? 'rgba(236,208,142,0.85)' : 'rgba(244,239,230,0.85)', OD = gk >= 1 ? '#f2c35a' : gk > 0 ? '#ecd08e' : '#f4efe6';
  const g = ctx.createLinearGradient(0, -bh / 2, 0, bh / 2); g.addColorStop(0, G3[0]); g.addColorStop(0.5, G3[1]); g.addColorStop(1, G3[2]);
  shown.forEach((l, i) => { const yy = -bh / 2 + lh * (i + 0.5); ctx.shadowColor = 'rgba(0,0,0,0.85)'; ctx.shadowBlur = 18 * s; ctx.lineWidth = size * 0.1; ctx.strokeStyle = 'rgba(40,16,4,0.9)'; ctx.strokeText(l, 0, yy); ctx.shadowColor = `rgba(255,170,60,${0.6 * gk})`; ctx.shadowBlur = 22 * s * gk;""")
rep("if (q > 0) { ctx.strokeStyle = 'rgba(242,195,90,0.9)';", "if (q > 0) { ctx.strokeStyle = OL;")
rep("ctx.rotate(Math.PI / 4); ctx.fillStyle = '#f2c35a'; const d = 7 * s * q;", "ctx.rotate(Math.PI / 4); ctx.fillStyle = OD; const d = 7 * s * q;")
# frames
rep("""  ctx.save(); ctx.shadowColor = 'rgba(255,170,60,0.55)'; ctx.shadowBlur = 34 * s; ctx.fillStyle = '#1c0d04'; path(); ctx.fill(); ctx.restore();
  const g = ctx.createLinearGradient(x, y, x + w, y + h); g.addColorStop(0, '#f8dc8c'); g.addColorStop(0.5, '#b07a22'); g.addColorStop(1, '#ffe8a6');""",
"""  const gk = glowK(), C3 = gk >= 1 ? ['#f8dc8c', '#b07a22', '#ffe8a6'] : gk > 0 ? ['#ecd7a4', '#9c7a45', '#f3e3bd'] : ['#d9d2c6', '#8c857a', '#ece6dc'];
  ctx.save(); ctx.shadowColor = gk > 0 ? `rgba(255,170,60,${0.55 * gk})` : 'rgba(0,0,0,0.5)'; ctx.shadowBlur = (gk > 0 ? 34 * gk : 18) * s; ctx.fillStyle = '#1c0d04'; path(); ctx.fill(); ctx.restore();
  const g = ctx.createLinearGradient(x, y, x + w, y + h); g.addColorStop(0, C3[0]); g.addColorStop(0.5, C3[1]); g.addColorStop(1, C3[2]);""")
rep("""  const g = ctx.createLinearGradient(x - o, y - o, x + w + o, y + h + o); g.addColorStop(0, '#7a4a10'); g.addColorStop(0.22, '#f8dc8c'); g.addColorStop(0.45, '#a8701c'); g.addColorStop(0.7, '#ffe8a6'); g.addColorStop(1, '#6e420e');
  ctx.save(); ctx.shadowColor = 'rgba(255,170,60,0.45)'; ctx.shadowBlur = 40 * s;""",
"""  const gk = glowK(), F5 = gk >= 1 ? ['#7a4a10', '#f8dc8c', '#a8701c', '#ffe8a6', '#6e420e'] : gk > 0 ? ['#6a4a22', '#e3cd98', '#94703a', '#efdfb6', '#5e4120'] : ['#2e241c', '#6d5a48', '#3b2e24', '#7a6754', '#261d16'];
  const g = ctx.createLinearGradient(x - o, y - o, x + w + o, y + h + o); F5.forEach((c, i) => g.addColorStop([0, 0.22, 0.45, 0.7, 1][i], c));
  ctx.save(); ctx.shadowColor = gk > 0 ? `rgba(255,170,60,${0.45 * gk})` : 'rgba(0,0,0,0.55)'; ctx.shadowBlur = (gk > 0 ? 40 * gk : 20) * s;""")
rep("ctx.rotate(Math.PI / 4); ctx.fillStyle = '#ffe7a0'; ctx.fillRect(-j, -j, j * 2, j * 2); ctx.fillStyle = '#b3261e';",
    "ctx.rotate(Math.PI / 4); ctx.fillStyle = gk >= 1 ? '#ffe7a0' : gk > 0 ? '#efdcae' : '#8a7764'; ctx.fillRect(-j, -j, j * 2, j * 2); ctx.fillStyle = gk > 0 ? '#b3261e' : '#4a3a2c';")
# light-burst transition
rep("ctx.drawImage(p < 0.5 ? A : B, 0, 0); const k = Math.sin(p * Math.PI);", "ctx.drawImage(p < 0.5 ? A : B, 0, 0); const k = Math.sin(p * Math.PI) * (0.3 + 0.7 * glowK());")
# picture prompts: softer "mythic" text by default, a new real-look style
rep("""  mythic: { name: '🕉️ Mythic painting (colour, divine light)', text: 'Style: cinematic Indian mythology digital painting, epic and devotional, rich jewel colours (deep red, saffron, gold, peacock blue, emerald), soft golden divine rim light, glowing aura around gods, dramatic sky, ornate gold jewellery and silk clothes, highly detailed, painterly, in the classical Indian art tradition.' },""",
"""  mythic: { name: '🕉️ Mythic painting (colour, divine light)', get text() { return glowK() >= 1 ? 'Style: cinematic Indian mythology digital painting, epic and devotional, rich jewel colours (deep red, saffron, gold, peacock blue, emerald), soft golden divine rim light, glowing aura around gods, dramatic sky, ornate gold jewellery and silk clothes, highly detailed, painterly, in the classical Indian art tradition.' : 'Style: cinematic Indian mythology digital painting, epic and devotional, balanced natural colours (deep red, saffron, peacock blue, emerald, earthy browns), realistic soft light, only a very faint aura around gods, NOT golden or yellow overall, no heavy glow or light bloom, detailed silk clothes and jewellery, highly detailed, painterly, in the classical Indian art tradition.'; } },
  mythreal: { name: '📷 Real cinematic (natural light, no glow)', text: 'Style: photorealistic cinematic film still of an Indian mythology story, like a big-budget historical movie: real actors, realistic skin, real fabric, metal and stone, natural daylight or real firelight, true-to-life colours, no glow, no halo, no aura, no golden light rays, no sparkle, no fantasy effects, sharp detail.' },""")
rep("function lookText(x) { if (x.asset && x.full && !isMod()) return 'Style: rich full-colour cinematic painting, detailed, dramatic light.';",
    "function lookText(x) { if (x.asset && x.full && !isMod()) return realMyth() ? LOOKS.mythreal.text : 'Style: rich full-colour cinematic painting, detailed, dramatic light.';")
rep("const intro = isMyth() ? MYTH_INTRO :", "const intro = isMyth() ? mythText(MYTH_INTRO) :")
rep("const assetRule = isMyth() ? MYTH_ASSETS :", "const assetRule = isMyth() ? mythText(MYTH_ASSETS) :")
rep("const MOD_INTRO = ", """// the plan request follows the Divine glow choice (real look = no glowing / golden words)
function mythText(t) {
  if (!realMyth()) return t;
  return t.replace('with a slowly turning golden mandala and soft light rays', 'with a faint carved pattern')
    .replace('characters as glowing cut-out figures with a golden aura, scenes in ornate gold frames — plus short gold titles', 'characters as realistic cut-out figures, scenes in simple dark frames — plus short clean titles')
    .replace('with a flash of divine light, smoke or a spinning mandala', 'with a soft fade, smoke or a push')
    .replace('shown glowing on the dark background', 'shown on the dark background').replace('shown in a gold frame', 'shown in a simple frame')
    .replace('dignified, calm, glowing; never comic', 'dignified, calm, realistic; never comic');
}
const MOD_INTRO = """)
rep("  if (isMyth()) return 'the same look as the video: dark warm temple background with a glowing golden mandala,",
    "  if (realMyth()) return 'the same look as the video: dark warm background, a realistic cinematic photo of the main character in natural light (no glow, no aura, not golden), bold clean Hindi title text with a dark shadow';\n  if (isMyth()) return 'the same look as the video: dark warm temple background with a glowing golden mandala,")
# mode defaults + the control
rep("if (m === 'myth') Object.assign(S.settings, { theme: 'temple', photo: 'myth', look: 'mythic', captions: 'words', capColor: '#ffd36b',",
    "if (m === 'myth') Object.assign(S.settings, { theme: 'temple', photo: 'myth', look: S.settings.glow === 'off' ? 'mythreal' : 'mythic', captions: 'words', capColor: S.settings.glow === 'off' ? '#ffffff' : S.settings.glow === 'full' ? '#ffd36b' : '#ffe8b0',")
rep("toast(m === 'myth' ? 'Mythology style: dark temple background, golden mandala + light rays, glowing figures, gold frames and titles, divine light transitions, temple bell sounds'",
    "toast(m === 'myth' ? 'Mythology style: dark temple background, mandala, framed pictures, temple bell sounds. Divine glow decides how golden it looks (Soft / Full / Real)'")
rep("const BIND = ['variety',", "const BIND = ['glow', 'variety',")
rep("$('#projTitle').value = S.settings.title || ''; R.pack = null; showLook(); }",
    "$('#projTitle').value = S.settings.title || ''; if ($('#glowWrap')) $('#glowWrap').hidden = !isMyth(); R.pack = null; showLook(); }")
rep("  if (k === 'mode') applyModeDefaults();\n",
    """  if (k === 'mode') applyModeDefaults();
  if (k === 'glow') { R.filt.clear(); R.paper.clear(); if (R.lay) R.lay.clear();
    if (S.settings.glow === 'off' && S.settings.look === 'mythic') S.settings.look = 'mythreal'; else if (S.settings.glow !== 'off' && S.settings.look === 'mythreal') S.settings.look = 'mythic';
    if (['#ffd36b', '#ffe8b0', '#ffffff'].includes(S.settings.capColor)) S.settings.capColor = S.settings.glow === 'off' ? '#ffffff' : S.settings.glow === 'full' ? '#ffd36b' : '#ffe8b0';
    applySettingsToUI(); renderSlots(); toast(S.settings.glow === 'off' ? 'Real look: natural colours, no glow, no gold' : S.settings.glow === 'full' ? 'Full divine glow (strong gold)' : 'Soft glow: less gold'); }
""")

# ---------------------------------------------------------------- 4. a cleaner, more attractive look
css = r'''
/* ===== Vox Studio 1.1 look ===== */
:root { --bg: #f4f1ea; --card: #ffffff; --line: #e6dfd0; --radius: 16px; --shadow: 0 1px 2px rgba(20,15,5,.05), 0 8px 24px rgba(60,40,0,.07); }
.top { background: linear-gradient(90deg, #141312, #2a2620); box-shadow: 0 2px 12px rgba(0,0,0,.25); }
.logo { border-radius: 6px; box-shadow: 0 2px 8px rgba(245,211,39,.35); }
.tabs { background: #1d1b19; padding: 6px 8px; gap: 6px; }
.tab { border: 0; border-radius: 999px; padding: 8px 14px; transition: background .15s, color .15s; }
.tab:hover { background: #2e2b27; color: #fff; }
.tab.on { background: var(--yel); color: var(--ink); border-bottom-color: transparent; }
.tab.on b { background: var(--ink); color: var(--yel); }
.pane > h2 { display: inline-block; position: relative; padding-bottom: 4px; }
.pane > h2::after { content: ''; position: absolute; left: 0; bottom: 0; width: 42px; height: 4px; border-radius: 4px; background: var(--yel); }
.card { border: 1px solid var(--line); transition: box-shadow .2s; }
.card:hover { box-shadow: 0 1px 2px rgba(20,15,5,.05), 0 12px 32px rgba(60,40,0,.10); }
.card h3 { font-size: 17px; }
.btn { border-radius: 12px; border-width: 1.5px; }
.btn.primary { background: linear-gradient(180deg, #ffe14d, #f2cc12); box-shadow: 0 3px 10px rgba(217,184,15,.35); }
.btn.primary:hover { background: linear-gradient(180deg, #ffe76e, #f5d327); }
.btn.big { border-radius: 14px; padding: 14px 28px; }
select, input:not([type=range]):not([type=color]):not([type=checkbox]), textarea { border-radius: 12px; }
.drop { border-radius: 16px; background: #fbf8f0; }
.style-pick { background: linear-gradient(135deg, #fff8d8, #fdf1c0); border-color: #efd97a; }
details.more { margin: 12px 0 0; border-top: 1px dashed var(--line); padding-top: 10px; }
details.more > summary { cursor: pointer; font-weight: 700; font-size: 13px; color: var(--sub); list-style: none; display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 999px; background: #f4efe2; }
details.more > summary::-webkit-details-marker { display: none; }
details.more > summary::before { content: '⚙'; }
details.more[open] > summary { background: var(--ink); color: #fff; }
details.more > .form, details.more > .row { margin-top: 12px; }
.preview { border-radius: 18px; box-shadow: 0 10px 30px rgba(0,0,0,.18); }
.slot, .shot { border-radius: 14px; }
.toast { border-radius: 999px; }
.form > label:has(#variety), .form > .row:has(#newLookBtn) { grid-column: 1 / -1; }
.form > .row:has(#newLookBtn) { margin: 0; align-items: flex-start; }
#variety { width: 100%; text-overflow: ellipsis; }
#lookInfo { flex: 1 1 200px; margin: 0; font-size: 12px; line-height: 1.4; }
@media (max-width: 600px) { .tab { padding: 7px 11px; font-size: 14px; } .card { padding: 12px; } }
'''
rep('\n</style>\n</head>', css + '</style>\n</head>')

# ---------------------------------------------------------------- 5. sheet cutting: never trim thin parts (a pointing hand, a spear) off the edge
# trim only columns / rows that are really empty (every pixel checked, a few noisy pixels allowed), not "96 % white"
rep("""      const colW = xx => { let n = 0, m = 0; for (let yy = 0; yy < ch; yy += 3) { n += white(xx, yy); m++; } return n / m > 0.96; };
      const rowW = yy => { let n = 0, m = 0; for (let xx = 0; xx < cw; xx += 3) { n += white(xx, yy); m++; } return n / m > 0.96; };""",
"""      const tolC = Math.max(2, ch * 0.003), tolR = Math.max(2, cw * 0.003);
      const colW = xx => { let k = 0; for (let yy = 0; yy < ch; yy++) if (!white(xx, yy) && ++k > tolC) return false; return true; };
      const rowW = yy => { let k = 0; for (let xx = 0; xx < cw; xx++) if (!white(xx, yy) && ++k > tolR) return false; return true; };""")
# keep a little white around the picture instead of cutting 3 px into it
rep("""      const m = trim ? 3 : 0, o = cv(Math.max(4, r - l + 1 - m * 2), Math.max(4, b - tp + 1 - m * 2)); o.getContext('2d').drawImage(tc, l + m, tp + m, o.width, o.height, 0, 0, o.width, o.height);""",
"""      if (trim) { const pd = Math.round(Math.min(cw, ch) * 0.015); l = Math.max(0, l - pd); r = Math.min(cw - 1, r + pd); tp = Math.max(0, tp - pd); b = Math.min(ch - 1, b + pd); }
      const o = cv(Math.max(4, r - l + 1), Math.max(4, b - tp + 1)); o.getContext('2d').drawImage(tc, l, tp, o.width, o.height, 0, 0, o.width, o.height);""")
# finding the gaps: a column with a thin arm / finger in it is not a "white gap" (was: up to 1.2 % ink allowed)
rep("zero[xx] = ink <= bh * 0.012 ? 1 : 0; cover[xx] = ed / bh; }\n    const cc = pickCuts(zero, cover, w, cols);",
    "zero[xx] = ink <= Math.max(1, bh * 0.002) ? 1 : 0; loose[xx] = ink <= bh * 0.012 ? 1 : 0; cover[xx] = ed / bh; }\n    let cc = pickCuts(zero, cover, w, cols); if (cols > 1 && !cc) cc = pickCuts(loose, cover, w, cols);")
rep("const zero = new Uint8Array(w), cover = new Float32Array(w), bh =", "const zero = new Uint8Array(w), loose = new Uint8Array(w), cover = new Float32Array(w), bh =")
rep("rowZero[y] = ink <= w * 0.012 ? 1 : 0; rowCover[y] = ed / w; }\n  let sc = 0; const rc = pickCuts(rowZero, rowCover, h, rows);",
    "rowZero[y] = ink <= Math.max(1, w * 0.002) ? 1 : 0; rowLoose[y] = ink <= w * 0.012 ? 1 : 0; rowCover[y] = ed / w; }\n  let sc = 0; const rc = pickCuts(rowZero, rowCover, h, rows) || (rows > 1 ? pickCuts(rowLoose, rowCover, h, rows) : null);")
rep("const rowZero = new Uint8Array(h), rowCover", "const rowZero = new Uint8Array(h), rowLoose = new Uint8Array(h), rowCover")

# ---------------------------------------------------------------- 6. sheets are cut in an even grid by default (Auto find = option)
rep("shortEnd: '', shortCaps: 'off', glow: 'soft'\n};", "shortEnd: '', shortCaps: 'off', glow: 'soft', cutMode: 'even'\n};")
rep("const BIND = ['glow', 'variety',", "const BIND = ['glow', 'cutMode', 'variety',")
rep('''          <label class="inline">Decor layers''', '''          <label class="inline">Sheet cutting <select id="cutMode"><option value="even">Even grid (equal parts) — recommended</option><option value="auto">Auto find the gaps</option></select></label>
          <label class="inline">Decor layers''')
rep("function bestGrid(bmp, n) {", """// equal cells; the layout (3×2 or 2×3) follows the shape of the sheet
function evenGrid(bmp, n) {
  const port = bmp.height > bmp.width * 1.1, [cols, rows] = n === 6 ? (port ? [2, 3] : [3, 2]) : n === 2 ? (port ? [1, 2] : [2, 1]) : sheetGrid(n, false);
  return { cols, rows, sc: 0, ys: Array.from({ length: rows + 1 }, (_, i) => i / rows), xs: Array.from({ length: rows }, () => Array.from({ length: cols + 1 }, (_, i) => i / cols)) };
}
const sheetGridFor = (bmp, n) => S.settings.cutMode === 'auto' ? bestGrid(bmp, n) : evenGrid(bmp, n);
function bestGrid(bmp, n) {""")
rep("try { const g = bestGrid(bmp, n), parts = await cutCells(bmp, g);", "try { const g = sheetGridFor(bmp, n), parts = await cutCells(bmp, g);")
rep("S.sheetCuts[k].cols ? S.sheetCuts[k] : bestGrid(src, n))), trim = true", "S.sheetCuts[k].cols ? S.sheetCuts[k] : sheetGridFor(src, n))), trim = true")

# ---------------------------------------------------------------- 7. YouTube kit: description always in English, hashtags start with a small letter
rep("- description: 1200–2500 characters in ${ytLangName()}:", "- description: 1200–2500 characters, ALWAYS in English (even when the script and titles are Hindi / Hinglish; names of people and places stay as they are):")
rep("- hashtags: 3–5, the first one is the main topic.", "- hashtags: 3–5, the first one is the main topic. Every hashtag starts with a small (lowercase) letter, no spaces, e.g. #mahabharat #draupadiStory — also on the last line of the description.")
rep("function quickYT() {", """// hashtags: first letter small (#Mahabharat -> #mahabharat), also inside a text
const hashLow = s => String(s || '').replace(/#(\\p{Lu})/gu, (m, c) => '#' + c.toLowerCase());
function quickYT() {""")
rep("hashtags: hs.map(h => '#' + str(h).replace(/^#+/, '').replace(/\\s+/g, '')).filter(h => h.length > 1) };",
    "hashtags: hs.map(h => hashLow('#' + str(h).replace(/^#+/, '').replace(/\\s+/g, ''))).filter(h => h.length > 1) }; S.yt.description = hashLow(S.yt.description);")
rep(".map(k => '#' + k.replace(/\\s+/g, '')).slice(0, 4);", ".map(k => hashLow('#' + k.replace(/\\s+/g, ''))).slice(0, 4);")
rep("\\n\\nIs video mein:\\n${pick.map(s => '• ' + s).join('\\n')}\\n\\nChapters\\n${chapters}\\n\\n${ch ? `Aisi aur videos ke liye ${ch} ko subscribe karein.` : 'Aisi aur videos ke liye channel ko subscribe karein.'}",
    "\\n\\nIn this video:\\n${pick.map(s => '• ' + s).join('\\n')}\\n\\nChapters\\n${chapters}\\n\\n${ch ? `Like the video and subscribe to ${ch} for more stories like this.` : 'Like the video and subscribe to the channel for more stories like this.'}")
rep("else if (k === 'hashtags') S.yt.hashtags = v.split(/\\s+/).filter(Boolean);", "else if (k === 'hashtags') S.yt.hashtags = v.split(/\\s+/).filter(Boolean).map(hashLow);")

# ---------------------------------------------------------------- 8. kits saved before: fix hashtags on open + one-tap English description
rep("""function renderYT() {
  const box = $('#ytOut'); if (!box) return; const y = S.yt;""", """function renderYT() {
  const box = $('#ytOut'); if (!box) return; const y = S.yt;
  if (y) { const h0 = (y.hashtags || []).join(' '), d0 = y.description || ''; y.hashtags = (y.hashtags || []).map(hashLow); y.description = hashLow(d0); if (h0 !== y.hashtags.join(' ') || d0 !== y.description) save(); }""")
rep("""      <div class="row">${cpy('description', 'Copy description')}<span class="muted" id="ytDescN">""",
    """      <div class="row">${cpy('description', 'Copy description')}<button class="btn small primary" id="ytEn">🌐 Make it English</button><span class="muted" id="ytDescN">""")
rep("  $$('#ytOut [data-open]').forEach(b => b.onclick = () => openApp(b.dataset.open));\n}\nfunction ytValue(id) {",
    """  $$('#ytOut [data-open]').forEach(b => b.onclick = () => openApp(b.dataset.open));
  if ($('#ytEn')) $('#ytEn').onclick = ytMakeEnglish;
}
// rewrite the current description in English (Gemini key: automatic; else: copy a request for ChatGPT / Gemini / Claude)
function ytEnglishPrompt() {
  return `Rewrite this YouTube video description in natural, fluent English. Keep the same meaning and structure: hook lines, summary, the "Chapters" list with the SAME timestamps (translate only the chapter names), the like/subscribe line and the hashtags on the last line. Names of people, places and gods stay as they are. Every hashtag starts with a small (lowercase) letter, no spaces. Return ONLY the new description text, nothing else.\\n\\nDESCRIPTION:\\n${S.yt.description}`;
}
async function ytMakeEnglish() {
  if (!S.yt || !S.yt.description) return toast('Make the YouTube kit first');
  const btn = $('#ytEn'), hasKey = !!(localStorage.getItem('vox-gem-key') || '').trim();
  if (!hasKey) { copyText(ytEnglishPrompt()); $('#ytInfo').textContent = '📋 Request copied. Paste it in ChatGPT / Gemini / Claude, then paste the English answer into the Description box below. (With a Gemini API key in the Plan tab this happens automatically.)'; return; }
  gemStop = false; btn.disabled = true; $('#ytInfo').textContent = 'Making the description English…';
  try { const models = await gemTextModel(); let txt = '', lastErr;
    for (const m of models.slice(0, 3)) { try { const j = await gemFetch(`/models/${m}:generateContent`, { contents: [{ role: 'user', parts: [{ text: ytEnglishPrompt() }] }], generationConfig: { temperature: 0.4 } }); txt = (j.candidates?.[0]?.content?.parts || []).map(p => p.text || '').join('').trim(); if (txt) break; } catch (e) { lastErr = e; if (/Stopped|API key/i.test(e.message)) throw e; } }
    if (!txt) throw lastErr || new Error('Gemini did not answer');
    S.yt.description = hashLow(txt.replace(/^```[a-z]*\\n?|```$/g, '').trim()); S.yt.autoEn = S.yt.description; save(); renderYT(); $('#ytInfo').textContent = '✔ The description is now in English.';
  } catch (e) { $('#ytInfo').textContent = '⚠ ' + e.message; } finally { if ($('#ytEn')) $('#ytEn').disabled = false; }
}
function ytValue(id) {""")
# the request also says it inside the JSON shape, so chat AIs do not miss it
rep(' "description": "...",\n', ' "description": "... (in English)",\n')
# quick kit cannot translate (offline): say so
rep("<p class=\"muted\">Keywords, chapters and tags straight from the script. Simple, but instant.</p>",
    "<p class=\"muted\">Keywords, chapters and tags straight from the script. Simple, but instant. Offline it cannot translate: the story lines stay in the script's language — use <b>🌐 Make it English</b> after.</p>")

# ---------------------------------------------------------------- 9. hashtags all small letters; English description automatic where possible
rep("const hashLow = s => String(s || '').replace(/#(\\p{Lu})/gu, (m, c) => '#' + c.toLowerCase());",
    """const hashLow = s => String(s || '').replace(/#([\\p{L}\\p{N}\\p{M}_]+)/gu, (m, w) => '#' + w.toLowerCase());
// is a text (mostly) not English? Devanagari, or many Hinglish words
const notEnglish = t => /[\\u0900-\\u097F]/.test(t || '') || (String(t || '').match(/\\b(hai|hain|nahi|nahin|kya|aur|mein|tha|thi|the|kar|karein|karte|ki|ka|ke|ko|se|ye|yeh|woh|kaise|kyun)\\b/gi) || []).length >= 4;""")
rep("Every hashtag starts with a small (lowercase) letter, no spaces, e.g. #mahabharat #draupadiStory — also on the last line of the description.",
    "Every hashtag is written in small (lowercase) letters only, no spaces, e.g. #mahabharat #draupadistory — also on the last line of the description.")
rep("Every hashtag starts with a small (lowercase) letter, no spaces. Return ONLY", "Every hashtag is in small (lowercase) letters only, no spaces. Return ONLY")
# quick kit: without AI the story lines would stay Hindi, so the description is built from English lines only
rep("const description = `${title}\\n\\n${sents.slice(0, 2).join(' ')}\\n\\nIn this video:\\n${pick.map(s => '• ' + s).join('\\n')}\\n\\nChapters",
    "const engScript = !notEnglish(sents.join(' '));\n  const description = `${title}\\n\\n${engScript ? sents.slice(0, 2).join(' ') : `The full story of ${main}${second ? ' and ' + second : ''}, explained step by step.`}\\n\\n${engScript ? `In this video:\\n${pick.map(s => '• ' + s).join('\\n')}\\n\\n` : ''}Chapters")
# YouTube tab: warn when the description is not English, translate it by itself when a Gemini key is there
rep("""  if (y) { const h0 = (y.hashtags || []).join(' '), d0 = y.description || ''; y.hashtags = (y.hashtags || []).map(hashLow); y.description = hashLow(d0); if (h0 !== y.hashtags.join(' ') || d0 !== y.description) save(); }""",
"""  if (y) { const h0 = (y.hashtags || []).join(' '), d0 = y.description || ''; y.hashtags = (y.hashtags || []).map(hashLow); y.description = hashLow(d0); if (h0 !== y.hashtags.join(' ') || d0 !== y.description) save(); }
  const nonEn = !!(y && y.description && notEnglish(y.description)), hasKey = !!(localStorage.getItem('vox-gem-key') || '').trim();
  if (nonEn && hasKey && y.autoEn !== y.description) { y.autoEn = y.description; setTimeout(ytMakeEnglish, 50); }""")
rep("""    <div class="card"><h3>📝 Description</h3><textarea data-yedit="description" rows="14">""",
    """    <div class="card"><h3>📝 Description</h3>${nonEn ? `<p class="info">⚠ This description is not in English yet. ${hasKey ? 'Making it English with Gemini…' : 'Tap <b>🌐 Make it English</b>: it copies a request — paste it in ChatGPT / Gemini, then paste the answer here. (Add a free Gemini API key in the Plan tab and this happens by itself.)'}</p>` : ''}<textarea data-yedit="description" rows="14">""")
# after a translation the warning must go: render again (ytMakeEnglish already calls renderYT)
# ---------------------------------------------------------------- 10. preview: ±5 s, vertical / landscape, full screen, double-tap to skip
rep('''        <button class="btn icon" id="playBtn" aria-label="Play">▶</button>
        <input type="range" id="seek" min="0" max="1000" value="0" aria-label="Seek">
        <span id="pvTime" class="mono">0:00 / 0:00</span>
        <button class="btn cc" id="ccBtn" title="Captions on / off">CC</button>''',
'''        <button class="btn pvb" id="backBtn" aria-label="Back 5 seconds" title="Back 5 s">↺5</button>
        <button class="btn icon" id="playBtn" aria-label="Play">▶</button>
        <button class="btn pvb" id="fwdBtn" aria-label="Forward 5 seconds" title="Forward 5 s">5↻</button>
        <span class="pv-sp"></span>
        <button class="btn pvb" id="orientBtn" title="Vertical / landscape"></button>
        <button class="btn pvb" id="rotBtn" title="Rotate the picture" hidden>⟳ Rotate</button>
        <button class="btn pvb" id="fullBtn" aria-label="Full screen" title="Full screen">⛶</button>
        <button class="btn cc" id="ccBtn" title="Captions on / off">CC</button>
        <span class="pv-br"></span>
        <input type="range" id="seek" min="0" max="1000" value="0" aria-label="Seek">
        <span id="pvTime" class="mono">0:00 / 0:00</span>''')
rep("$('#seek').oninput = e => setT(e.target.value / 1000 * duration());\n",
"""$('#seek').oninput = e => setT(e.target.value / 1000 * duration());
// ±5 seconds (buttons, arrow keys, double-tap on the left / right of the picture)
const skip = d => { setT(nowT() + d); if (playing && S.musicBlob) { try { music.currentTime = music.duration ? nowT() % music.duration : 0; } catch (_) {} } };
$('#backBtn').onclick = () => skip(-5); $('#fwdBtn').onclick = () => skip(5);
document.addEventListener('keydown', e => { if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && !e.target.closest('input,textarea,select,[contenteditable]')) { e.preventDefault(); skip(e.key === 'ArrowLeft' ? -5 : 5); } });
pv.addEventListener('dblclick', e => { const r = pv.getBoundingClientRect(), f = (e.clientX - r.left) / r.width; if (f < 0.35) skip(-5); else if (f > 0.65) skip(5); else playing ? pause() : play(); });
// vertical (9:16) / landscape (16:9): the same Format setting as in Export, so what you see is what you export
function showOrient() { const v = S.settings.aspect === '9:16'; $('#orientBtn').textContent = v ? '▭ Landscape' : '▯ Vertical'; $('#orientBtn').title = v ? 'Switch to landscape 16:9' : 'Switch to vertical 9:16'; }
$('#orientBtn').onclick = () => { S.settings.aspect = S.settings.aspect === '9:16' ? '16:9' : '9:16'; if ($('#aspect')) $('#aspect').value = S.settings.aspect; R.lay && R.lay.clear(); sizePreview(); showOrient(); touch(); save();
  toast(S.settings.aspect === '9:16' ? 'Vertical 9:16 (Shorts / Reels) — export will be vertical too' : 'Landscape 16:9 (YouTube) — export will be landscape too'); if ($('#pvFull')) fitFull(); };
// full screen: the preview fills the screen (and turns the phone for landscape when the system allows it)
// full screen: our own layer on top of the app (no system full-screen / rotation, which the app WebView does not support well)
function fitFull() {
  const ov = $('#pvFull'); if (!ov) return; const rot = ov.classList.contains('rot'), vw = innerWidth, vh = innerHeight, SW = rot ? vh : vw, SH = rot ? vw : vh;
  ov.style.width = SW + 'px'; ov.style.height = SH + 'px';
  const bar = ov.querySelector('.pv-bar'), bh = bar ? bar.offsetHeight : 90, k = Math.min(SW / pv.width, (SH - bh - 8) / pv.height);
  pv.style.aspectRatio = ''; pv.style.maxHeight = 'none'; pv.style.margin = '0 auto'; pv.style.width = Math.floor(pv.width * k) + 'px'; pv.style.height = Math.floor(pv.height * k) + 'px';
}
function setFull(on) {
  const box = document.querySelector('.preview'); if (!box) return;
  if (on && !$('#pvFull')) { const ov = document.createElement('div'); ov.id = 'pvFull'; document.body.appendChild(ov); ov.appendChild(box); document.body.classList.add('pv-full'); setTimeout(fitFull, 30); }
  if (!on && $('#pvFull')) { $('#previewCol').appendChild(box); $('#pvFull').remove(); document.body.classList.remove('pv-full'); pv.style.height = ''; sizePreview(); }
  $('#fullBtn').textContent = on ? '✕' : '⛶'; $('#fullBtn').title = on ? 'Close full screen' : 'Full screen'; $('#rotBtn').hidden = !on;
}
$('#fullBtn').onclick = () => setFull(!$('#pvFull'));
$('#rotBtn').onclick = () => { const ov = $('#pvFull'); if (!ov) return; ov.classList.toggle('rot'); fitFull(); };
addEventListener('resize', () => setTimeout(fitFull, 60));
document.addEventListener('keydown', e => { if (e.key === 'Escape' && $('#pvFull')) setFull(false); });
""")
rep("$('#projTitle').value = S.settings.title || ''; if ($('#glowWrap'))", "$('#projTitle').value = S.settings.title || ''; if ($('#orientBtn')) showOrient(); if ($('#glowWrap'))")
rep("  if (k === 'aspect') { sizePreview();", "  if (k === 'aspect') { if ($('#orientBtn')) showOrient(); sizePreview();")

# ---------------------------------------------------------------- 11. style changed after making a plan: say whether a new plan is needed
rep("  S.assets = assets; S.shots = shots; S.images = images; R.img = {}; R.filt.clear(); touch();", "  S.assets = assets; S.shots = shots; S.images = images; S.settings.planMode = S.settings.mode; R.img = {}; R.filt.clear(); touch();")
rep("  S.shots = shots; S.images = images; R.img = {}; R.filt.clear(); touch();", "  S.shots = shots; S.images = images; S.settings.planMode = S.settings.mode; R.img = {}; R.filt.clear(); touch();")
rep("  if (k === 'mode') applyModeDefaults();\n", """  if (k === 'mode') applyModeDefaults();
  if (k === 'mode' && $('#planInfo')) { const pm = S.settings.planMode, NM = { collage: 'Layered collage', modern: 'Modern collage', classic: 'Classic Vox', myth: 'Mythology / Divine' };
    $('#planInfo').textContent = !(S.shots || []).length || !pm || pm === S.settings.mode ? '' : (pm === 'classic') !== (S.settings.mode === 'classic')
      ? `⚠ Your plan was made for ${NM[pm]}. ${NM[S.settings.mode]} needs its own plan: make it again with A, B or C. Your scenes and voice stay as they are.`
      : `ℹ Your plan was made for ${NM[pm]}. It still works in ${NM[S.settings.mode]} (background, frames, titles and picture style change by themselves). For the best result make a new plan with A or B: the picture ideas and transitions are written for the style. Scenes and voice stay; pictures whose prompt stays the same are kept.`; }
""")

css2 = r'''
/* preview controls */
.pv-bar { flex-wrap: wrap; gap: 8px; }
.pv-br { flex-basis: 100%; height: 0; }
.pv-bar #seek { flex: 1 1 auto; min-width: 0; }
.pv-bar #pvTime { font-size: 12px; white-space: nowrap; }
@media (max-width: 480px) { .pv-bar { gap: 6px; } .btn.pvb { padding: 3px 8px; font-size: 12px; } .btn.icon { width: 40px; } }
.pv-sp { flex: 1; }
.btn.pvb { min-height: 34px; padding: 3px 10px; font: 800 13px "Archivo", sans-serif; background: #2c2a26; color: #f1ece1; border: 1.5px solid #4a463f; border-radius: 10px; }
.btn.pvb:hover { background: #3a3732; }
body.pv-full { overflow: hidden; }
#pvFull { position: fixed; left: 50%; top: 50%; transform: translate(-50%, -50%); z-index: 500; background: #000; display: flex; align-items: center; justify-content: center; }
#pvFull.rot { transform: translate(-50%, -50%) rotate(90deg); }
#pvFull .preview { position: static; width: 100%; border-radius: 0; box-shadow: none; background: #000; }
#pvFull #pv { display: block; }
#pvFull .pv-bar { background: rgba(27,26,24,.92); }
#pvFull .pv-shot { display: none; }
'''
rep('</style>\n</head>', css2 + '</style>\n</head>')

open(dst, 'w', encoding='utf-8').write(h)
print('ok', len(h))
