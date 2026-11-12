# -*- coding: utf-8 -*-
"""生成墨阅 iPad 版 PWA 图标（apple-touch-icon 180 / manifest 192、512）
用法：python make_icons.py
"""
import os

from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "www", "icons")

# 与书架封面占位同款渐变（见 index.html .cover）
TOP = (109, 91, 78)     # #6d5b4e
BOTTOM = (62, 52, 44)   # #3e342c

FONTS = [
    r"C:\Windows\Fonts\msyhbd.ttc",   # 微软雅黑 Bold
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",   # 黑体兜底
]


def load_font(size):
    for p in FONTS:
        if os.path.isfile(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                pass
    return ImageFont.load_default()


def gradient(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img


def make(size, glyph_ratio=0.52, out_name=None):
    img = gradient(size, size)
    d = ImageDraw.Draw(img)
    font = load_font(int(size * glyph_ratio))
    # 居中画「墨」，四角留白
    bbox = d.textbbox((0, 0), "墨", font=font)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (size - tw) / 2 - bbox[0]
    y = (size - th) / 2 - bbox[1]
    d.text((x, y), "墨", font=font, fill=(243, 233, 220))  # #f3e9dc
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, out_name or f"icon-{size}.png")
    img.save(path, "PNG")
    print("生成", path)


if __name__ == "__main__":
    make(180, out_name="apple-touch-icon.png")  # iOS 主屏图标（不透明、满幅）
    make(192)
    make(512)
