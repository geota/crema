#!/usr/bin/env python3
"""
Crema brand mark — single source of truth generator.

The mark is a copper disc + a serif "C" (Newsreader). To stay font-INDEPENDENT
(so it renders identically in every shell + as favicon.ico/png/svg without the
fragile <foreignObject>+webfont trick), we bake the actual Newsreader glyph
outline into a plain SVG <path> with fontTools, then rasterise to png/ico.

Two optical variants (this is real CSS `font-optical-sizing`, not a hack):
  • canonical (opsz=34, wght=500) — refined display C. Used for the in-app mark,
    the scalable favicon.svg, and the large PWA icons (192/512). Matches the
    PWA favicon's 34px optical size.
  • small     (opsz=6,  wght=600) — bolder, simpler, slightly larger + centred.
    Used for the tiny raster favicons (16/32/48 + favicon.ico) where the refined
    C's thin terminals would mud out.

Layout: positioned by the exact CSS the shells use — font-size 0.529·disc,
flex-centred, line-height:1, letter-spacing -0.04em — using the font's own
advance + hhea metrics, so the baked C sits where the live font would render it.

Colours: disc #C7763B (copper-500), ink #1F1812 (fg-on-accent).

Usage:  python3 generate_mark.py            # writes crema-mark*.svg + core/brand.rs
                                            #   + Android brand vectors (logo + launcher)
        python3 generate_mark.py --assets   # + rsvg/magick → png/ico, incl. the
                                            #   512×512 Google Play store icon
                                            #   (needs both rsvg-convert + magick)

Requires: fonttools (pip install fonttools). Downloads Newsreader VF on first run.
"""
import os, subprocess, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "Newsreader.ttf")
FONT_URL = "https://github.com/google/fonts/raw/main/ofl/newsreader/Newsreader%5Bopsz,wght%5D.ttf"
DISC = 64.0
COPPER, INK = "#C7763B", "#1F1812"
CREAM, CREAM_MUTED = "#F4EDE0", "#E8DCC4"   # ink-50 / ink-100 — text on copper, coin face
FEATURE_W, FEATURE_H = 1024, 500            # Google Play feature graphic dimensions

def ensure_font():
    if not os.path.exists(FONT):
        print("downloading Newsreader VF…")
        urllib.request.urlretrieve(FONT_URL, FONT)

def bake(wght, opsz, *, target_h=None, cx=None, cy=None):
    """Return the C glyph as an SVG path 'd', positioned in the 64-unit disc.
    target_h/cx/cy override the CSS-layout placement (used for the small variant)."""
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.pens.boundsPen import BoundsPen
    f = TTFont(FONT); instantiateVariableFont(f, {"wght": wght, "opsz": opsz}, inplace=True)
    upm = f["head"].unitsPerEm; hA, hD = f["hhea"].ascent, f["hhea"].descent
    adv = f["hmtx"]["C"][0]
    gs = f.getGlyphSet(); g = gs["C"]; bp = BoundsPen(gs); g.draw(bp)
    xMin, yMin, xMax, yMax = bp.bounds
    if target_h is not None:                         # explicit placement (small variant)
        s = target_h / (yMax - yMin)
        tx = cx - s * (xMin + xMax) / 2.0
        ty = cy + s * (yMin + yMax) / 2.0
    else:                                            # faithful CSS layout (canonical)
        F = 0.529 * DISC; s = F / upm; LS = -0.04 * F
        tx = (DISC - (adv * s + LS)) / 2.0
        ty = DISC / 2.0 + s * (hA + hD) / 2.0        # baseline; C rides above → up-shift
    pen = SVGPathPen(gs); g.draw(TransformPen(pen, (s, 0, 0, -s, tx, ty)))
    return pen.getCommands()

def svg(d):
    """Disc mark — copper circle + C, transparent corners. Favicon + standard use."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">'
            f'<circle cx="32" cy="32" r="32" fill="{COPPER}"/>'
            f'<path fill="{INK}" d="{d}"/></svg>\n')

def svg_full(d):
    """Full-bleed mark — copper square (no transparency) + C. For PWA *maskable*
    icons + iOS apple-touch (the OS masks to circle/squircle/rounded-rect, so the
    bg must fill the square and the glyph must sit inside the ~80% safe zone)."""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" width="64" height="64">'
            f'<rect width="64" height="64" fill="{COPPER}"/>'
            f'<path fill="{INK}" d="{d}"/></svg>\n')

def bake_text(text, wght, opsz, size_px, *, tracking=0.0):
    """Bake a whole string to one SVG path 'd' (font-INDEPENDENT — the same glyph-
    outline trick the mark's "C" uses, so the wordmark needs no embedded font at
    render time). Glyphs are laid out left-to-right by their own hmtx advances.
    Baseline sits at y=0, left edge at x=0, y-down (SVG). Returns (d, advance_width)."""
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    f = TTFont(FONT); instantiateVariableFont(f, {"wght": wght, "opsz": opsz}, inplace=True)
    upm = f["head"].unitsPerEm; s = size_px / upm
    cmap = f.getBestCmap(); gs = f.getGlyphSet(); hmtx = f["hmtx"]
    pen = SVGPathPen(gs); x = 0.0
    for ch in text:
        gname = cmap.get(ord(ch))
        if gname is None:                            # space / unmapped → quarter-em gap
            x += 0.25 * size_px + tracking; continue
        gs[gname].draw(TransformPen(pen, (s, 0, 0, -s, x, 0.0)))
        x += hmtx[gname][0] * s + tracking
    return pen.getCommands(), x


def feature_graphic_svg(d_mask):
    """Google Play feature graphic (1024×500 listing banner): copper field, a cream
    'coin' of the mark on the left, and the baked Newsreader wordmark + tagline.
    No transparency. Reuses the centred maskable "C" path for the coin."""
    coin_scale = 300.0 / DISC                        # 64-unit artboard → 300px coin
    coin_tx = 250.0 - 32.0 * coin_scale              # centre the coin at (250,250)
    crema_d, _ = bake_text("Crema", 500, 72, 132)
    sub_d, _ = bake_text("for Decent Espresso", 500, 18, 40, tracking=0.5)
    tx = 470                                          # left edge of the text block
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{FEATURE_W}" height="{FEATURE_H}" '
            f'viewBox="0 0 {FEATURE_W} {FEATURE_H}">'
            f'<rect width="{FEATURE_W}" height="{FEATURE_H}" fill="{COPPER}"/>'
            f'<g transform="translate({coin_tx},{coin_tx}) scale({coin_scale})">'
            f'<circle cx="32" cy="32" r="32" fill="{CREAM}"/>'
            f'<path fill="{COPPER}" d="{d_mask}"/></g>'
            f'<path transform="translate({tx},258)" fill="{CREAM}" d="{crema_d}"/>'
            f'<path transform="translate({tx},318)" fill="{CREAM_MUTED}" d="{sub_d}"/>'
            f'</svg>\n')


CORE_BRAND_RS = os.path.normpath(os.path.join(HERE, "..", "core", "de1-domain", "src", "brand.rs"))

_RUST_TEMPLATE = r'''//! Crema brand mark — GENERATED by `brand/generate_mark.py`; do not edit by hand.
//!
//! The single source of truth for the Crema "C": the Newsreader-500 glyph baked
//! to a font-free SVG path (renders identically everywhere — no font, no
//! `<foreignObject>`). The web (`static/favicon.svg`, PWA icons) and Android
//! (`ic_crema_logo.xml`) generate their committed assets from this same path.
//! Kept as plain domain data (no FFI export) until a shell renders it at runtime.

/// Copper disc / app-icon background — `copper-500`.
pub const COPPER: &str = "__COPPER__";
/// Ink / glyph colour — `fg-on-accent`.
pub const INK: &str = "__INK__";
/// The disc-placement "C" path, in a `0 0 64 64` viewBox.
pub const MARK_PATH: &str = "__DISC__";
/// The full-bleed (maskable / app-icon) "C" path — centred + larger.
pub const MARK_PATH_MASKABLE: &str = "__MASK__";

/// Disc mark as a standalone SVG document (transparent corners; favicon + in-app).
pub fn mark_svg() -> String {
    format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" width=\"64\" height=\"64\"><circle cx=\"32\" cy=\"32\" r=\"32\" fill=\"{}\"/><path fill=\"{}\" d=\"{}\"/></svg>",
        COPPER, INK, MARK_PATH
    )
}

/// Full-bleed maskable mark (copper square + C) — PWA maskable / iOS / app icon.
pub fn mark_maskable_svg() -> String {
    format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\" width=\"64\" height=\"64\"><rect width=\"64\" height=\"64\" fill=\"{}\"/><path fill=\"{}\" d=\"{}\"/></svg>",
        COPPER, INK, MARK_PATH_MASKABLE
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn builds_valid_svg() {
        assert!(mark_svg().starts_with("<svg"));
        assert!(mark_svg().contains(MARK_PATH));
        assert!(mark_svg().contains(COPPER));
        assert!(mark_maskable_svg().contains("<rect"));
    }
}
'''

def write_core(d_disc, d_mask):
    """Emit the canonical mark into de1-domain as shared Rust source (no drift)."""
    if not os.path.isdir(os.path.dirname(CORE_BRAND_RS)):
        print(f"(skip core: {os.path.dirname(CORE_BRAND_RS)} not found)")
        return
    rs = (_RUST_TEMPLATE.replace("__COPPER__", COPPER).replace("__INK__", INK)
          .replace("__DISC__", d_disc).replace("__MASK__", d_mask))
    open(CORE_BRAND_RS, "w").write(rs)
    print(f"wrote {CORE_BRAND_RS}")


# --- Android brand vectors (in-app logo + launcher adaptive icon) --------------
# Both the logo and the launcher foreground embed a baked glyph path, so they MUST
# regenerate with the mark or they silently drift — the launcher "C" used to be a
# hand-placed copy of crema-mark-maskable.svg, and the logo cited a .scratch/ file
# as its "canonical source". Emitting them here makes generate_mark.py their single
# source of truth. Android <vector> has no <circle>, so the copper disc is an arc
# path. minSdk 31 ⇒ adaptive XML only (no legacy PNG mipmaps needed).
ANDROID_RES = os.path.normpath(os.path.join(HERE, "..", "android", "app", "src", "main", "res"))
DISC_CIRCLE_PATH = "M0,32 a32,32 0 1,0 64,0 a32,32 0 1,0 -64,0z"  # <circle cx=32 cy=32 r=32>


def _android_logo_xml(d_disc):
    """In-app logo (R.drawable.ic_crema_logo) — copper disc + C in a 64 viewport,
    the disc (off-centre CSS-layout) variant; mirrors crema-mark.svg."""
    return f'''<!--
  GENERATED by brand/generate_mark.py — do not edit by hand.

  Crema brand mark — font-INDEPENDENT vector (matches web/static + core source).
  The Newsreader-500 "C" (instantiated at opsz=34 to match the favicon's optical
  size) was extracted with fontTools and positioned by the exact CSS layout the
  shells use (font-size 0.529·disc, flex-centred, line-height:1, -0.04em). Pure
  paths — no font dependency. Disc {COPPER} (copper-500), ink {INK}.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="34dp"
    android:height="34dp"
    android:viewportWidth="64"
    android:viewportHeight="64">
    <path
        android:fillColor="{COPPER}"
        android:pathData="{DISC_CIRCLE_PATH}" />
    <path
        android:fillColor="{INK}"
        android:pathData="{d_disc}" />
</vector>
'''


def _android_launcher_foreground_xml(d_mask):
    """Adaptive-icon foreground — the maskable C mapped onto the central 72dp safe
    zone of the 108dp layer; doubles as the Android-13+ <monochrome> layer."""
    return f'''<!--
  GENERATED by brand/generate_mark.py — do not edit by hand.

  Launcher-icon foreground — the Crema "C", taken verbatim from the source of
  truth brand/crema-mark-maskable.svg (font-baked Newsreader-500 "C" at opsz-34,
  ink {INK}). The copper field is the adaptive-icon background layer.

  The mark's 64-unit artboard is mapped onto the adaptive icon's central 72dp
  SAFE ZONE (scale 72/64 = 1.125, offset (108-72)/2 = 18), NOT the full 108dp
  layer — Android only ever shows the central 72dp, so filling 108 would magnify
  the "C" to ~79% of the visible circle (too big / serifs clipped). At this
  mapping the visible viewport reproduces crema-mark-maskable.svg 1:1.
  Doubles as the Android-13+ themed (<monochrome>) layer.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="1.125"
        android:scaleY="1.125"
        android:translateX="18"
        android:translateY="18">
        <path
            android:fillColor="{INK}"
            android:pathData="{d_mask}" />
    </group>
</vector>
'''


def _android_launcher_background_xml():
    """Adaptive-icon background — copper, kept literal (not a theme colour) so the
    icon is identical regardless of light/dark or Material-You theming."""
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by brand/generate_mark.py — do not edit by hand.
     Launcher-icon background — copper-500 ({COPPER}), the brand mark's disc/square
     fill. Kept literal (not a theme colour) so the icon is identical regardless
     of light/dark or Material-You theming. -->
<resources>
    <color name="ic_launcher_background">{COPPER}</color>
</resources>
'''


def _android_adaptive_icon_xml():
    """The adaptive-icon wrapper (shared by ic_launcher + ic_launcher_round) — pure
    structure referencing the foreground/background layers above."""
    return '''<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by brand/generate_mark.py — do not edit by hand. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'''


def write_android(d_disc, d_mask):
    """Emit the Android brand vectors from the canonical paths so a mark regen
    propagates to Android with no hand-placed copies (in-app logo + launcher icon)."""
    if not os.path.isdir(ANDROID_RES):
        print(f"(skip android: {ANDROID_RES} not found)")
        return
    outputs = {
        ("drawable", "ic_crema_logo.xml"): _android_logo_xml(d_disc),
        ("drawable", "ic_launcher_foreground.xml"): _android_launcher_foreground_xml(d_mask),
        ("values", "ic_launcher_background.xml"): _android_launcher_background_xml(),
        ("mipmap-anydpi-v26", "ic_launcher.xml"): _android_adaptive_icon_xml(),
        ("mipmap-anydpi-v26", "ic_launcher_round.xml"): _android_adaptive_icon_xml(),
    }
    for (subdir, name), content in outputs.items():
        d = os.path.join(ANDROID_RES, subdir)
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, name), "w", encoding="utf-8").write(content)
        print(f"wrote android …/res/{subdir}/{name}")


def main():
    ensure_font()
    d_disc = bake(500, 34)                                  # off-centre CSS layout (favicon/disc)
    d_mask = bake(500, 34, target_h=34.0, cx=32.0, cy=32.5)  # centred + larger (square app icon)
    open(os.path.join(HERE, "crema-mark.svg"), "w").write(svg(d_disc))
    open(os.path.join(HERE, "crema-mark-maskable.svg"), "w").write(svg_full(d_mask))
    print("wrote crema-mark.svg (disc) + crema-mark-maskable.svg (full-bleed) — opsz-34")
    write_core(d_disc, d_mask)
    write_android(d_disc, d_mask)
    if "--assets" in sys.argv:
        out = os.path.join(HERE, "out"); os.makedirs(out, exist_ok=True)
        disc = os.path.join(HERE, "crema-mark.svg")
        mask = os.path.join(HERE, "crema-mark-maskable.svg")
        def render(src, size, dst):  # rsvg rasterises the SVG at the exact size
            subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), src, "-o", dst], check=True)
        # Favicon (disc, transparent corners) → multi-res .ico + 32px fallback
        for sz in (16, 32, 48): render(disc, sz, f"{out}/mark-{sz}.png")
        render(disc, 32,  f"{out}/favicon-32.png")
        subprocess.run(["magick", f"{out}/mark-16.png", f"{out}/mark-32.png", f"{out}/mark-48.png", f"{out}/favicon.ico"], check=True)
        # PWA install set (kept for future installable-PWA iteration):
        render(disc, 192, f"{out}/icon-192.png")            # "any" purpose
        render(disc, 512, f"{out}/icon-512.png")
        render(mask, 192, f"{out}/icon-maskable-192.png")   # "maskable" purpose (Android)
        render(mask, 512, f"{out}/icon-maskable-512.png")
        render(mask, 180, f"{out}/apple-touch-icon.png")    # iOS home screen
        render(mask, 120, f"{out}/oauth-logo-120.png")      # Google OAuth consent (square, PNG)
        # Google Play Store listing icon — the full-bleed mark the launcher shows,
        # so the store icon matches the installed app 1:1. Play's spec: PNG/JPEG,
        # 512×512, ≤1 MB. We flatten the alpha to a fully-opaque copper square
        # (Play wants an opaque square and applies its own corner rounding); the
        # flat copper+C is a few KB, far under the 1 MB cap.
        render(mask, 512, f"{out}/play-store-icon-512.png")
        subprocess.run(["magick", f"{out}/play-store-icon-512.png",
                        "-background", COPPER, "-alpha", "remove", "-alpha", "off",
                        f"{out}/play-store-icon-512.png"], check=True)
        # Google Play feature graphic — 1024×500 listing banner (PNG, ≤1 MB). Built
        # from the same baked mark + Newsreader wordmark (no embedded font needed),
        # then flattened to an opaque banner.
        feat_svg = os.path.join(out, "crema-feature-graphic.svg")
        open(feat_svg, "w").write(feature_graphic_svg(d_mask))
        subprocess.run(["rsvg-convert", "-w", str(FEATURE_W), "-h", str(FEATURE_H),
                        feat_svg, "-o", f"{out}/play-feature-graphic-1024x500.png"], check=True)
        subprocess.run(["magick", f"{out}/play-feature-graphic-1024x500.png",
                        "-background", COPPER, "-alpha", "remove", "-alpha", "off",
                        f"{out}/play-feature-graphic-1024x500.png"], check=True)
        print(f"rasterised → {out}/ : favicon.ico, favicon-32, icon-192/512, "
              f"icon-maskable-192/512, apple-touch-icon, oauth-logo-120, "
              f"play-store-icon-512, play-feature-graphic-1024x500 (all opsz-34)")

if __name__ == "__main__":
    main()
