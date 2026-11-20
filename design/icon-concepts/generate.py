#!/usr/bin/env python3
"""Render the selected SOFT source and the alternative SHARP comparison.
Requires system Python packages: PyGObject (Rsvg), Pillow.
"""
from pathlib import Path
import gi
from PIL import Image, ImageDraw, ImageFont

gi.require_version("Rsvg", "2.0")
from gi.repository import Rsvg

ROOT = Path(__file__).resolve().parent
NAVY = "#111B30"
WHITE = "#F7FAFC"
MINT = "#79E2C4"


def svg(mark):
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 512 512">
  <title>Pi Bridge icon concept</title>
  <rect width="512" height="512" rx="112" fill="{NAVY}"/>
  {mark}
</svg>
'''


concepts = {
    "01-soft": svg(f'''<g fill="none" stroke="{WHITE}" stroke-width="42" stroke-linecap="round" stroke-linejoin="round">
    <path d="M212 190 C212 246 202 304 184 346"/>
    <path d="M306 190 L306 306 Q306 346 340 346 Q354 346 365 336"/>
    <path d="M138 210 Q150 190 180 190 L366 190"/>
  </g>
  <path d="M344 190 L374 190" fill="none" stroke="{MINT}" stroke-width="42" stroke-linecap="round"/>'''),
    "02-sharp": svg(f'''<g fill="none" stroke="{WHITE}" stroke-width="42" stroke-linecap="square" stroke-linejoin="miter">
    <path d="M211 206 L184 346"/>
    <path d="M306 190 L306 320 L332 346 L358 346"/>
    <path d="M123 190 L395 190" stroke-linecap="butt"/>
  </g>
  <path d="M344 190 L395 190" fill="none" stroke="{MINT}" stroke-width="42" stroke-linecap="butt"/>'''),
}

for name, source in concepts.items():
    path = ROOT / f"{name}.svg"
    path.write_text(source)
    handle = Rsvg.Handle.new_from_file(str(path))
    handle.get_pixbuf().savev(str(ROOT / f"{name}.png"), "png", [], [])

FONT_JA = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
FONT_EN = "/usr/share/fonts/truetype/space-grotesk/SpaceGrotesk-Regular.ttf"
font = lambda size: ImageFont.truetype(FONT_JA, size)
en = lambda size: ImageFont.truetype(FONT_EN, size)
board = Image.new("RGB", (1440, 1120), "#EEF2F7")
d = ImageDraw.Draw(board)
d.text((64, 40), "PI MOBILE BRIDGE / ICON STUDY", font=en(23), fill="#52647C")
d.text((60, 82), "πを、橋に。", font=font(48), fill=NAVY)
d.text((1040, 104), "NAVY / WHITE / MINT", font=en(20), fill="#52647C")

for x, name, heading, subtitle in [
    (56, "01-soft", "01  SOFT / SELECTED", "丸みのある、やわらかいπ"),
    (748, "02-sharp", "02  SHARP", "直線的な、端正なπ"),
]:
    d.rounded_rectangle((x, 180, x + 636, 1000), radius=32, fill="white")
    d.text((x + 40, 210), heading, font=en(31), fill=NAVY)
    d.text((x + 40, 259), subtitle, font=font(24), fill="#52647C")
    icon = Image.open(ROOT / f"{name}.png").convert("RGBA")
    big = icon.resize((340, 340), Image.Resampling.LANCZOS)
    board.paste(big, (x + 148, 327), big)
    d.text((x + 40, 727), "小さい表示でも、ひとつの形に。", font=font(22), fill=NAVY)
    d.text((x + 40, 768), "マスク形状と小サイズの比較", font=font(19), fill="#52647C")
    for dx, size, circle in [(60, 80, False), (222, 80, True), (400, 48, False), (510, 32, False)]:
        small = icon.resize((size, size), Image.Resampling.LANCZOS)
        if circle:
            mask = Image.new("L", (size * 4, size * 4), 0)
            ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
            small.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
        y = 839 + (80 - size) // 2
        board.paste(small, (x + dx, y), small)
    for dx, label in [(64, "80 px"), (226, "80 px"), (396, "48 px"), (502, "32 px")]:
        d.text((x + dx, 935), label, font=en(18), fill="#52647C")

d.text((64, 1040), "濃紺の背景 / 白いπ / ミントの接続点", font=font(21), fill="#52647C")
d.text((1004, 1045), "SOFT SELECTED / SHARP ALTERNATIVE", font=en(16), fill="#52647C")
board.save(ROOT / "comparison.png")
print("Created 2 SVGs, 2 transparent-corner PNGs, and comparison.png")
