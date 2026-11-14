"""Style presets, offline prompt enhancer and 'Surprise me' ideas."""
import random

NEG_BASE = ("lowres, worst quality, low quality, blurry, jpeg artifacts, deformed, disfigured, "
            "bad anatomy, extra fingers, mutated hands, extra limbs, watermark, signature, text error")

# name -> (positive template, extra negative)
STYLES: dict[str, tuple[str, str]] = {
    "🚫 No style": ("{p}", ""),
    "📷 Real Photo": ("{p}, professional photograph, ultra realistic, 35mm photo, natural lighting, "
                     "sharp focus, highly detailed skin texture, 8k", "cartoon, painting, illustration, 3d render, anime"),
    "🎬 Cinematic": ("cinematic film still of {p}, dramatic lighting, shallow depth of field, anamorphic, "
                    "color graded, epic composition, highly detailed", "cartoon, flat, amateur"),
    "🌸 Anime": ("anime artwork of {p}, anime style, key visual, vibrant colors, studio quality, "
                "detailed eyes, clean lineart", "photo, realistic, 3d"),
    "🧸 3D Cartoon": ("3d animated movie still of {p}, cute, pixar style render, soft lighting, "
                                   "octane render, vibrant, highly detailed", "photo, realistic, 2d, sketch"),
    "🎨 Digital Art": ("digital painting of {p}, concept art, highly detailed, artstation trending, "
                      "sharp focus, illustration", "photo"),
    "🖼️ Oil Painting": ("oil painting of {p}, thick brush strokes, canvas texture, classical masterpiece, "
                       "rich colors", "photo, 3d, digital"),
    "💧 Watercolor": ("watercolor painting of {p}, soft wash, paper texture, delicate, artistic", "photo, 3d"),
    "✏️ Pencil Sketch": ("pencil sketch of {p}, graphite drawing, detailed shading, monochrome, paper", "color, photo"),
    "💥 Comic Book": ("comic book illustration of {p}, bold ink lines, halftone, vibrant, dynamic", "photo, realistic"),
    "🐉 Fantasy": ("epic fantasy art of {p}, magical atmosphere, glowing light, intricate details, "
                  "dramatic sky, masterpiece", "modern, photo"),
    "🌃 Cyberpunk": ("cyberpunk {p}, neon lights, futuristic city, rain, reflections, blade runner vibe, "
                    "highly detailed", "daylight, nature"),
    "🪔 Indian Art (Madhubani/Miniature)": ("traditional indian art of {p}, madhubani and mughal miniature style, "
                                          "intricate patterns, rich colors, gold details", "photo, 3d, modern"),
    "🌑 Glowing Silhouette": ("silhouette of {p}, black silhouette against glowing sunset backlight, "
                                 "rim light, minimal, cinematic, high contrast", "detailed face, cluttered"),
    "▶️ YouTube Thumbnail": ("{p}, eye catching youtube thumbnail, bold, high contrast, vibrant saturated colors, "
                            "expressive, clean background, dramatic lighting", "dull, muted, cluttered"),
    "📦 Product Photo": ("product photography of {p}, studio lighting, clean background, commercial, "
                        "sharp focus, high detail", "cluttered, people"),
    "🧑 Portrait": ("portrait of {p}, studio portrait, soft key light, bokeh, detailed face, 85mm lens",
                   "cartoon, full body, cropped head"),
    "🏷️ Logo": ("minimal vector logo of {p}, flat design, simple shapes, clean, centered, white background",
               "photo, realistic, gradient, clutter, 3d"),
    "👾 Pixel Art": ("pixel art of {p}, 16-bit, retro game sprite, crisp pixels, limited palette", "blurry, photo, 3d"),
    "🔷 Low Poly / Isometric": ("isometric low poly 3d {p}, clean geometry, soft shadows, diorama, pastel colors",
                               "photo, sketch"),
    "🖍️ Coloring Book (line art)": ("coloring book page of {p}, black and white line art, thick clean outlines, "
                                   "no shading, white background", "color, shading, photo, gray"),
    "🏷️ Sticker": ("die-cut sticker of {p}, cute, bold outline, white border, vector, simple background",
                  "photo, realistic, cluttered background"),
}
STYLE_NAMES = list(STYLES.keys())


def apply_style(style: str, prompt: str, negative: str, use_base_neg: bool = True) -> tuple[str, str]:
    pos_t, neg_extra = STYLES.get(style, ("{p}", ""))
    pos = pos_t.replace("{p}", prompt.strip()) if prompt.strip() else pos_t.replace("{p}", "").strip(", ")
    negs = [n for n in (negative.strip(), neg_extra, NEG_BASE if use_base_neg else "") if n]
    return pos, ", ".join(negs)


_QUALITY = ["highly detailed", "masterpiece", "best quality", "sharp focus", "intricate details"]
_LIGHT = ["golden hour lighting", "soft studio lighting", "dramatic rim lighting", "volumetric light rays",
          "moody cinematic lighting", "bright natural daylight", "neon glow"]
_CAMERA = ["wide angle shot", "close-up", "low angle view", "aerial view", "medium shot", "rule of thirds composition"]


def enhance_prompt(prompt: str) -> str:
    """Turns a short prompt into a detailed one (offline, rule-based)."""
    p = prompt.strip().rstrip(",")
    if not p:
        return random_prompt()
    low = p.lower()
    extra = []
    if not any(w in low for w in ("light", "glow", "sunset", "neon")):
        extra.append(random.choice(_LIGHT))
    if not any(w in low for w in ("shot", "view", "close-up", "angle")):
        extra.append(random.choice(_CAMERA))
    extra += random.sample(_QUALITY, 3)
    return p + ", " + ", ".join(extra)


_SUBJ = ["a majestic tiger", "an old wise monk", "a tiny robot", "a floating island castle", "a cute baby dragon",
         "a vintage red car", "a girl in a saree", "an astronaut", "a mountain village", "a magical library",
         "a samurai cat", "a lighthouse", "a street food stall in old Delhi", "a temple by the river",
         "a steampunk airship", "a fox spirit", "a cozy tea shop", "a peacock"]
_PLACE = ["in a misty forest", "on mars", "during diwali night", "in a rainy cyberpunk city", "under the northern lights",
          "on a snowy himalayan peak", "in a field of sunflowers", "inside a crystal cave", "at the bottom of the ocean",
          "in a busy indian market", "among the clouds", "in a desert at sunset"]
_MOOD = ["peaceful", "epic", "mysterious", "joyful", "dreamy", "dramatic", "whimsical"]


def random_prompt() -> str:
    return (f"{random.choice(_SUBJ)} {random.choice(_PLACE)}, {random.choice(_MOOD)} atmosphere, "
            f"{random.choice(_LIGHT)}, highly detailed")


# Aspect ratio presets: name -> (w, h) ratio
ASPECTS = {
    "⬛ 1:1 Square (Insta post)": (1, 1),
    "📱 9:16 (Reels/Shorts/Story)": (9, 16),
    "🖥️ 16:9 (YouTube/Wallpaper)": (16, 9),
    "🖼️ 3:4 Portrait": (3, 4),
    "🏞️ 4:3 Landscape": (4, 3),
    "📄 2:3 Poster": (2, 3),
    "🎞️ 3:2 Photo": (3, 2),
    "✏️ Custom size": None,
}
ASPECT_NAMES = list(ASPECTS.keys())


def size_for(aspect: str, base: int, scale: float = 1.0) -> tuple[int, int]:
    """Width/height (multiples of 64) from aspect ratio + model base resolution."""
    r = ASPECTS.get(aspect) or (1, 1)
    area = (base * scale) ** 2
    w = (area * r[0] / r[1]) ** 0.5
    h = area / w
    return max(256, int(round(w / 64)) * 64), max(256, int(round(h / 64)) * 64)


def size_like(img_w: int, img_h: int, base: int) -> tuple[int, int]:
    area = base * base
    w = (area * img_w / img_h) ** 0.5
    h = area / w
    return max(256, int(round(w / 64)) * 64), max(256, int(round(h / 64)) * 64)
