# -*- coding: utf-8 -*-
"""生成漫画测试 EPUB：3 章 x 每章 6 页竖版图片（模拟条漫），EPUB3 + nav 目录。
用于验证墨阅的漫画长条模式：零间距连续滚动 + 双指缩放 + 跨章自动衔接。
"""
import struct, zlib, zipfile, os, uuid

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testbooks")
os.makedirs(OUT, exist_ok=True)


def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def page_png(ch, idx):
    """一页 720x1120 的竖版'漫画页'：底色渐变 + 大号页码色块条"""
    w, h = 720, 1120
    base = [(38 + ch * 30) % 256, (34 + idx * 12) % 256, (60 + ch * 25) % 256]

    def f(x, y):
        # 上中下三段分镜条
        bands = [(80, 380), (420, 660), (700, 1040)]
        t = y / h
        r = int(base[0] * (1 - t) + 250 * t * 0.4)
        g = int(base[1] * (1 - t) + 250 * t * 0.4)
        b = int(base[2] * (1 - t) + 250 * t * 0.3)
        for (y1, y2) in bands:
            if y1 + ch * 4 <= y <= y2 - ch * 4:
                k = (x // 24 + idx * 3 + ch) % 7
                return (min(255, r + k * 8), min(255, g + k * 5), min(255, b + k * 6))
        return (max(0, r - 30), max(0, g - 30), max(0, b - 30))

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    rows = bytearray()
    for y in range(h):
        rows.append(0)
        for x in range(w):
            rows.extend(f(x, y))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b""))


CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

ch_items, ch_refs, nav_lis = [], [], []
images = []
n_ch = 8
pages_per_ch = 6
for c in range(1, n_ch + 1):
    ch_items.append(
        f'    <item id="ch{c}" href="text/ch{c}.xhtml" media-type="application/xhtml+xml"/>'
    )
    ch_refs.append(f'    <itemref idref="ch{c}"/>')
    nav_lis.append(f'<li><a href="text/ch{c}.xhtml">第{c}话</a></li>')
    for p in range(1, pages_per_ch + 1):
        images.append((f"OEBPS/images/c{c}_{p:02d}.png", page_png(c, p)))
    img_tags = "".join(
        f'<img src="../images/c{c}_{p:02d}.png" alt="p{p}"/>' for p in range(1, pages_per_ch + 1)
    )
    images.append((
        f"OEBPS/text/ch{c}.xhtml",
        f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第{c}话</title></head>
<body><h2>第{c}话</h2>{img_tags}</body></html>
""".encode(),
    ))

NAV = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目录</title></head><body>
<nav epub:type="toc"><h1>目录</h1><ol>
""" + "\n".join(nav_lis) + """
</ol></nav></body></html>
"""

OPF = f"""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="zh-CN">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:{uuid.uuid4()}</dc:identifier>
    <dc:title>测试条漫·三话合集</dc:title>
    <dc:creator>墨阅测试组</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-10-29T00:00:00Z</meta>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="cover-img" href="images/c1_01.png" media-type="image/png" properties="cover-image"/>
{chr(10).join(ch_items)}
  </manifest>
  <spine>
{chr(10).join(ch_refs)}
  </spine>
</package>
"""

path = os.path.join(OUT, "test_comic8.epub")
with zipfile.ZipFile(path, "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip")
    z.writestr("META-INF/container.xml", CONTAINER)
    z.writestr("OEBPS/content.opf", OPF)
    z.writestr("OEBPS/nav.xhtml", NAV)
    for name, data in images:
        z.writestr(name, data if isinstance(data, bytes) else data.encode())
print("wrote", path, os.path.getsize(path), "bytes")
