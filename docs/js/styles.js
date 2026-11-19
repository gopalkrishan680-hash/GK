// Style presets, sizes and prompt helpers (all offline).
export const STYLES = [
  ["None", "{p}"],
  ["📷 Real Photo", "{p}, professional photograph, ultra realistic, natural lighting, sharp focus, highly detailed, 35mm"],
  ["🎬 Cinematic", "cinematic film still of {p}, dramatic lighting, shallow depth of field, color graded, epic composition"],
  ["🌸 Anime", "anime artwork of {p}, anime style, key visual, vibrant colors, clean lineart, studio quality"],
  ["🧸 3D Cartoon", "3d animated movie still of {p}, cute, soft lighting, octane render, vibrant, highly detailed"],
  ["🎨 Digital Art", "digital painting of {p}, concept art, highly detailed, sharp focus, illustration"],
  ["🖼️ Oil Painting", "oil painting of {p}, thick brush strokes, canvas texture, classical masterpiece, rich colors"],
  ["💧 Watercolor", "watercolor painting of {p}, soft wash, paper texture, delicate, artistic"],
  ["✏️ Pencil Sketch", "pencil sketch of {p}, graphite drawing, detailed shading, monochrome"],
  ["💥 Comic", "comic book illustration of {p}, bold ink lines, halftone, vibrant, dynamic"],
  ["🐉 Fantasy", "epic fantasy art of {p}, magical atmosphere, glowing light, intricate details, dramatic sky"],
  ["🌃 Cyberpunk", "cyberpunk {p}, neon lights, futuristic city, rain, reflections, highly detailed"],
  ["🪔 Indian Art", "traditional indian art of {p}, madhubani and mughal miniature style, intricate patterns, rich colors, gold details"],
  ["🌑 Silhouette", "silhouette of {p}, black silhouette against a glowing sunset, rim light, minimal, high contrast"],
  ["▶️ Thumbnail", "{p}, eye catching youtube thumbnail, bold, high contrast, vibrant saturated colors, dramatic lighting"],
  ["📦 Product", "product photography of {p}, studio lighting, clean background, commercial, sharp focus"],
  ["🧑 Portrait", "portrait of {p}, studio portrait, soft key light, bokeh, detailed face, 85mm lens"],
  ["🏷️ Logo", "minimal vector logo of {p}, flat design, simple shapes, clean, centered, white background"],
  ["👾 Pixel Art", "pixel art of {p}, 16-bit, retro game sprite, crisp pixels, limited palette"],
  ["🔷 Isometric", "isometric low poly 3d {p}, clean geometry, soft shadows, diorama, pastel colors"],
  ["🖍️ Coloring Page", "coloring book page of {p}, black and white line art, thick clean outlines, no shading, white background"],
  ["🏷️ Sticker", "die-cut sticker of {p}, cute, bold outline, white border, vector, simple background"],
];

export function applyStyle(styleIdx, prompt) {
  const t = (STYLES[styleIdx] || STYLES[0])[1];
  const p = prompt.trim();
  return p ? t.replace("{p}", p) : t.replace("{p}", "").replace(/^[,\s]+|[,\s]+$/g, "");
}

// Sizes in pixels (multiples of 64). SD-Turbo is trained at 512×512.
export const SIZES = [
  ["⬛ Square 1:1", 512, 512],
  ["📱 Story 9:16", 448, 768],
  ["🖼️ Portrait 2:3", 512, 768],
  ["🧍 Portrait 3:4", 512, 704],
  ["🖥️ Wide 16:9", 768, 448],
  ["🏞️ Landscape 3:2", 768, 512],
  ["🎞️ Landscape 4:3", 704, 512],
  ["🔹 Small 1:1 (less memory)", 384, 384],
];

const pick = (a) => a[Math.floor(Math.random() * a.length)];
const QUALITY = ["highly detailed", "masterpiece", "best quality", "sharp focus", "intricate details"];
const LIGHT = ["golden hour lighting", "soft studio lighting", "dramatic rim lighting", "volumetric light rays",
  "moody cinematic lighting", "bright natural daylight", "neon glow"];
const CAMERA = ["wide angle shot", "close-up", "low angle view", "aerial view", "medium shot"];

export function enhancePrompt(prompt) {
  let p = prompt.trim().replace(/,$/, "");
  if (!p) return randomPrompt();
  const low = p.toLowerCase(), extra = [];
  if (!/light|glow|sunset|neon/.test(low)) extra.push(pick(LIGHT));
  if (!/shot|view|close-up|angle/.test(low)) extra.push(pick(CAMERA));
  const q = [...QUALITY].sort(() => Math.random() - 0.5).slice(0, 3);
  return p + ", " + [...extra, ...q].join(", ");
}

const SUBJ = ["a majestic tiger", "an old wise monk", "a tiny robot", "a floating island castle", "a cute baby dragon",
  "a vintage red car", "a girl in a saree", "an astronaut", "a mountain village", "a magical library",
  "a samurai cat", "a lighthouse", "a street food stall in old Delhi", "a temple by the river",
  "a steampunk airship", "a fox spirit", "a cozy tea shop", "a peacock"];
const PLACE = ["in a misty forest", "on mars", "during diwali night", "in a rainy cyberpunk city", "under the northern lights",
  "on a snowy himalayan peak", "in a field of sunflowers", "inside a crystal cave", "at the bottom of the ocean",
  "in a busy indian market", "among the clouds", "in a desert at sunset"];
const MOOD = ["peaceful", "epic", "mysterious", "joyful", "dreamy", "dramatic", "whimsical"];

export function randomPrompt() {
  return `${pick(SUBJ)} ${pick(PLACE)}, ${pick(MOOD)} atmosphere, ${pick(LIGHT)}, highly detailed`;
}
