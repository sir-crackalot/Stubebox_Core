"""Renders the dragon brand images over the flip-card design.

Replaces every procedurally drawn flip-card mark with the white dragon logo
(tools/brand/dragon.png, transparent background) and the "Stube"/"box" texts
with "stube"/".box". Background colours, adaptive-icon XMLs and resource NAMES
stay untouched, so manifests, themes and Kotlin code keep working.

Needs Pillow (Termux: pkg install python-pillow).

    cp ~/logo.png tools/brand/dragon.png
    python3 tools/brand/render_dragon.py android --out core/src/main/res --font /system/fonts/Roboto-Regular.ttf
    python3 tools/brand/render_dragon.py stage --out core/src/main/res
    python3 tools/brand/render_dragon.py extras --out <app-repo>/extras/brand --font /system/fonts/Roboto-Regular.ttf

Then: git add -A, commit, tag, push (see release workflow). The old vector
XMLs that are replaced by PNGs are deleted by this script (same resource names,
so R references stay valid).
"""
import argparse
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from render_brand import PAL, PIXEL, DENSITIES, HEADER, rgb, font, wordmark, word_width  # noqa: E402
from PIL import Image, ImageDraw  # noqa: E402

TEXT = 'stube.box'
TEXT_PARTS = [('stube', '#FFFFFF'), ('.box', None)]  # None -> variant accent filled in


def acc(p):
    return p['acc']


def load_dragon(path):
    img = Image.open(path).convert('RGBA')
    w, h = img.size
    assert min(w, h) >= 96, f'dragon too small: {img.size}'
    return img


def dragon_fit(img, px, fill=0.66):
    """Dragon scaled so its longest side is fill*px, centred on transparent px canvas."""
    w, h = img.size
    s = fill * px / max(w, h)
    nw, nh = max(1, round(w * s)), max(1, round(h * s))
    out = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    out.alpha_composite(img.resize((nw, nh), Image.LANCZOS), ((px - nw) // 2, (px - nh) // 2))
    return out


def rm(path, removed):
    if os.path.exists(path):
        os.remove(path)
        removed.append(path)


def write_xml(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(HEADER + text)


def splash_name_text(p, w, h, font_path, tfont=None):
    """Launch-screen wordmark, 'stube' white + '.box' in the accent (transparent canvas)."""
    fp = tfont or font_path
    fpx = round(h * .45)
    f = font(fp, fpx)
    tr = -0.025 * fpx
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    b = f.getbbox(TEXT, anchor='ls')
    parts = [(t, c or acc(p)) for t, c in TEXT_PARTS]
    wordmark_ss(img, ((w - word_width(TEXT, f, tr)) / 2, h / 2 - (b[1] + b[3]) / 2),
               parts, fp, fpx, tr)
    return img


def wordmark_ss(img, xy, parts, font_path, fpx, tracking, scale=2):
    """Draws the wordmark 2x supersampled, then downscales: clean edges at small sizes."""
    f2 = font(font_path, fpx * scale)
    layer = Image.new('RGBA', (img.width * scale, img.height * scale), (0, 0, 0, 0))
    wordmark(ImageDraw.Draw(layer), (xy[0] * scale, xy[1] * scale), parts, f2, tracking * scale)
    img.alpha_composite(layer.resize((img.width, img.height), Image.LANCZOS), (0, 0))


def banner_dragon(p, w, h, font_path, dragon, tfont=None):
    """TV banner layout with the dragon + 'stube.box' (same geometry rules as banner())."""
    fp = tfont or font_path
    s = h / 180
    img = Image.new('RGBA', (w, h), rgb(p['field']))
    mk, gap = round(108 * s), round(108 * s * 0.26)
    fpx = round(48 * s)
    narrow = w / h <= 320 / 180 + .01
    while fpx > round(20 * s):
        f = font(fp, fpx)
        if mk + gap + word_width(TEXT, f, -1.2 * s) <= ((w - 2 * 26 * s) if narrow else (w * 0.62)):
            break
        fpx -= 1
    f = font(fp, fpx)
    cw = mk + gap + word_width(TEXT, f, -1.2 * s)
    x0 = round((w - cw) / 2) if w / h > 320 / 180 + .01 else round(26 * s)
    img.alpha_composite(dragon_fit(dragon, mk, fill=0.8), (x0, round((h - mk) / 2)))
    parts = [(t, c or acc(p)) for t, c in TEXT_PARTS]
    wordmark_ss(img, (x0 + mk + gap, 106 * s), parts, fp, fpx, -1.2 * s)
    return img


def lockup_dragon(p, mark_px, font_path, own='#FFFFFF', tv=None, pad=10, tfont=None):
    """Horizontal lockup for README logos: dragon + 'stube.box'."""
    fp = tfont or font_path
    f = font(fp, 150)
    tr = -0.025 * 150
    ww = word_width(TEXT, f, tr)
    gap = round(mark_px * .26)
    w = pad * 2 + mark_px + gap + int(ww) + 4
    h = pad * 2 + max(mark_px, int(150 * 1.2))
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    img.alpha_composite(dragon_fit(dragon_global, mark_px, fill=0.85), (pad, (h - mark_px) // 2))
    asc = f.getbbox(TEXT, anchor='ls')
    base = h / 2 - (asc[1] + asc[3]) / 2
    parts = [(t, (c or tv or acc(p)) if t != 'stube' else own) for t, c in TEXT_PARTS]
    wordmark_ss(img, (pad + mark_px + gap, base), parts, fp, 150, tr)
    return img


dragon_global = None


def android(res, font_path, dragon_path, tfont=None):
    global dragon_global
    dragon = dragon_global = load_dragon(dragon_path)
    removed, written = [], 0
    for key, p in PAL.items():
        for dens, k in DENSITIES.items():  # launcher foregrounds keep density structure + names
            d = os.path.join(res, f'mipmap-{dens}')
            os.makedirs(d, exist_ok=True)
            dragon_fit(dragon, round(108 * k)).save(os.path.join(d, f'stubebox_icon_{key}_foreground.png'))
            written += 1
        d = os.path.join(res, 'drawable-xxxhdpi')
        os.makedirs(d, exist_ok=True)
        dragon_fit(dragon, 256, fill=0.85).save(os.path.join(d, f'stubebox_mark_{key}.png'))
        dragon_fit(dragon, 128, fill=0.85).save(os.path.join(d, f'stubebox_mark_small_{key}.png'))
        dragon_fit(dragon, 432).save(os.path.join(d, f'stubebox_splash_static_{key}.png'))
        written += 3
        for n in (f'stubebox_mark_{key}.xml', f'stubebox_mark_small_{key}.xml',
                  f'stubebox_splash_static_{key}.xml', f'stubebox_splash_{key}.xml'):
            rm(os.path.join(res, 'drawable', n), removed)
        for dens, k in DENSITIES.items():  # launch-screen wordmark, text swapped
            d = os.path.join(res, f'drawable-{dens}')
            os.makedirs(d, exist_ok=True)
            splash_name_text(p, round(200 * k), round(80 * k), font_path, tfont).save(
                os.path.join(d, f'stubebox_splash_name_{key}.png'))
            written += 1
        d = os.path.join(res, 'drawable-xhdpi')
        os.makedirs(d, exist_ok=True)
        banner_dragon(p, 320, 180, font_path, dragon, tfont).convert('RGB').save(
            os.path.join(d, f'stubebox_banner_{key}.png'))
        written += 1
    # monochrome (themed icons) + notification: white dragon silhouette
    dragon_fit(dragon, 432).save(os.path.join(res, 'drawable-xxxhdpi', 'stubebox_icon_monochrome.png'))
    dragon_fit(dragon, 96, fill=0.8).save(os.path.join(res, 'drawable-xxxhdpi', 'stubebox_notification.png'))
    written += 2
    for n in ('stubebox_icon_monochrome.xml', 'stubebox_notification.xml'):
        rm(os.path.join(res, 'drawable', n), removed)
    # accent-triangle overlay retired with the card: transparent 4px PNGs (names kept)
    d = os.path.join(res, 'drawable-nodpi')
    os.makedirs(d, exist_ok=True)
    for n in ('stubebox_mark_play.png', 'stubebox_mark_play_small.png'):
        Image.new('RGBA', (4, 4), (0, 0, 0, 0)).save(os.path.join(d, n))
        written += 1
    for n in ('stubebox_mark_play.xml', 'stubebox_mark_play_small.xml'):
        rm(os.path.join(res, 'drawable', n), removed)
    print(f'android: {written} PNGs written, {len(removed)} obsolete XMLs removed')


def stage(res, dragon_path):
    dragon = load_dragon(dragon_path)
    removed, written = [], 0
    for dens, k in DENSITIES.items():
        dragon_fit(dragon, round(108 * k)).save(
            os.path.join(res, f'mipmap-{dens}', 'stubebox_icon_pixel_foreground.png'))
        written += 1
        stage_splash_name_text(round(200 * k), round(80 * k)).save(
            os.path.join(res, f'drawable-{dens}', 'stubebox_splash_name_pixel.png'))
        written += 1
    d = os.path.join(res, 'drawable-xxxhdpi')
    os.makedirs(d, exist_ok=True)
    dragon_fit(dragon, 256, fill=0.85).save(os.path.join(d, 'stubebox_mark_pixel.png'))
    dragon_fit(dragon, 128, fill=0.85).save(os.path.join(d, 'stubebox_mark_small_pixel.png'))
    dragon_fit(dragon, 432).save(os.path.join(d, 'stubebox_splash_static_pixel.png'))
    written += 3
    for n in ('stubebox_mark_pixel.xml', 'stubebox_mark_small_pixel.xml',
              'stubebox_splash_static_pixel.xml', 'stubebox_splash_pixel.xml'):
        rm(os.path.join(res, 'drawable', n), removed)
    banner_dragon(dict(field=PIXEL['bg'], acc=PIXEL['tri']), 320, 180, STAGE_FONT, dragon,
                STAGE_FONT_BOLD).convert('RGB').save(
        os.path.join(res, 'drawable-xhdpi', 'stubebox_banner_pixel.png'))
    written += 1
    print(f'stage: {written} PNGs written, {len(removed)} obsolete XMLs removed')


STAGE_FONT = None
STAGE_FONT_BOLD = None


def stage_splash_name_text(w, h):
    fp = STAGE_FONT_BOLD or STAGE_FONT
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    fpx = round(h * .62)
    f = font(fp, fpx)
    tr = -0.025 * fpx
    b = f.getbbox(TEXT, anchor='ls')
    parts = [(t, c or PIXEL['tri']) for t, c in TEXT_PARTS]
    wordmark_ss(img, ((w - word_width(TEXT, f, tr)) / 2, h / 2 - (b[1] + b[3]) / 2), parts, fp, fpx, tr)
    return img


def extras(root, font_path, dragon_path, tfont=None):
    global dragon_global
    dragon = dragon_global = load_dragon(dragon_path)
    n = 0
    d = os.path.join(root, 'app-icons')
    os.makedirs(d, exist_ok=True)
    for key in PAL:
        dragon_fit(dragon, 1024, fill=0.8).save(os.path.join(d, f'icon_{key}.png'))
        n += 1
    d = os.path.join(root, 'app-logos')
    os.makedirs(d, exist_ok=True)
    for key, p in PAL.items():
        lockup_dragon(p, 150, font_path, pad=10, tfont=tfont).save(os.path.join(d, f'logo_{key}.png'))
        lockup_dragon(p, 150, font_path, own='#16181D', tv=p['ui'], pad=10, tfont=tfont).save(
            os.path.join(d, f'logo_{key}_light.png'))
        n += 2
    d = os.path.join(root, 'tv-banners')
    os.makedirs(d, exist_ok=True)
    for key, p in PAL.items():
        banner_dragon(p, 1920, 384, font_path, dragon, tfont).convert('RGB').save(
            os.path.join(d, f'banner_{key}.png'))
        n += 1
    print(f'extras: {n} files written to {root}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('what', choices=['android', 'stage', 'extras'])
    ap.add_argument('--dragon', required=True)
    ap.add_argument('--font', default=None)
    ap.add_argument('--font-bold', default=None,
                    help='bold TTF for the wordmark (default: --font). Bold + 2x supersampling '
                         'keep small text crisp on TV screens.')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    global STAGE_FONT, STAGE_FONT_BOLD
    if a.what == 'android':
        if not a.font:
            ap.error('--font is required for android')
        android(a.out, a.font, a.dragon, a.font_bold)
    elif a.what == 'stage':
        STAGE_FONT = a.font or '/system/fonts/Roboto-Regular.ttf'
        STAGE_FONT_BOLD = a.font_bold or STAGE_FONT
        stage(a.out, a.dragon)
    else:
        if not a.font:
            ap.error('--font is required for extras')
        extras(a.out, a.font, a.dragon, a.font_bold)


if __name__ == '__main__':
    main()
