# -*- coding: utf-8 -*-
"""探测生成图的真实像素结构：alpha 是否真透明、背景什么色。"""
import sys
from PIL import Image

for p in sys.argv[1:]:
    im = Image.open(p)
    print("=" * 60)
    print(p)
    print("  mode:", im.mode, "size:", im.size)
    rgba = im.convert("RGBA")
    w, h = rgba.size
    corners = [(2, 2), (w - 3, 2), (2, h - 3), (w - 3, h - 3), (w // 2, h // 2)]
    for c in corners:
        print("   px", c, rgba.getpixel(c))
    alpha = rgba.split()[-1]
    print("  alpha min/max:", alpha.getextrema())
    hist = alpha.histogram()
    print("  alpha==0 count:", hist[0], " alpha==255:", hist[255])
    rgb = rgba.convert("RGB")
    import statistics
    lum = [sum(rgb.getpixel((x, y))) / 3 for x in range(0, w, 37) for y in range(0, h, 37)]
    print("  mean lum:", round(statistics.mean(lum), 1))
