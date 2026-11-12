# -*- coding: utf-8 -*-
"""墨阅·电脑版 —— 本地电子书阅读服务器（仅用 Python 标准库）

用法：python moyue_desktop.py  （或双击 启动墨阅.bat）
然后浏览器自动打开 http://127.0.0.1:8756

支持格式：EPUB 2/3、TXT（GBK/UTF-8 自动识别+自动分章）、CBZ（漫画压缩包）、PDF（浏览器内 pdf.js 渲染）
收书箱：脚本目录下的「收书箱」文件夹，放入电子书自动入库
"""
import html as htmllib
import io
import json
import os
import re
import struct
import threading
import time
import uuid
import webbrowser
import zipfile
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse, parse_qs

PORT = 8756
BASE = os.path.dirname(os.path.abspath(__file__))
BOOKS = os.path.join(BASE, "books")
INBOX = os.path.join(BASE, "收书箱")
WWW = os.path.join(BASE, "www")
os.makedirs(BOOKS, exist_ok=True)
os.makedirs(INBOX, exist_ok=True)

_lock = threading.Lock()


# ════════════════════════ 工具 ════════════════════════

def img_size(data):
    """读图片头部宽高：JPEG/PNG/GIF/WebP"""
    try:
        if len(data) >= 24 and data[:2] == b"\x89P":
            w = struct.unpack(">I", data[16:20])[0]
            h = struct.unpack(">I", data[20:24])[0]
            if w and h:
                return w, h
        if len(data) >= 10 and data[:2] == b"\xff\xd8":
            i = 2
            while i + 9 < len(data):
                if data[i] != 0xFF:
                    i += 1
                    continue
                mk = data[i + 1]
                if mk in (0x01, 0xD8) or 0xD0 <= mk <= 0xD7:
                    i += 2
                    continue
                if mk == 0xD9 or mk == 0xDA:
                    break
                seg = struct.unpack(">H", data[i + 2:i + 4])[0]
                if 0xC0 <= mk <= 0xCF and mk not in (0xC4, 0xC8, 0xCC):
                    h = struct.unpack(">H", data[i + 5:i + 7])[0]
                    w = struct.unpack(">H", data[i + 7:i + 9])[0]
                    if w and h:
                        return w, h
                    break
                i += 2 + seg
        if len(data) >= 10 and data[:4] == b"GIF8":
            w, h = struct.unpack("<HH", data[6:10])
            if w and h:
                return w, h
        if len(data) >= 30 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
            if data[12:16] == b"VP8X":
                w = 1 + int.from_bytes(data[24:27], "little")
                h = 1 + int.from_bytes(data[27:30], "little")
                return w, h
            if data[12:16] == b"VP8 " and data[23:26] == b"\x9d\x01\x2a":
                w = struct.unpack("<H", data[26:28])[0] & 0x3FFF
                h = struct.unpack("<H", data[28:30])[0] & 0x3FFF
                return w, h
            if data[12:16] == b"VP8L" and data[20:21] == b"\x2f":
                bits = int.from_bytes(data[21:25], "little")
                return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    except Exception:
        pass
    return None


def natural_key(s):
    return [(0, int(t)) if t.isdigit() else (1, t.lower())
            for t in re.split(r"(\d+)", s) if t]


def smart_decode(data):
    """按头部声明/内容探测解码：UTF-8 优先，GB 系自动回退（老书兼容）"""
    head = data[:1024].decode("ascii", "replace").lower()
    m = re.search(r'encoding="([a-z0-9_\-]+)"|charset=["\' ]*([a-z0-9_\-]+)', head)
    enc = (m.group(1) or m.group(2)) if m else ""
    if enc and ("gb" in enc):
        try:
            return data.decode("gb18030")
        except Exception:
            pass
    try:
        return data.decode("utf-8")
    except Exception:
        try:
            return data.decode("gb18030")
        except Exception:
            return data.decode("utf-8", "replace")


def norm_rel(base, href):
    """把书内相对链接解析成书内相对路径（同安卓版逻辑）"""
    p = unquote(href.split("#")[0]).replace("\\", "/")
    combined = (base + "/" + p) if base else p
    stack = []
    for part in combined.split("/"):
        if not part or part == ".":
            continue
        if part == "..":
            if stack:
                stack.pop()
        else:
            stack.append(part)
    return "/".join(stack)


def zip_name(info):
    """GBK 打包的 zip 文件名修正（zipfile 对无 UTF-8 标志的名字按 cp437 解）"""
    if info.flag_bits & 0x800:
        return info.filename
    try:
        return info.filename.encode("cp437").decode("gbk")
    except Exception:
        return info.filename


# ════════════════════════ 各格式导入 ════════════════════════

def detect_format(data, name):
    if data[:5] == b"%PDF-":
        return "pdf"
    if data[:2] == b"PK":
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                names = [zip_name(i) for i in z.infolist()]
                if "META-INF/container.xml" in names or any(n.lower().endswith(".opf") for n in names):
                    return "epub"
        except Exception:
            pass
        return "cbz"
    return "txt"


def import_epub(zf, bdir):
    names = {}
    broken = 0
    for info in zf.infolist():
        if info.is_dir():
            continue
        rel = zip_name(info).lstrip("/")
        out = os.path.join(bdir, *rel.split("/"))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        try:
            with zf.open(info) as src, open(out, "wb") as dst:
                dst.write(src.read())
            names[rel.lower()] = rel
        except Exception:
            # 损坏条目跳过（与安卓版一致），能解多少是多少
            broken += 1
            try:
                os.remove(out)
            except OSError:
                pass

    def fix(p):
        return names.get(p.lower(), p)

    def read_text(rel):
        try:
            return smart_decode(open(os.path.join(bdir, *rel.split("/")), "rb").read())
        except Exception:
            return ""

    container = fix("META-INF/container.xml")
    opf_rel = None
    m = re.search(r'full-path="([^"]+)"', read_text(container) if os.path.isfile(
        os.path.join(bdir, *container.split("/"))) else "")
    if m:
        opf_rel = fix(norm_rel("", m.group(1)))
    if not opf_rel or not os.path.isfile(os.path.join(bdir, *opf_rel.split("/"))):
        for root, _, fs in os.walk(bdir):
            for f in fs:
                if f.lower().endswith(".opf"):
                    opf_rel = os.path.relpath(os.path.join(root, f), bdir).replace("\\", "/")
                    break
    if not opf_rel:
        raise ValueError("不是有效的 EPUB（缺少 OPF）")

    opf_dir = opf_rel.rsplit("/", 1)[0] if "/" in opf_rel else ""
    tree = ET.fromstring(read_text(opf_rel))
    manifest, spine_refs, meta = {}, [], {"title": "未命名", "author": "未知作者"}
    in_meta = False
    for el in tree.iter():
        tag = el.tag.split("}")[-1]
        if tag == "metadata":
            in_meta = True
        if tag == "title" and in_meta and el.text and el.text.strip():
            meta["title"] = el.text.strip()
        if tag == "creator" and in_meta and el.text and el.text.strip():
            meta["author"] = el.text.strip()
        if tag == "item" and el.get("id"):
            manifest[el.get("id")] = {
                "href": el.get("href", ""), "mediaType": el.get("media-type", ""),
                "properties": el.get("properties", ""),
            }
        if tag == "itemref" and el.get("idref"):
            spine_refs.append(el.get("idref"))
    spine = [{"href": fix(norm_rel(opf_dir, manifest[r]["href"])),
              "mediaType": manifest[r]["mediaType"]}
             for r in spine_refs if r in manifest]
    if not spine:
        raise ValueError("EPUB 阅读顺序为空")

    href_to_idx = {}
    for i, s in enumerate(spine):
        href_to_idx.setdefault(s["href"], i)

    # 目录：nav 优先，其次 ncx
    toc = []
    nav_item = next((v for v in manifest.values() if "nav" in (v.get("properties") or "")), None)
    if nav_item:
        toc = parse_nav(read_text(fix(norm_rel(opf_dir, nav_item["href"]))), opf_dir)
    if not toc:
        ncx = next((v for v in manifest.values() if "dtbncx" in v["mediaType"]), None)
        if ncx:
            toc = parse_ncx(read_text(fix(norm_rel(opf_dir, ncx["href"]))), opf_dir)
    for t in toc:
        t["href"] = fix(t["href"])
        t["spineIndex"] = href_to_idx.get(t["href"], -1)
    toc = [t for t in toc if t["spineIndex"] >= 0]
    if not toc:
        toc = [{"title": f"第 {i + 1} 节", "href": s["href"], "level": 0, "spineIndex": i}
               for i, s in enumerate(spine)]

    cover = None
    prop = next((v for v in manifest.values() if "cover-image" in (v.get("properties") or "")), None)
    if prop:
        cover = fix(norm_rel(opf_dir, prop["href"]))
    else:
        for v in manifest.values():
            if v["mediaType"].startswith("image/") and "cover" in v["href"].lower():
                cover = fix(norm_rel(opf_dir, v["href"]))
                break

    # 漫画识别：抽样 spine
    sample = spine if len(spine) <= 24 else [spine[int(i * len(spine) / 20)] for i in range(20)]
    img_pages = 0
    for s in sample:
        f = os.path.join(bdir, *s["href"].split("/"))
        if not os.path.isfile(f):
            continue
        if s["mediaType"].startswith("image/"):
            img_pages += 1
        elif "html" in s["mediaType"]:
            t = read_text(s["href"])
            if ("<img" in t or re.search(r"<image[^>]*?href", t, re.I)) and \
                    len(re.sub(r"<[^>]*>|\s", "", t)) <= 80:
                img_pages += 1
    mode = "comic" if len(sample) >= 2 and img_pages / len(sample) >= 0.6 else "text"
    return {"title": meta["title"], "author": meta["author"], "format": "epub",
            "mode": mode, "spine": spine, "toc": toc, "pages": None, "cover": cover}


def parse_nav(xml_text, base):
    toc = []
    try:
        root = ET.fromstring(re.sub(r"\sxmlns(:\w+)?=\"[^\"]+\"", "", xml_text))
    except Exception:
        return toc

    def walk(ol, level):
        for li in ol.findall("li"):
            a = li.find("a")
            if a is not None and a.get("href"):
                toc.append({"title": (a.text or "").strip() or "(无题)",
                            "href": norm_rel(base, a.get("href")), "level": level, "spineIndex": -1})
            sub = li.find("ol")
            if sub is not None:
                walk(sub, level + 1)

    for nav in root.iter("nav"):
        if nav.get("type") == "toc" or nav.get("{http://www.idpf.org/2007/ops}type") == "toc":
            for ol in nav.findall("ol"):
                walk(ol, 0)
            break
    return toc


def parse_ncx(xml_text, base):
    toc = []

    def walk(np, level):
        for child in np:
            if child.tag.split("}")[-1] == "navPoint":
                label = ""
                src = ""
                for x in child.iter():
                    t = x.tag.split("}")[-1]
                    if t == "text" and not label and x.text:
                        label = x.text.strip()
                    if t == "content" and x.get("src"):
                        src = x.get("src")
                if label and src:
                    toc.append({"title": label, "href": norm_rel(base, src),
                                "level": level, "spineIndex": -1})
                walk(child, level + 1)

    try:
        root = ET.fromstring(xml_text)
        for nm in root.iter():
            if nm.tag.split("}")[-1] == "navMap":
                walk(nm, 0)
                break
    except Exception:
        pass
    return toc


CHAPTER_RE = re.compile(
    r"^\s{0,8}(第[0-9零〇一二三四五六七八九十百千万两]{1,14}[章节卷回部集]{1,3}[\s　]?[^\n]{0,30}"
    r"|(?:Chapter|CHAPTER)\s*\d+[^\n]{0,30}"
    r"|(?:序章|楔子|序言|前言|后记|尾声|终章|番外[^\n]{0,12}))\s*$")


def import_txt(data, bdir, title):
    text = None
    for enc in ("utf-8-sig", "utf-16", "utf-8", "gb18030"):
        try:
            text = data.decode(enc)
            break
        except Exception:
            continue
    if text is None:
        text = data.decode("utf-8", "replace")
    lines = text.split("\n")
    heads = []
    for i, line in enumerate(lines):
        t = line.strip("\r \t　")
        if 2 <= len(t) <= 40 and CHAPTER_RE.match(t):
            heads.append((i, t))
    if heads:
        chunks, start = [], 0
        for i, _ in heads:
            if i > start:
                chunks.append((start, i - 1))
            start = i
        if start < len(lines):
            chunks.append((start, len(lines) - 1))
    else:
        chunks = [(s, min(s + 1499, len(lines) - 1)) for s in range(0, len(lines), 1500)]
    chunks = [c for c in chunks if c[1] >= c[0]]
    if not chunks:
        raise ValueError("文件内容为空")

    spine, toc = [], []
    tdir = os.path.join(bdir, "text")
    os.makedirs(tdir, exist_ok=True)
    for idx, (a, b) in enumerate(chunks):
        heading = next((t for i, t in heads if i == a), None)
        title2 = heading or (f"片段 {idx + 1}" if heads else f"第 {idx + 1} 部分")
        parts = ["<html><head><meta charset='utf-8'><style>body{font-family:serif;line-height:1.8;"
                 "padding:24px 6%}p{text-indent:2em;margin:.4em 0}h2{text-align:center}</style></head><body>"]
        if heading:
            parts.append(f"<h2>{htmllib.escape(heading)}</h2>")
        for i in range(a, b + 1):
            p = lines[i].strip("\r").strip()
            if p:
                parts.append(f"<p>{htmllib.escape(p)}</p>")
        parts.append("</body></html>")
        rel = f"text/c{idx}.html"
        open(os.path.join(bdir, rel), "w", encoding="utf-8").write("".join(parts))
        spine.append({"href": rel, "mediaType": "text/html"})
        toc.append({"title": title2, "href": rel, "level": 0, "spineIndex": idx})
    return {"title": title, "author": "文本文档", "format": "txt", "mode": "text",
            "spine": spine, "toc": toc, "pages": None, "cover": None}


def import_cbz(zf, bdir, title):
    imgs = []
    for info in zf.infolist():
        if info.is_dir():
            continue
        rel = zip_name(info).lstrip("/")
        ext = rel.rsplit(".", 1)[-1].lower() if "." in rel else ""
        if ext not in ("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif"):
            continue
        out = os.path.join(bdir, *rel.split("/"))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        data = zf.read(info)
        open(out, "wb").write(data)
        sz = img_size(data) or (3, 4)
        imgs.append((rel, sz))
    if not imgs:
        raise ValueError("压缩包里没有图片，不是有效的 CBZ")
    imgs.sort(key=lambda x: natural_key(x[0]))
    pages = [{"src": r, "w": w, "h": h} for r, (w, h) in imgs]
    n = len(pages)
    spine, toc = [], []
    for s in range(0, n, 20):
        e = min(s + 19, n - 1)
        spine.append({"href": f"chunk/{s}-{e}", "mediaType": "x"})
        toc.append({"title": f"第 {s + 1} 页", "href": spine[-1]["href"],
                    "level": 0, "spineIndex": len(spine) - 1})
    return {"title": title, "author": "CBZ 漫画", "format": "cbz", "mode": "comic",
            "spine": spine, "toc": toc, "pages": pages, "cover": pages[0]["src"]}


def import_book_bytes(data, name):
    fmt = detect_format(data, name)
    bid = uuid.uuid4().hex[:16]
    bdir = os.path.join(BOOKS, bid)
    os.makedirs(bdir, exist_ok=True)
    title = os.path.splitext(os.path.basename(name or "未命名"))[0] or "未命名"
    try:
        if fmt == "pdf":
            open(os.path.join(bdir, "source.pdf"), "wb").write(data)
            info = {"title": title, "author": "PDF 文档", "format": "pdf", "mode": "pdf",
                    "spine": [], "toc": [], "pages": None, "cover": None}
        elif fmt == "epub":
            try:
                info = import_epub(zipfile.ZipFile(io.BytesIO(data)), bdir)
            except ValueError:
                raise
            except Exception:
                info = import_epub(zipfile.ZipFile(io.BytesIO(data)), bdir)
        elif fmt == "cbz":
            info = import_cbz(zipfile.ZipFile(io.BytesIO(data)), bdir, title)
        else:
            info = import_txt(data, bdir, title)
    except Exception:
        import shutil
        shutil.rmtree(bdir, ignore_errors=True)
        raise
    meta = {**info, "id": bid, "i": 0, "r": 0.0, "spread": 0, "merge": 0, "seam": 1, "page": -1,
            "addedAt": int(time.time() * 1000), "lastReadAt": 0}
    save_meta(meta)
    return meta


def save_meta(meta):
    with _lock:
        p = os.path.join(BOOKS, meta["id"], "meta.json")
        tmp = p + ".tmp"
        json.dump(meta, open(tmp, "w", encoding="utf-8"), ensure_ascii=False)
        os.replace(tmp, p)


def load_books():
    out = []
    for d in os.listdir(BOOKS):
        p = os.path.join(BOOKS, d, "meta.json")
        if os.path.isfile(p):
            try:
                out.append(json.load(open(p, encoding="utf-8")))
            except Exception:
                pass
    out.sort(key=lambda m: m.get("lastReadAt") or m.get("addedAt") or 0, reverse=True)
    return out


def scan_inbox():
    n = 0
    for f in sorted(os.listdir(INBOX)):
        fp = os.path.join(INBOX, f)
        if not os.path.isfile(fp):
            continue
        ext = os.path.splitext(f)[1].lower()
        if ext not in (".epub", ".txt", ".cbz", ".pdf"):
            continue
        try:
            import_book_bytes(open(fp, "rb").read(), f)
            os.remove(fp)
            n += 1
        except Exception as e:
            print(f"[收书箱] 导入失败 {f}: {e}")
    return n


def chapter_images(bdir, item):
    """提取一个 spine 章节里的图片：[(rel_path, w, h), ...]"""
    f = os.path.join(bdir, *item["href"].split("/"))
    base = item["href"].rsplit("/", 1)[0] if "/" in item["href"] else ""
    try:
        text = smart_decode(open(f, "rb").read())
    except Exception:
        return []
    found = [(m.start(), m.group(1)) for m in re.finditer(
        r"<img[^>]*?\ssrc\s*=\s*[\"']([^\"']+)[\"']", text, re.I)]
    found += [(m.start(), m.group(1)) for m in re.finditer(
        r"<image[^>]*?(?:xlink:)?href\s*=\s*[\"']([^\"']+)[\"']", text, re.I)]
    found.sort()
    out = []
    for _, src in found:
        rel = norm_rel(base, src)
        fp = os.path.join(bdir, *rel.split("/"))
        if os.path.isfile(fp):
            sz = img_size(open(fp, "rb").read(131072)) or (3, 4)
            out.append((rel, sz[0], sz[1]))
    return out


def same_size(w1, h1, w2, h2):
    """左右半页常有 1~2px 扫描差，配对尺寸判定带容差"""
    if not (w1 and h1 and w2 and h2):
        return False
    return abs(w1 - w2) <= max(4, w1 // 100) and abs(h1 - h2) <= max(4, h1 // 100)


def ensure_epub_flat(meta):
    """EPUB 漫画开双页合并时：摊平全书图片为 pages 流 + 20 页分章 spine（原结构备份，关合并时还原）"""
    if meta.get("format") != "epub" or meta.get("mode") != "comic" or meta.get("chunked"):
        return meta
    bdir = os.path.join(BOOKS, meta["id"])
    pages = []
    for item in meta["spine"]:
        for rel, w, h in chapter_images(bdir, item):
            pages.append({"src": rel, "w": w, "h": h})
    if not pages:
        return meta
    spine, toc = [], []
    n = len(pages)
    for st in range(0, n, 20):
        e = min(st + 19, n - 1)
        spine.append({"href": "chunk/%d-%d" % (st, e), "mediaType": "x"})
        toc.append({"title": "第 %d 页" % (st + 1), "href": spine[-1]["href"],
                    "level": 0, "spineIndex": len(spine) - 1})
    meta = {**meta, "pages": pages, "spine_orig": meta["spine"],
            "toc_orig": meta.get("toc", []), "spine": spine, "toc": toc, "chunked": True}
    save_meta(meta)
    return meta


def restore_epub_orig(meta):
    if meta.get("chunked"):
        meta = {**meta, "spine": meta.get("spine_orig") or meta["spine"],
                "toc": meta.get("toc_orig") or meta.get("toc", []),
                "chunked": False, "pages": None}
        save_meta(meta)
    return meta


def comic_inner(meta, idx):
    """生成一章的长条 HTML（含双页合并 / 宽页拆分 / 页间距）"""
    spread = meta.get("spread", 0)
    merge = meta.get("merge", 0)
    seam = meta.get("seam", 1)

    def single_img(src, w, h, pg=-1):
        ar = f" style='aspect-ratio:{w}/{h}'" if w and h else ""
        pga = f" data-pg='{pg}'" if pg >= 0 else ""
        # 不用 lazy：桌面端图片就在本地磁盘，立即加载零开销，
        # 且避免个别环境 IntersectionObserver 不触发导致整页空白
        return f"<img src='{src}'{ar}{pga}>"

    def img_tag(src, w, h, pg=-1):
        body = single_img(src, w, h, pg)
        if spread and w and h and w / h >= 1.1:
            pga = f" data-pg='{pg}'" if pg >= 0 else ""
            if spread == 2:
                return f"<div class='split-half second'{pga}>{body}</div><div class='split-half'{pga}>{body}</div>"
            return f"<div class='split-half'{pga}>{body}</div><div class='split-half second'{pga}>{body}</div>"
        return body

    def render_pages(lst, base_slot):
        """双页合并：相邻同尺寸竖版页拼成一行跨页。
        用“槽位”模型对齐：跨页大图占 2 槽（它本身就是一整个跨页），竖版单页占 1 槽；
        槽位为偶的竖页与其后同尺寸竖页配对。"""
        on = merge > 0
        rtl = merge in (2, 4)
        wide = lambda w, h: bool(w and h and w / h >= 1.1)
        out, i, slot = [], 0, base_slot
        while i < len(lst):
            src, w, h = lst[i][:3]
            pg = lst[i][3] if len(lst[i]) > 3 else -1
            if not on:
                out.append(img_tag(src, w, h, pg))
                i += 1
                continue
            if wide(w, h):
                out.append(single_img(src, w, h, pg))
                slot += 2
                i += 1
                continue
            if slot % 2 == 0 and i + 1 < len(lst):
                n2 = lst[i + 1]
                s2, w2, h2 = n2[:3]
                pg2 = n2[3] if len(n2) > 3 else -1
                if not wide(w2, h2) and same_size(w, h, w2, h2):
                    a, b = single_img(src, w, h, pg), single_img(s2, w2, h2, pg2)
                    pga = f" data-pg='{pg}'" if pg >= 0 else ""
                    cls = "pair seam%d" % seam
                    out.append(f"<div class='{cls}'{pga}>{b}{a}</div>" if rtl else f"<div class='{cls}'{pga}>{a}{b}</div>")
                    slot += 2
                    i += 2
                    continue
            out.append(single_img(src, w, h, pg))
            slot += 1
            i += 1
        return "".join(out)

    def slot_of(arr, x, off):
        total = off
        for p in arr[:x]:
            w, h = (p["w"], p["h"]) if isinstance(p, dict) else (p[1], p[2])
            total += 2 if (w and h and w / h >= 1.1) else 1
        return total

    off = 1 if merge in (3, 4) else 0

    bdir = os.path.join(BOOKS, meta["id"])
    item = meta["spine"][idx]
    pages = meta.get("pages")
    if pages and (meta.get("format") in ("pdf", "cbz") or meta.get("chunked")):
        m = re.match(r"chunk/(\d+)-(\d+)", item["href"])
        if m:
            s = int(m.group(1))
            e = min(int(m.group(2)), len(pages) - 1)
            if s <= e:
                if merge > 0:
                    # 跨 20 页分章边界的配对：跳过被上一章借走的页 + 借下一页凑完整组（按槽位对齐）
                    def is_wide_p(p):
                        return p["w"] and p["h"] and p["w"] / p["h"] >= 1.1

                    def same_pair(a, b):
                        return not is_wide_p(a) and not is_wide_p(b) and \
                            same_size(a["w"], a["h"], b["w"], b["h"])

                    while s <= e and slot_of(pages, s, off) % 2 == 1 and s > 0 \
                            and same_pair(pages[s - 1], pages[s]):
                        s += 1
                    if e < len(pages) - 1 and slot_of(pages, e + 1, off) % 2 == 1 \
                            and same_pair(pages[e], pages[e + 1]):
                        e += 1
                    lst = [(f"/b/{meta['id']}/f/{p['src']}", p["w"], p["h"], s + k)
                           for k, p in enumerate(pages[s:e + 1])]
                    return render_pages(lst, slot_of(pages, s, off))
                lst = [(f"/b/{meta['id']}/f/{p['src']}", p["w"], p["h"], s + k)
                       for k, p in enumerate(pages[s:e + 1])]
                return "".join(img_tag(*t) for t in lst)
        return "<div class='txtpage'>（本章没有可显示的内容）</div>"
    # epub 章节：提取图片
    lst = [(f"/b/{meta['id']}/f/{rel}", w, h) for rel, w, h in chapter_images(bdir, item)]
    if lst:
        return "".join(img_tag(s2, w2, h2) for s2, w2, h2 in lst)
    return f"<iframe class='txtpage-frame' src='/b/{meta['id']}/f/{item['href']}'></iframe>"


# ════════════════════════ HTTP ════════════════════════

MIME = {".html": "text/html", ".xhtml": "application/xhtml+xml", ".htm": "text/html",
        ".css": "text/css", ".js": "application/javascript", ".jpg": "image/jpeg",
        ".jpeg": "image/jpeg", ".png": "image/png", ".gif": "image/gif",
        ".webp": "image/webp", ".bmp": "image/bmp", ".svg": "image/svg+xml",
        ".ttf": "font/ttf", ".otf": "font/otf", ".opf": "text/xml", ".ncx": "text/xml"}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _file(self, path, mime=None):
        try:
            data = open(path, "rb").read()
        except Exception:
            self._json({"error": "not found"}, 404)
            return
        ext = os.path.splitext(path)[1].lower()
        self.send_response(200)
        self.send_header("Content-Type", mime or MIME.get(ext, "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        try:
            if u.path == "/" or u.path == "/index.html":
                return self._file(os.path.join(WWW, "index.html"), "text/html; charset=utf-8")
            if u.path.startswith("/vendor/"):
                return self._file(os.path.join(WWW, unquote(u.path[1:])))
            if u.path != "/api/books" and not u.path.startswith("/api/") \
                    and not u.path.startswith("/b/"):
                cand = os.path.realpath(os.path.join(WWW, unquote(u.path.lstrip("/"))))
                if cand.startswith(os.path.realpath(WWW)) and os.path.isfile(cand):
                    return self._file(cand)
            if u.path == "/api/books":
                scan_inbox()
                return self._json(load_books())
            if u.path == "/api/book":
                meta = json.load(open(os.path.join(BOOKS, q["id"][0], "meta.json"), encoding="utf-8"))
                return self._json(meta)
            if u.path == "/api/comic":
                meta = json.load(open(os.path.join(BOOKS, q["id"][0], "meta.json"), encoding="utf-8"))
                return self._json({"inner": comic_inner(meta, int(q["idx"][0]))})
            if u.path.startswith("/b/"):
                parts = unquote(u.path).split("/", 4)
                bid, rel = parts[2], parts[4] if len(parts) > 4 else ""
                bdir = os.path.realpath(os.path.join(BOOKS, bid))
                fp = os.path.realpath(os.path.join(bdir, *rel.split("/")))
                if not fp.startswith(bdir):
                    return self._json({"error": "forbidden"}, 403)
                return self._file(fp)
            self._json({"error": "not found"}, 404)
        except Exception as e:
            self._json({"error": str(e)}, 500)

    def do_POST(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        try:
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length) if length else b""
            if u.path == "/api/upload":
                name = q.get("name", ["未命名"])[0]
                meta = import_book_bytes(body, name)
                return self._json({"ok": True, "meta": meta})
            if u.path == "/api/progress":
                d = json.loads(body.decode("utf-8"))
                p = os.path.join(BOOKS, d["id"], "meta.json")
                meta = json.load(open(p, encoding="utf-8"))
                for k in ("i", "r", "spread", "merge", "seam", "page"):
                    if k in d:
                        meta[k] = d[k]
                if d.get("merge", 0) and meta.get("format") == "epub" and meta.get("mode") == "comic":
                    meta = ensure_epub_flat(meta)
                elif not d.get("merge", 0) and meta.get("format") == "epub":
                    meta = restore_epub_orig(meta)
                meta["lastReadAt"] = int(time.time() * 1000)
                save_meta(meta)
                return self._json({"ok": True})
            self._json({"error": "not found"}, 404)
        except Exception as e:
            self._json({"error": str(e)}, 500)


def main():
    scan_inbox()
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError:
        # 端口被占用：多半已有一个墨阅在运行，直接打开浏览器复用
        print(f"端口 {PORT} 已被占用（墨阅可能已在运行），直接打开浏览器…")
        webbrowser.open(f"http://127.0.0.1:{PORT}")
        return
    print(f"墨阅·电脑版 已启动: http://127.0.0.1:{PORT}  （Ctrl+C 退出）")
    print(f"书库目录: {BOOKS}")
    print(f"收书箱:   {INBOX}")
    threading.Timer(0.8, lambda: webbrowser.open(f"http://127.0.0.1:{PORT}")).start()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
