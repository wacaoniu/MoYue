# -*- coding: utf-8 -*-
"""生成 PDF / TXT / CBZ 测试文件：
- test_sample.pdf：6 页 A4（不同底色矩形 + 页码文字，手工构造的合法 PDF）
- test_sample_gbk.txt：GBK 编码中文小说，含 第一章/第二章/第三章 结构
- test_sample.cbz：12 张竖版图的漫画压缩包
"""
import os
import struct
import zipfile
import zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testbooks")
os.makedirs(OUT, exist_ok=True)

# ── PDF ────────────────────────────────────────────────
def build_pdf(path, n=6):
    # 每页不同底色
    colors = [(0.92, 0.92, 0.95), (0.95, 0.92, 0.88), (0.9, 0.94, 0.9),
              (0.95, 0.9, 0.92), (0.9, 0.92, 0.95), (0.94, 0.94, 0.88)]
    objs = {}  # num -> body(str)

    objs[1] = "<< /Type /Catalog /Pages 2 0 R >>"
    kids = " ".join(f"{5 + 2 * i} 0 R" for i in range(n))
    objs[2] = f"<< /Type /Pages /Kids [{kids}] /Count {n} >>"
    objs[3] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
    objs[4] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>"

    for i in range(n):
        r, g, b = colors[i % len(colors)]
        content = (
            f"{r} {g} {b} rg 0 0 595 842 re f\n"
            f"0.2 0.2 0.2 rg 40 770 515 3 re f\n"
            f"BT /F2 34 Tf 60 780 Td (MoYue PDF Test) Tj ET\n"
            f"BT /F1 72 Tf 60 640 Td (Page {i + 1} / {n}) Tj ET\n"
            f"0.45 0.45 0.45 rg 60 560 475 4 re f\n"
            f"0.35 0.35 0.35 rg 60 480 475 4 re f\n"
            f"0.55 0.55 0.55 rg 60 400 475 4 re f\n"
            f"BT /F1 20 Tf 60 120 Td (moyue reader pdf render test page {i + 1}) Tj ET\n"
        )
        objs[5 + 2 * i] = (
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
            f"/Contents {6 + 2 * i} 0 R /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> >>"
        )
        objs[6 + 2 * i] = f"<< /Length {len(content)} >>\nstream\n{content}endstream"

    max_n = max(objs)
    body = "%PDF-1.4\n%\xe2\xe3\xcf\xd3\n"
    offsets = {}
    for num in range(1, max_n + 1):
        offsets[num] = len(body.encode("latin-1"))
        body += f"{num} 0 obj\n{objs[num]}\nendobj\n"
    xref_pos = len(body.encode("latin-1"))
    xref = f"xref\n0 {max_n + 1}\n0000000000 65535 f \n"
    for num in range(1, max_n + 1):
        xref += f"{offsets[num]:010d} 00000 n \n"
    body += xref + f"trailer\n<< /Size {max_n + 1} /Root 1 0 R >>\nstartxref\n{xref_pos}\n%%EOF"

    with open(path, "wb") as f:
        f.write(body.encode("latin-1"))
    print("wrote", path, os.path.getsize(path), "bytes")


build_pdf(os.path.join(OUT, "test_sample.pdf"))

# ── TXT (GBK) ─────────────────────────────────────────
txt = """　　这是一本用 GBK 编码的测试小说，用来验证墨阅的文本导入与编码识别。

第一章　风起

　　洛桑城外的风，一年四季只朝一个方向吹。
　　少年背着行囊走进城门的时候，守卫连眼皮都没有抬。这样的旅人，每天有几百个。
　　他在井边喝了一口水，井水很凉，凉得像三年前那个冬天。
　　“找一个人。”他对自己说，“找到他，就回家。”

第二章　灯下

　　城里的灯，一到夜里就全亮了。
　　他找了很多天，问了很多路，只有酒馆的老板娘多看了他一眼。
　　“你找的那个人，”她说，“十年前就走了。”
　　“去哪了？”
　　“往风去的方向。”

第三章　归途

　　原来答案一直就在出发的地方。
　　他往回走，风还是朝一个方向吹，只是这次，吹在背上。
　　少年笑了笑，把行囊紧了紧。
　　（全文完）
"""
with open(os.path.join(OUT, "test_sample_gbk.txt"), "wb") as f:
    f.write(txt.encode("gbk"))
print("wrote", os.path.join(OUT, "test_sample_gbk.txt"))

# ── CBZ ───────────────────────────────────────────────
def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def make_png(w, h, fn):
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    rows = bytearray()
    for y in range(h):
        rows.append(0)
        for x in range(w):
            rows.extend(fn(x, y))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b""))


path = os.path.join(OUT, "test_sample.cbz")
with zipfile.ZipFile(path, "w") as z:
    for i in range(12):
        wide = i in (2, 7)  # 第3、8页做成跨页大图
        w, h = (1240, 900) if wide else (600, 940)
        def f(x, y, i=i, w=w, h=h):
            return ((40 + i * 12) % 256, (x * 255 // w), (y * 255 // h))
        z.writestr(f"page_{i + 1:02d}.png", make_png(w, h, f))
print("wrote", path, os.path.getsize(path), "bytes")
