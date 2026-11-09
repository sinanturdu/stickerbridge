#!/usr/bin/env python3
"""Generate the synthetic test stickers for experiment E1 (IME probe).

All images are drawn from scratch: a simple coloured shape on a transparent
background with the text "TEST" and a frame number. No real stickers are used.

Output (relative to this script):
    ../app/src/main/res/raw/test_png.png             static PNG, transparent
    ../app/src/main/res/raw/test_webp.webp           static WEBP, transparent
    ../app/src/main/res/raw/test_webp_animated.webp  animated WEBP, 4 frames
    ../app/src/main/res/raw/test_gif.gif             animated GIF, 4 frames

Usage (PowerShell, from experiments/e1-ime-probe):
    python -m pip install Pillow
    python tools/generate_assets.py
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, features

SIZE = 512  # WhatsApp/Instagram stickers are 512x512
FRAMES = 4
FRAME_MS = 400
MAX_BYTES = 200 * 1024

OUT_DIR = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res" / "raw"

COLOURS = [
    (231, 76, 60, 255),   # red
    (46, 204, 113, 255),  # green
    (52, 152, 219, 255),  # blue
    (241, 196, 15, 255),  # yellow
]


def load_font(size: int) -> ImageFont.ImageFont:
    for name in ("arialbd.ttf", "Arial Bold.ttf", "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    try:
        return ImageFont.load_default(size=size)  # Pillow >= 10.1
    except TypeError:
        return ImageFont.load_default()


FONT_BIG = load_font(120)
FONT_SMALL = load_font(64)


def centered_text(draw: ImageDraw.ImageDraw, y: int, text: str, font) -> None:
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
    x = (SIZE - (right - left)) // 2 - left
    draw.text((x, y - top), text, font=font, fill=(255, 255, 255, 255),
              stroke_width=6, stroke_fill=(0, 0, 0, 255))


def make_frame(index: int, label: str) -> Image.Image:
    """One RGBA frame: rotating shape + 'TEST' + frame label."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    colour = COLOURS[index % len(COLOURS)]
    margin = 40
    shape = index % 3
    if shape == 0:
        draw.ellipse([margin, margin, SIZE - margin, SIZE - margin], fill=colour,
                     outline=(0, 0, 0, 255), width=8)
    elif shape == 1:
        draw.rounded_rectangle([margin, margin, SIZE - margin, SIZE - margin], radius=80,
                               fill=colour, outline=(0, 0, 0, 255), width=8)
    else:
        draw.regular_polygon((SIZE // 2, SIZE // 2, SIZE // 2 - margin), n_sides=6,
                             rotation=index * 15, fill=colour, outline=(0, 0, 0, 255), width=8)
    centered_text(draw, 150, "TEST", FONT_BIG)
    centered_text(draw, 310, label, FONT_SMALL)
    return img


def to_gif_frame(rgba: Image.Image) -> Image.Image:
    """Convert RGBA to palette mode with index 255 reserved as fully transparent."""
    alpha = rgba.getchannel("A")
    pal = rgba.convert("RGB").quantize(colors=255, method=Image.Quantize.MEDIANCUT)
    mask = alpha.point(lambda a: 255 if a < 128 else 0)
    pal.paste(255, mask=mask)
    pal.info["transparency"] = 255
    return pal


def check(path: Path) -> None:
    size = path.stat().st_size
    status = "OK" if size < MAX_BYTES else "TOO BIG"
    print(f"{status:7} {size / 1024:7.1f} KB  {path.relative_to(OUT_DIR.parent.parent.parent.parent.parent)}")
    if size >= MAX_BYTES:
        raise SystemExit(f"{path.name} exceeds {MAX_BYTES} bytes")


def main() -> int:
    if not features.check("webp"):
        print("This Pillow build has no WEBP support. Install a wheel from PyPI: python -m pip install -U Pillow")
        return 1
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    static = make_frame(0, "frame 1/1")
    png = OUT_DIR / "test_png.png"
    static.save(png, format="PNG", optimize=True)
    check(png)

    webp = OUT_DIR / "test_webp.webp"
    make_frame(1, "frame 1/1").save(webp, format="WEBP", lossless=True, quality=100, method=6)
    check(webp)

    frames = [make_frame(i, f"frame {i + 1}/{FRAMES}") for i in range(FRAMES)]

    awebp = OUT_DIR / "test_webp_animated.webp"
    frames[0].save(awebp, format="WEBP", save_all=True, append_images=frames[1:],
                   duration=FRAME_MS, loop=0, lossless=True, quality=100, method=6)
    check(awebp)

    gif_frames = [to_gif_frame(f) for f in frames]
    gif = OUT_DIR / "test_gif.gif"
    gif_frames[0].save(gif, format="GIF", save_all=True, append_images=gif_frames[1:],
                       duration=FRAME_MS, loop=0, transparency=255, disposal=2, optimize=False)
    check(gif)

    # Sanity: animated outputs really have several frames.
    for p in (awebp, gif):
        with Image.open(p) as im:
            n = getattr(im, "n_frames", 1)
            if n < 3:
                raise SystemExit(f"{p.name} has only {n} frame(s)")
            print(f"        {p.name}: {n} frames, mode {im.mode}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
