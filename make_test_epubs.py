# -*- coding: utf-8 -*-
"""生成三本测试 EPUB：
1. test_epub3_cn.epub —— EPUB3 中文小说（nav 目录 + 嵌套小节 + 内嵌图片 + 内文链接 + 封面）
2. test_epub2_en.epub —— EPUB2 英文（NCX 目录）
3. test_epub2_gbk.epub —— EPUB2 GBK 编码（测试字符集探测）
"""
import struct, zlib, zipfile, os, uuid

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "testbooks")
os.makedirs(OUT, exist_ok=True)


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


def cover_png():
    def f(x, y):
        t = y / 800
        return (int(30 + 60 * t), int(24 + 34 * t), int(46 + 90 * (1 - t)))
    return make_png(600, 800, f)


def scene_png():
    def f(x, y):
        sky = y < 150
        if sky:
            return (150 - y // 4, 180 - y // 4, 235)
        g = 90 + (y - 150) // 6
        return (30, min(200, g + x // 60), 70)
    return make_png(420, 260, f)


CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

CSS = """body { font-family: serif; }
h1, h2 { letter-spacing: 0.1em; }
p { text-indent: 2em; margin: 0.4em 0; }
.center { text-align: center; }
"""

CH1 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head><title>第一章 长安雪</title><link rel="stylesheet" type="text/css" href="../styles.css"/></head>
<body>
<h1>第一章　长安雪</h1>
<p>大雪落了三天三夜，长安城的檐角都变成了白色。</p>
<p>沈砚之推开客栈的窗，看见朱雀大街上只有一行脚印，从城门一路延伸到酒旗之下。他数了数，脚印一共七十三步，深浅一致，是个练家子。</p>
<p>“客官，这样的人，躲远些好。”店小二端着热汤进来，压低了声音。</p>
<p>沈砚之不答，只把窗关上了一半。他袖中藏着一封信，信上只有八个字：雪停之前，勿开城门。</p>
<p>可是雪，偏偏就在今夜停了。</p>
<p>他想起十年前也是这样的雪夜，师父把一柄断剑交到他手里，说：剑断了，人不能断。那时他不懂，如今懂了，却宁愿自己从没懂过。</p>
<p>更鼓敲过三巡，街上传来马蹄声。由远及近，又由近及远，像是谁在夜里反复丈量这座城的宽度。</p>
<p>沈砚之吹灭了灯。</p>
<p>黑暗里，他听见自己的心跳，一下，一下，稳得像更鼓。</p>
<p>明日的事，明日再说。他对自己说。可他知道，有些事等不到明日。</p>
<p>天将亮未亮的时候，有人在楼下轻轻叩了三下窗。不多不少，正好三下。</p>
<p>沈砚之握紧了袖中的信。</p>
<p>“来了。”他说。</p>
</body>
</html>
"""

CH2 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head><title>第二章 渡口</title><link rel="stylesheet" type="text/css" href="../styles.css"/></head>
<body>
<h1>第二章　渡口</h1>
<p>渭水渡口的老船工说，今年冬天的水，比往年都要急。</p>
<div class="center"><img src="../images/scene.png" alt="渡口远山"/></div>
<p id="sec1"><b>第一节　夜航</b></p>
<p>船开的时候没有点灯。艄公只凭两岸的山影认路，偶有渔火，也是一闪即没。</p>
<p>沈砚之坐在船头，把那封信又读了一遍。信纸被江水汽浸得发软，字迹却依旧锋利，像写信人落笔时没有半分犹豫。</p>
<p>“客人是要去终南山？”艄公忽然问。</p>
<p>“不去终南山。”他说，“去终南山背后。”</p>
<p>艄公笑了笑，不再问。走水路的人，都懂得不该问的别问。</p>
<p id="sec2"><b>第二节　故人</b></p>
<p>后半夜，船靠了一处无名的小渡。岸上有人提着灯笼等，灯笼罩上画着一枝墨梅。</p>
<p>“十年了，你的剑还没修好？”来人问。</p>
<p>“剑不急。”沈砚之跳上岸，“人急。”</p>
<p>两个人沿着江堤走，把身后那条船和一江的水声都走远了。</p>
<p>后面的事，且看<a href="chapter3.xhtml">下一章</a>。</p>
</body>
</html>
"""

CH3 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="zh-CN">
<head><title>第三章 山月</title><link rel="stylesheet" type="text/css" href="../styles.css"/></head>
<body>
<h1>第三章　山月</h1>
<p>终南山的月亮升起来的时候，雪已经化了一半。</p>
<p>沈砚之把断剑插在雪地里，剑柄上结了一层薄冰。他坐在剑旁，像坐在一句没有说完的誓言旁边。</p>
<p>山下的长安城灯火渐次亮起，一盏，又一盏，像是有人在慢慢地数着这座城的心事。</p>
<p>他想起很多年前，也是这样的月夜，师父指着山下的灯火说：你看，每一盏灯下都有人在等。剑客的剑，不是用来杀人的，是用来让等的人等得到的。</p>
<p>那时他十二岁，觉得这话酸。如今他三十有二，站在这座山上，忽然眼眶一热。</p>
<p>雪水顺着剑身流下来，在月光里像一线银。</p>
<p>他拔剑出鞘，断口在月下泛着幽幽的光。断了就是断了，修不好了。可是人还在，路还在。</p>
<p>沈砚之把剑背回背上，朝山下走去。</p>
<p>月亮很好，路还很长。</p>
<p>——第一卷　终——</p>
</body>
</html>
"""

NAV = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目录</title></head>
<body>
<nav epub:type="toc">
<h1>目录</h1>
<ol>
<li><a href="text/chapter1.xhtml">第一章 长安雪</a></li>
<li><a href="text/chapter2.xhtml">第二章 渡口</a>
<ol>
<li><a href="text/chapter2.xhtml#sec1">第一节 夜航</a></li>
<li><a href="text/chapter2.xhtml#sec2">第二节 故人</a></li>
</ol>
</li>
<li><a href="text/chapter3.xhtml">第三章 山月</a></li>
</ol>
</nav>
<nav epub:type="landmarks" hidden="hidden">
<ol><li><a epub:type="bodymatter" href="text/chapter1.xhtml">正文</a></li></ol>
</nav>
</body>
</html>
"""

OPF3 = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="zh-CN">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:{uid}</dc:identifier>
    <dc:title>长安雪（墨阅测试书·卷一）</dc:title>
    <dc:creator>墨阅测试组</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-10-29T00:00:00Z</meta>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="css" href="styles.css" media-type="text/css"/>
    <item id="cover-img" href="cover.png" media-type="image/png" properties="cover-image"/>
    <item id="ch1" href="text/chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="text/chapter3.xhtml" media-type="application/xhtml+xml"/>
    <item id="scene" href="images/scene.png" media-type="image/png"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="ch3"/>
  </spine>
</package>
"""


def write_epub(path, entries):
    with zipfile.ZipFile(path, "w") as z:
        z.writestr(zipfile.ZipInfo("mimetype"), entries[0][1])  # 第一项必须是 mimetype
        for name, data in entries[1:]:
            z.writestr(name, data)
    print("wrote", path, os.path.getsize(path), "bytes")


write_epub(os.path.join(OUT, "test_epub3_cn.epub"), [
    ("mimetype", "application/epub+zip"),
    ("META-INF/container.xml", CONTAINER),
    ("OEBPS/content.opf", OPF3.format(uid=str(uuid.uuid4()))),
    ("OEBPS/nav.xhtml", NAV),
    ("OEBPS/styles.css", CSS),
    ("OEBPS/cover.png", cover_png()),
    ("OEBPS/text/chapter1.xhtml", CH1),
    ("OEBPS/text/chapter2.xhtml", CH2),
    ("OEBPS/text/chapter3.xhtml", CH3),
    ("OEBPS/images/scene.png", scene_png()),
])

# ── EPUB2 英文（NCX） ────────────────────────────────────────────
EN1 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter I</title></head><body>
<h1>Chapter I — The Strange Machine</h1>
<p>The Time Traveller (for so it will be convenient to speak of him) was expounding a recondite matter to us.</p>
<p>"You must follow me carefully. I shall have to controvert one or two ideas that are almost universally accepted."</p>
<p>"Is not that rather a large ask?" said the Provincial Mayor.</p>
<p>"I do not mean to ask you to accept anything without reasonable ground for it. You will soon admit as much as I need from you."</p>
<p>"Clearly," the Time Traveller proceeded, "any real body must have extension in four directions: it must have Length, Breadth, Thickness, and—Duration."</p>
<p>The fire burnt brightly, and the soft radiance of the incandescent lights in the lilies of silver caught the bubbles that flashed and passed in our glasses.</p>
<p>Our chairs, being his patents, embraced and caressed us rather than submitted to be sat upon.</p>
<p>"Scientific people know very well that Time is only a kind of Space."</p>
<p>"Here is a portrait of a man at eight years old, another at fifteen, another at seventeen, another at twenty-three, and so on. All these are evidently sections, as it were, Three-Dimensional representations of his Four-Dimensioned being."</p>
<p>"I have been at work upon this geometry of Four Dimensions for some time. Some of my results are curious."</p>
</body></html>
"""
EN2 = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter II</title></head><body>
<h1>Chapter II — The Machine</h1>
<p>"I do not want you to be under any misconception," said the Time Traveller.</p>
<p>"I will tell you where this little machine has gone. It has moved into the Future."</p>
<p>The Psychologist looked at us.</p>
<p>"I cannot be quite sure," he said, "but I fancy I see—nothing."</p>
<p>The Medical Man got up out of his chair and looked into the thing.</p>
<p>"It is a trick?" he said, and raised his eyebrows.</p>
<p>"You will notice that it looks singularly distorted, and that there is an odd flickering blur over it."</p>
<p>The little model laboratory vanished; the breath of the lamp drew the flame down; the coal cracked; a spark flew; and the night was at hand.</p>
</body></html>
"""
NCX_EN = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:{uid}"/></head>
  <docTitle><text>The Time Machine (Excerpt)</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1"><navLabel><text>Chapter I</text></navLabel><content src="ch1.xhtml"/></navPoint>
    <navPoint id="np2" playOrder="2"><navLabel><text>Chapter II</text></navLabel><content src="ch2.xhtml"/></navPoint>
  </navMap>
</ncx>
"""
OPF_EN = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="bookid">urn:uuid:{uid}</dc:identifier>
    <dc:title>The Time Machine (Excerpt)</dc:title>
    <dc:creator>H. G. Wells</dc:creator>
    <dc:language>en</dc:language>
    <meta name="cover" content="cover-img"/>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="cover-img" href="cover.png" media-type="image/png"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>
"""
CONTAINER_FLAT = CONTAINER.replace("OEBPS/content.opf", "content.opf")
write_epub(os.path.join(OUT, "test_epub2_en.epub"), [
    ("mimetype", "application/epub+zip"),
    ("META-INF/container.xml", CONTAINER_FLAT),
    ("content.opf", OPF_EN.format(uid=str(uuid.uuid4()))),
    ("toc.ncx", NCX_EN.format(uid=str(uuid.uuid4()))),
    ("cover.png", cover_png()),
    ("ch1.xhtml", EN1),
    ("ch2.xhtml", EN2),
])

# ── EPUB2 GBK 编码（模拟早年中文电子书） ────────────────────────
GBK_CH1 = """<?xml version="1.0" encoding="gbk"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Transitional//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd">
<html xmlns="http://www.w3.org/1999/xhtml"><head>
<meta http-equiv="Content-Type" content="text/html; charset=gbk"/>
<title>第一回</title></head><body>
<h2>第一回　宴桃园豪杰三结义</h2>
<p>话说天下大势，分久必合，合久必分：周末七国分争，并入于秦；及秦灭之后，楚、汉分争，又并入于汉。</p>
<p>汉朝自高祖斩白蛇而起义，一统天下，后来光武中兴，传至献帝，遂分为三国。</p>
<p>推其致乱之由，殆始于桓、灵二帝。桓帝禁锢善类，崇信宦官。</p>
<p>及桓帝崩，灵帝即位，大将军窦武、太傅陈蕃，共相辅佐；时有宦官曹节等弄权，窦武、陈蕃谋诛之，机事不密，反为所害，中涓自此愈横。</p>
<p>建宁二年四月望日，帝御温德殿。方升座，殿角狂风骤起，只见一条大青蛇，从梁上飞将下来，蟠于椅上。</p>
<p>帝惊倒，左右急救入宫，百官俱奔避。须臾，蛇不见了。</p>
</body></html>
"""
GBK_OPF = """<?xml version="1.0" encoding="gbk"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:{uid}</dc:identifier>
    <dc:title>三国演义·第一回（GBK 编码测试）</dc:title>
    <dc:creator>罗贯中</dc:creator>
    <dc:language>zh-CN</dc:language>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="ch1"/></spine>
</package>
"""
GBK_NCX = """<?xml version="1.0" encoding="gbk"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:{uid}"/></head>
  <docTitle><text>三国演义·第一回</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1"><navLabel><text>第一回 宴桃园豪杰三结义</text></navLabel><content src="ch1.xhtml"/></navPoint>
  </navMap>
</ncx>
"""


def gbk(s):
    return s.encode("gbk")


write_epub(os.path.join(OUT, "test_epub2_gbk.epub"), [
    ("mimetype", "application/epub+zip"),
    ("META-INF/container.xml", CONTAINER_FLAT.encode("utf-8")),
    ("content.opf", gbk(GBK_OPF.format(uid=str(uuid.uuid4())))),
    ("toc.ncx", gbk(GBK_NCX.format(uid=str(uuid.uuid4())))),
    ("ch1.xhtml", gbk(GBK_CH1)),
])
print("all done")
