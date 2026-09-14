# -*- coding: utf-8 -*-
"""
把 AI 生成图做成 Android 资源。

生成图的真实结构（已用 probe_assets.py 确认）：
- 图标：RGB 模式、纯黑底 (0,0,0) + 白色线稿 (253)。没有真 alpha，
  所以「亮度即 alpha」——黑=透明、白=不透明，直接用 L 通道当遮罩。
- 插画：白底 (250,250,249)，主体是浅蓝。按「离白距离」阈值化抠图。

同时裁掉右下角的「AI生成 WORKBUDDY」水印带。
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

ASSETS = r"C:\Users\Administrator\WorkBuddy\记账软件\_assets"
ICON_SRC = os.path.join(ASSETS, "App_launcher_icon_foreground_a_2026-09-14T12-03-30.png")
ILLUS_SRC = os.path.join(ASSETS, "Flat_vector_illustration_for_a_2026-09-14T12-05-28.png")
OUT = r"C:\Users\Administrator\WorkBuddy\记账软件\jizhang-native\app\src\main\res"

BRAND_TOP = (42, 90, 232)      # #2A5AE8
BRAND_BOTTOM = (94, 146, 255)  # #5E92FF

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
LEGACY_SIZE = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def trim_watermark(img, keep_ratio=0.86):
    """裁掉底部水印带。"""
    w, h = img.size
    return img.crop((0, 0, w, int(h * keep_ratio)))


def trim_square(img, upper_bias=0.5):
    """裁成正方形；非正方形时以上部为中心（主体在上方）。"""
    w, h = img.size
    if h > w:
        top = int((h - w) * upper_bias)
        return img.crop((0, top, w, top + w))
    if w > h:
        left = (w - h) // 2
        return img.crop((left, 0, left + h, h))
    return img


def luminance_cutout(img, lo=232.0, hi=246.0):
    """
    白底抠图：亮度 >= hi 视为透明，<= lo 视为不透明，中间线性过渡。
    返回 RGBA，RGB 原样保留（保持浅蓝配色）。
    """
    rgb = img.convert("RGB")
    lum = rgb.convert("L")
    w, h = lum.size
    src = lum.load()
    alpha = Image.new("L", (w, h), 0)
    dst = alpha.load()
    span = max(1.0, hi - lo)
    for y in range(h):
        for x in range(w):
            v = src[x, y]
            if v >= hi:
                a = 0
            elif v <= lo:
                a = 255
            else:
                a = int(255 * (hi - v) / span)
            dst[x, y] = a
    # 收缩 1px，抹掉白边
    alpha = alpha.filter(ImageFilter.MinFilter(3))
    out = rgb.convert("RGBA")
    out.putalpha(alpha)
    return out


def alpha_glyph(img):
    """图标：黑底白线 → 亮度当 alpha，RGB 强制纯白。"""
    rgb = img.convert("RGB")
    lum = rgb.convert("L")
    white = Image.new("RGB", rgb.size, (255, 255, 255))
    out = white.convert("RGBA")
    out.putalpha(lum)
    return out


def alpha_bbox(img, thresh=24):
    mask = img.split()[-1].point(lambda v: 255 if v > thresh else 0)
    return mask.getbbox()


def fit_center(img, canvas, ratio, resample=Image.LANCZOS):
    """把 img 的可见内容缩放到 canvas*ratio 并居中。"""
    box = alpha_bbox(img)
    glyph = img.crop(box) if box else img
    target = int(canvas * ratio)
    gw, gh = glyph.size
    scale = min(target / gw, target / gh)
    glyph = glyph.resize((max(1, round(gw * scale)), max(1, round(gh * scale))), resample)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(glyph, ((canvas - glyph.width) // 2, (canvas - glyph.height) // 2), glyph)
    return out


def diagonal_gradient(size, top, bottom, radius=None, circle=False):
    w, h = size
    grad = Image.new("RGB", (w, h))
    px = grad.load()
    for y in range(h):
        for x in range(w):
            t = (x / max(1, w - 1) + y / max(1, h - 1)) / 2.0
            px[x, y] = (
                round(top[0] + (bottom[0] - top[0]) * t),
                round(top[1] + (bottom[1] - top[1]) * t),
                round(top[2] + (bottom[2] - top[2]) * t),
            )
    mask = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(mask)
    if circle:
        d.ellipse([0, 0, w - 1, h - 1], fill=255)
    else:
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius or 0, fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(grad, (0, 0), mask)
    return out


def main():
    # ---------- launcher icon ----------
    raw = Image.open(ICON_SRC)
    raw = trim_square(trim_watermark(raw))
    glyph_master = alpha_glyph(raw)
    # 安全区：adaptive 前景 108dp 里只有中间 72dp(66.7%) 一定可见
    fg_master = fit_center(glyph_master, 1024, 0.64)

    for d, size in DENSITIES.items():
        d_dir = os.path.join(OUT, f"mipmap-{d}")
        os.makedirs(d_dir, exist_ok=True)

        fg_master.resize((size, size), Image.LANCZOS).save(
            os.path.join(d_dir, "ic_launcher_foreground.png")
        )

        ls = LEGACY_SIZE[d]
        inner = fg_master.resize((round(ls * 0.60), round(ls * 0.60)), Image.LANCZOS)
        pos = ((ls - inner.width) // 2, (ls - inner.height) // 2)

        sq = diagonal_gradient((ls, ls), BRAND_TOP, BRAND_BOTTOM, radius=round(ls * 0.23))
        sq.paste(inner, pos, inner)
        sq.save(os.path.join(d_dir, "ic_launcher.png"))

        ci = diagonal_gradient((ls, ls), BRAND_TOP, BRAND_BOTTOM, circle=True)
        ci.paste(inner, pos, inner)
        ci.save(os.path.join(d_dir, "ic_launcher_round.png"))

    # ---------- 空态插画 ----------
    illus = Image.open(ILLUS_SRC)
    illus = trim_square(trim_watermark(illus, keep_ratio=0.90), upper_bias=0.35)
    cut = luminance_cutout(illus)
    box = alpha_bbox(cut)
    if box:
        cut = cut.crop(box)

    # 192dp 的插图，五种密度都给一份，避免低密度机上糊
    ILLUS_DP = 192
    for d, k in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        target = round(ILLUS_DP * k)
        gw, gh = cut.size
        scale = min(target / gw, target / gh)
        piece = cut.resize((max(1, round(gw * scale)), max(1, round(gh * scale))), Image.LANCZOS)
        canvas = Image.new("RGBA", (target, target), (0, 0, 0, 0))
        canvas.paste(piece, ((target - piece.width) // 2, (target - piece.height) // 2), piece)
        d_dir = os.path.join(OUT, f"drawable-{d}")
        os.makedirs(d_dir, exist_ok=True)
        canvas.save(os.path.join(d_dir, "ic_empty_backup.png"))

    print("icon + illustration done")
    print("  fg master box:", alpha_bbox(fg_master))
    print("  illus master:", cut.size)


if __name__ == "__main__":
    main()
