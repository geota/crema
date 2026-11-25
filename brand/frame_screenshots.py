#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""
Frame raw app screenshots into the Google Play listing images.

Each listing image is a copper field with a cream Newsreader caption across the
top and the app screen in a dark rounded device frame. The layout per device
class (canvas size, caption size, frame box) matches the listing set first made
in June 2026, so a retake drops in without a visual jump.

Input: raw screenshots at the device's native resolution, named after the shot
slugs below:

    RAW/phone/01-brew-live.png      (Pixel, portrait,     e.g. 1280×2856)
    RAW/tablet7/01-brew-live.png    (7" tablet, landscape, e.g. 1920×1200)
    RAW/tablet10/01-brew-live.png   (10" tablet, landscape, e.g. 2560×1600)

Output (default: the listing folder the fastlane metadata symlinks to):

    phoneScreenshots/  1440×2880   sevenInchScreenshots/ 1920×1313
    tenInchScreenshots/ 2560×1752  — opaque 24-bit PNG, ratio ≤ 2:1 (Play's rules)

Usage:
    uv run brand/frame_screenshots.py RAW              # frame all three sets
    uv run brand/frame_screenshots.py RAW --out DIR    # somewhere else
    uv run brand/frame_screenshots.py --check          # verify the committed set

Needs ImageMagick 7 (`magick`). Downloads Newsreader to brand/ on first run
(gitignored, shared with generate_mark.py).

Capturing the raws (the 0.12 set): a `playNightly` build on fresh emulators
(Pixel 10 Pro, 7" landscape, 10" landscape) in dark mode, with demo data only:
invented roasters, bags and shots restored from a crema-backup file
(Settings ▸ Sharing ▸ Restore). The live brew is a replay of
core/de1-app/tests/fixtures/session-*-shot-pull.jsonl: Settings ▸ Advanced ▸
Hub mode + "Replay capture as DE1", the capture copied into the app's
Android/data/<pkg>/files/captures (made world-readable), restart, Connect;
the pour starts about 70 s after connecting. Manual is the built-in 1 Cup V60
recipe, mid step 2. Grab each screen with `adb exec-out screencap -p`.
"""

import argparse
import os
import subprocess
import sys
import tempfile
import urllib.request
from dataclasses import dataclass

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
FONT = os.path.join(HERE, "Newsreader.ttf")
FONT_URL = "https://github.com/google/fonts/raw/main/ofl/newsreader/Newsreader%5Bopsz,wght%5D.ttf"
LISTING = os.path.join(
    REPO, "android", "distribution", "play-listing", "en-US", "images"
)

COPPER = "#C7763B"  # copper-500, the brand field
CREAM = "#F2E7D8"  # caption ink
FRAME = "#110D0A"  # device frame, a shade under the app's own background

# The listing, in order. Slug → file name; caption → the line above the screen.
SHOTS = [
    ("01-brew-live", "Watch every shot, live"),
    ("02-brew-manual", "Pour over, step by step"),
    ("03-profiles", "A profile for every bean"),
    ("04-history", "Remember every pour"),
    ("05-beans", "Track every bag & roaster"),
    ("06-grinder", "Dial in every grinder"),
]


@dataclass(frozen=True)
class Target:
    raw: str  # RAW subfolder
    folder: str  # listing subfolder
    width: int
    height: int
    caption_pt: int  # Newsreader point size
    caption_top: int  # y of the caption's cap height (an "H" top)
    frame_x: int  # frame box: left edge and width; height follows the screen
    frame_y: int
    frame_w: int
    bezel: int
    radius: int


TARGETS = [
    Target("phone", "phoneScreenshots", 1440, 2880, 104, 213, 187, 394, 1066, 12, 64),
    Target(
        "tablet7", "sevenInchScreenshots", 1920, 1313, 125, 81, 154, 230, 1612, 14, 40
    ),
    Target(
        "tablet10", "tenInchScreenshots", 2560, 1752, 166, 109, 205, 307, 2150, 18, 52
    ),
]


def magick(*args: str, capture: bool = False) -> str:
    r = subprocess.run(["magick", *args], check=True, capture_output=capture, text=True)
    return r.stdout.strip() if capture else ""


def ensure_font() -> None:
    if not os.path.exists(FONT):
        print("downloading Newsreader…")
        urllib.request.urlretrieve(FONT_URL, FONT)


def caption_layer(text: str, t: Target, path: str) -> None:
    """The caption as a transparent strip the canvas width, cap height at `caption_top`.

    Shrinks the point size if a long caption would run past 90% of the width.
    """
    pt = t.caption_pt
    while True:
        w = int(
            magick(
                "-font",
                FONT,
                "-pointsize",
                str(pt),
                f"label:{text}",
                "-format",
                "%w",
                "info:",
                capture=True,
            )
        )
        if w <= t.width * 0.9 or pt <= 40:
            break
        pt -= 2
    # Where the cap height sits inside an untrimmed label: measure an "H" once.
    ink_top = int(
        magick(
            "-background",
            "black",
            "-fill",
            "white",
            "-font",
            FONT,
            "-pointsize",
            str(pt),
            "label:H",
            "-trim",
            "-format",
            "%Y",
            "info:",
            capture=True,
        ).lstrip("+")
        or 0
    )
    magick(
        "-background",
        "none",
        "-fill",
        CREAM,
        "-font",
        FONT,
        "-pointsize",
        str(pt),
        f"label:{text}",
        "-gravity",
        "north",
        "-background",
        "none",
        "-extent",
        f"{t.width}x",
        "-page",
        f"+0+{t.caption_top - ink_top}",
        path,
    )


def frame_one(raw: str, caption: str, t: Target, out: str, tmp: str) -> None:
    inner_w = t.frame_w - 2 * t.bezel
    screen = os.path.join(tmp, "screen.png")
    sw, sh = map(int, magick(raw, "-format", "%w %h", "info:", capture=True).split())
    inner_h = round(sh * inner_w / sw)
    r_in = max(0, t.radius - t.bezel)
    # The screen, scaled to the frame and cut to its inner corner radius.
    magick(
        raw,
        "-alpha",
        "off",
        "-filter",
        "Lanczos",
        "-resize",
        f"{inner_w}x{inner_h}!",
        "(",
        "-size",
        f"{inner_w}x{inner_h}",
        "xc:black",
        "-fill",
        "white",
        "-draw",
        f"roundrectangle 0,0,{inner_w - 1},{inner_h - 1},{r_in},{r_in}",
        ")",
        "-alpha",
        "off",
        "-compose",
        "CopyOpacity",
        "-composite",
        screen,
    )
    frame_h = inner_h + 2 * t.bezel
    if t.frame_y + frame_h > t.height:
        sys.exit(
            f"{raw}: screen too tall for {t.folder} ({t.frame_y + frame_h} > {t.height})"
        )
    cap = os.path.join(tmp, "caption.png")
    caption_layer(caption, t, cap)
    magick(
        "-size",
        f"{t.width}x{t.height}",
        f"xc:{COPPER}",
        "-fill",
        FRAME,
        "-draw",
        f"roundrectangle {t.frame_x},{t.frame_y},{t.frame_x + t.frame_w - 1},{t.frame_y + frame_h - 1},{t.radius},{t.radius}",
        screen,
        "-geometry",
        f"+{t.frame_x + t.bezel}+{t.frame_y + t.bezel}",
        "-compose",
        "over",
        "-composite",
        cap,
        "-compose",
        "over",
        "-layers",
        "flatten",
        "-alpha",
        "off",
        "-strip",
        "-define",
        "png:exclude-chunk=date,time",
        f"PNG24:{out}",
    )


def check(folder: str) -> bool:
    ok = True
    for t in TARGETS:
        d = os.path.join(folder, t.folder)
        names = sorted(f for f in os.listdir(d) if f.endswith(".png"))
        want = [f"{slug}.png" for slug, _ in SHOTS]
        if names != want:
            print(f"{t.folder}: files {names} != {want}")
            ok = False
        for n in names:
            info = magick(
                os.path.join(d, n),
                "-format",
                "%w %h %[channels]",
                "info:",
                capture=True,
            ).split()
            w, h, ch = int(info[0]), int(info[1]), info[2]
            bad = []
            if (w, h) != (t.width, t.height):
                bad.append(f"{w}×{h} (want {t.width}×{t.height})")
            if ch != "srgb":
                bad.append(f"channels {ch} (want opaque srgb)")
            if max(w, h) > 2 * min(w, h):
                bad.append("ratio over 2:1")
            print(
                f"{'BAD' if bad else 'ok '} {t.folder}/{n} {w}×{h} {ch} {'; '.join(bad)}"
            )
            ok = ok and not bad
    return ok


def main() -> None:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    ap.add_argument(
        "raw", nargs="?", help="folder with phone/, tablet7/, tablet10/ raw screenshots"
    )
    ap.add_argument(
        "--out", default=LISTING, help="listing images folder (default: %(default)s)"
    )
    ap.add_argument(
        "--check", action="store_true", help="only verify sizes, alpha and names"
    )
    a = ap.parse_args()
    if a.check:
        sys.exit(0 if check(a.out) else 1)
    if not a.raw:
        ap.error("RAW folder required")
    ensure_font()
    with tempfile.TemporaryDirectory() as tmp:
        for t in TARGETS:
            dst = os.path.join(a.out, t.folder)
            os.makedirs(dst, exist_ok=True)
            for slug, caption in SHOTS:
                raw = os.path.join(a.raw, t.raw, f"{slug}.png")
                if not os.path.exists(raw):
                    sys.exit(f"missing {raw}")
                frame_one(raw, caption, t, os.path.join(dst, f"{slug}.png"), tmp)
                print(f"framed {t.folder}/{slug}.png")
    sys.exit(0 if check(a.out) else 1)


if __name__ == "__main__":
    main()
