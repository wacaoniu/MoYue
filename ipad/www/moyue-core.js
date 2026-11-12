/* 墨阅·iPad版 —— 解析核心
   移植自 desktop/moyue_desktop.py（与安卓版同源逻辑）：
   ZIP 解包（含 GBK 文件名修正）/ EPUB 2-3 / TXT 编码探测+自动分章 / CBZ 自然排序
   sw.js 只使用其中的 ZipReader / idb / mimeOf（EPUB 解析用到 DOMParser，仅限页面侧调用）。 */
'use strict';
const MoYue = (() => {

  /* ══════════════════ 工具 ══════════════════ */

  let HAS_DEFLATE = false;
  try { new DecompressionStream('deflate-raw'); HAS_DEFLATE = true; } catch (e) {}

  function randId() { // 16 位随机 hex，同 python uuid4().hex[:16]
    const a = new Uint8Array(8); crypto.getRandomValues(a);
    return [...a].map(b => b.toString(16).padStart(2, '0')).join('');
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  }

  function safeUnquote(s) { // 对应 python unquote：坏转义不抛错
    try { return decodeURIComponent(s); } catch (e) { return s; }
  }

  /* 把书内相对链接解析成书内相对路径（同安卓版逻辑） */
  function normRel(base, href) {
    const p = safeUnquote(String(href || '').split('#')[0]).replace(/\\/g, '/');
    const combined = base ? base + '/' + p : p;
    const stack = [];
    for (const part of combined.split('/')) {
      if (!part || part === '.') continue;
      if (part === '..') { if (stack.length) stack.pop(); }
      else stack.push(part);
    }
    return stack.join('/');
  }

  /* 自然排序：p2 < p10 */
  function naturalCmp(a, b) {
    const A = String(a).split(/(\d+)/), B = String(b).split(/(\d+)/);
    for (let i = 0; i < Math.max(A.length, B.length); i++) {
      const x = A[i], y = B[i];
      if (x === undefined || x === '') { if (y === undefined || y === '') return 0; return -1; }
      if (y === undefined || y === '') return 1;
      if (i % 2 === 1) { const d = (+x) - (+y); if (d) return d; } // 数字段按数值比
      else {
        const lx = x.toLowerCase(), ly = y.toLowerCase();
        if (lx < ly) return -1; if (lx > ly) return 1;
      }
    }
    return 0;
  }

  /* 按头部声明/内容探测解码：UTF-8 优先，GB 系自动回退（老书兼容） */
  function smartDecode(bytes) {
    if (!bytes) return '';
    const head = new TextDecoder('windows-1252').decode(bytes.subarray(0, 1024)).toLowerCase();
    const m = head.match(/encoding="([a-z0-9_\-]+)"|charset=["' ]*([a-z0-9_\-]+)/);
    const enc = m ? (m[1] || m[2]) : '';
    if (enc && enc.includes('gb')) {
      try { return new TextDecoder('gb18030', { fatal: true }).decode(bytes); } catch (e) {}
    }
    try { return new TextDecoder('utf-8', { fatal: true }).decode(bytes); } catch (e) {}
    try { return new TextDecoder('gb18030').decode(bytes); } catch (e) {}
    return new TextDecoder('utf-8').decode(bytes);
  }

  /* 读图片头部宽高：JPEG/PNG/GIF/WebP */
  function imgSize(d) {
    try {
      const dv = new DataView(d.buffer, d.byteOffset, d.byteLength);
      if (d.length >= 24 && d[0] === 0x89 && d[1] === 0x50) { // PNG
        const w = dv.getUint32(16), h = dv.getUint32(20);
        if (w && h) return [w, h];
      }
      if (d.length >= 10 && d[0] === 0xff && d[1] === 0xd8) { // JPEG
        let i = 2;
        while (i + 9 < d.length) {
          if (d[i] !== 0xff) { i++; continue; }
          const mk = d[i + 1];
          if (mk === 0x01 || mk === 0xd8 || (mk >= 0xd0 && mk <= 0xd7)) { i += 2; continue; }
          if (mk === 0xd9 || mk === 0xda) break;
          const seg = dv.getUint16(i + 2);
          if (mk >= 0xc0 && mk <= 0xcf && mk !== 0xc4 && mk !== 0xc8 && mk !== 0xcc) {
            const h = dv.getUint16(i + 5), w = dv.getUint16(i + 7);
            if (w && h) return [w, h];
            break;
          }
          i += 2 + seg;
        }
      }
      if (d.length >= 10 && d[0] === 0x47 && d[1] === 0x49 && d[2] === 0x46) { // GIF
        const w = dv.getUint16(6, true), h = dv.getUint16(8, true);
        if (w && h) return [w, h];
      }
      if (d.length >= 30 && d[0] === 0x52 && d[1] === 0x49 && d[2] === 0x46 && d[3] === 0x46 &&
          d[8] === 0x57 && d[9] === 0x45 && d[10] === 0x42 && d[11] === 0x50) { // RIFF..WEBP
        const tag = String.fromCharCode(d[12], d[13], d[14], d[15]);
        if (tag === 'VP8X') return [1 + (d[24] | d[25] << 8 | d[26] << 16), 1 + (d[27] | d[28] << 8 | d[29] << 16)];
        if (tag === 'VP8 ' && d[23] === 0x9d && d[24] === 0x01 && d[25] === 0x2a)
          return [dv.getUint16(26) & 0x3fff, dv.getUint16(28) & 0x3fff];
        if (tag === 'VP8L' && d[20] === 0x2f) {
          const bits = (d[21] | d[22] << 8 | d[23] << 16 | d[24] << 24) >>> 0;
          return [(bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1];
        }
      }
    } catch (e) {}
    return null;
  }

  /* ══════════════════ ZIP 读取 ══════════════════ */

  /* GBK 打包的 zip 文件名修正：无 UTF-8 标志的名字先按 UTF-8 严格试、失败按 GB18030 解 */
  function decodeZipName(raw, flags) {
    if (flags & 0x800) return new TextDecoder('utf-8').decode(raw);
    try { return new TextDecoder('utf-8', { fatal: true }).decode(raw); } catch (e) {}
    try { return new TextDecoder('gb18030').decode(raw); } catch (e) {}
    return new TextDecoder('utf-8').decode(raw);
  }

  class ZipReader {
    constructor(blob) { this.blob = blob; this.entries = []; this.byLower = new Map(); }

    async init() { // 只解析中央目录，不解压内容
      const size = this.blob.size;
      if (size < 22) throw new Error('文件太小，不是有效的压缩包');
      const tailLen = Math.min(size, 22 + 65536);
      const tail = new Uint8Array(await this.blob.slice(size - tailLen).arrayBuffer());
      let eocd = -1;
      for (let i = tail.length - 22; i >= 0; i--) {
        if (tail[i] === 0x50 && tail[i + 1] === 0x4b && tail[i + 2] === 0x05 && tail[i + 3] === 0x06) { eocd = i; break; }
      }
      if (eocd < 0) throw new Error('不是有效的 ZIP 压缩包');
      const dv = new DataView(tail.buffer, eocd);
      const count = dv.getUint16(8, true), cdSize = dv.getUint32(12, true), cdOfs = dv.getUint32(16, true);
      if (count === 0xFFFF || cdOfs === 0xFFFFFFFF || cdSize === 0xFFFFFFFF)
        throw new Error('暂不支持 ZIP64 超大压缩包');
      if (cdOfs + cdSize > size) throw new Error('ZIP 中央目录损坏');
      const cd = new Uint8Array(await this.blob.slice(cdOfs, cdOfs + cdSize).arrayBuffer());
      const dv2 = new DataView(cd.buffer);
      let p = 0;
      while (p + 46 <= cd.length) {
        if (dv2.getUint32(p, true) !== 0x02014b50) break;
        const flags = dv2.getUint16(p + 8, true), method = dv2.getUint16(p + 10, true);
        const csize = dv2.getUint32(p + 20, true), usize = dv2.getUint32(p + 24, true);
        const nameLen = dv2.getUint16(p + 28, true), extraLen = dv2.getUint16(p + 30, true), cmtLen = dv2.getUint16(p + 32, true);
        const lho = dv2.getUint32(p + 42, true);
        const nameRaw = cd.subarray(p + 46, p + 46 + nameLen);
        const e = { name: decodeZipName(nameRaw, flags), flags, method, csize, usize, lho };
        this.entries.push(e);
        this.byLower.set(e.name.toLowerCase(), e); // 与 python 一致：重名后者覆盖
        p += 46 + nameLen + extraLen + cmtLen;
      }
      if (!this.entries.length) throw new Error('ZIP 里没有文件');
      return this;
    }

    fix(rel) { return this.byLower.get(String(rel).toLowerCase())?.name ?? rel; } // 目录大小写容错
    has(rel) { return this.byLower.has(String(rel).toLowerCase()); }
    findEntry(rel) { return this.byLower.get(String(rel).toLowerCase()) || null; }

    async readEntry(e, limit) {
      if (!e) return null;
      if (e.flags & 0x1) throw new Error('不支持加密的压缩包条目');
      if (e.csize === 0 && e.usize === 0) return new Uint8Array(0); // 空文件
      // 本地头里的 name/extra 长度可能与中央目录不同，读实际数据偏移
      const lh = new Uint8Array(await this.blob.slice(e.lho, e.lho + 30).arrayBuffer());
      if (lh.length < 30 || new DataView(lh.buffer).getUint32(0, true) !== 0x04034b50) return null;
      const ldv = new DataView(lh.buffer);
      const start = e.lho + 30 + ldv.getUint16(26, true) + ldv.getUint16(28, true);
      const end = Math.min(start + e.csize, this.blob.size);
      if (end <= start) return new Uint8Array(0);
      let chunk = this.blob.slice(start, end);
      if (e.method === 8) {
        if (!HAS_DEFLATE) throw new Error('浏览器不支持 ZIP 解压（iPadOS 需 16.4+）');
        const stream = chunk.stream().pipeThrough(new DecompressionStream('deflate-raw'));
        chunk = await new Response(stream).blob();
      } else if (e.method !== 0) throw new Error('不支持的压缩算法 (' + e.method + ')');
      if (limit != null && chunk.size > limit) return new Uint8Array(await chunk.slice(0, limit).arrayBuffer());
      return new Uint8Array(await chunk.arrayBuffer());
    }

    async read(rel, limit) { return this.readEntry(this.findEntry(rel), limit); }
    async readBlob(rel, mime, limit) {
      const b = await this.read(rel, limit);
      return b ? new Blob([b], { type: mime || 'application/octet-stream' }) : null;
    }
  }

  async function readText(zip, rel) { // 读不到返回 ''（同 python read_text 容错）
    const e = zip.findEntry(rel);
    if (!e) return '';
    try { return smartDecode(await zip.readEntry(e)); } catch (err) { return ''; }
  }

  /* ══════════════════ XML 容错解析 ══════════════════ */

  function xmlRoot(text) {
    let doc = new DOMParser().parseFromString(text, 'application/xml');
    if (doc.getElementsByTagName('parsererror').length)
      doc = new DOMParser().parseFromString(text, 'text/html'); // 个别书 XML 不合规，按 HTML 宽容解析
    return doc;
  }
  function tagOf(el) { return (el.nodeName || '').split(':').pop().toLowerCase(); } // dc:title → title
  function allEls(doc) { return [...doc.getElementsByTagName('*')]; }

  /* ══════════════════ EPUB ══════════════════ */

  async function importEpub(zip) {
    const containerText = await readText(zip, zip.fix('META-INF/container.xml'));
    const m = /full-path="([^"]+)"/.exec(containerText);
    let opfRel = m ? zip.fix(normRel('', m[1])) : null;
    if (!opfRel || !zip.has(opfRel)) {
      for (const e of zip.entries) { if (e.name.toLowerCase().endsWith('.opf')) { opfRel = e.name; break; } }
    }
    if (!opfRel || !zip.has(opfRel)) throw new Error('不是有效的 EPUB（缺少 OPF）');

    const opfDir = opfRel.includes('/') ? opfRel.slice(0, opfRel.lastIndexOf('/')) : '';
    const doc = xmlRoot(await readText(zip, opfRel));
    const manifest = {}, spineRefs = [], metaV = { title: '未命名', author: '未知作者' };
    let inMeta = false;
    for (const el of allEls(doc)) {
      const tag = tagOf(el);
      if (tag === 'metadata') inMeta = true;
      if (inMeta && tag === 'title' && (el.textContent || '').trim()) metaV.title = el.textContent.trim();
      if (inMeta && tag === 'creator' && (el.textContent || '').trim()) metaV.author = el.textContent.trim();
      if (tag === 'item' && el.getAttribute('id')) {
        manifest[el.getAttribute('id')] = {
          href: el.getAttribute('href') || '', mediaType: el.getAttribute('media-type') || '',
          properties: el.getAttribute('properties') || '',
        };
      }
      if (tag === 'itemref' && el.getAttribute('idref')) spineRefs.push(el.getAttribute('idref'));
    }
    const spine = [];
    for (const r of spineRefs) {
      const it = manifest[r];
      if (it) spine.push({ href: zip.fix(normRel(opfDir, it.href)), mediaType: it.mediaType });
    }
    if (!spine.length) throw new Error('EPUB 阅读顺序为空');

    const hrefToIdx = {};
    spine.forEach((s, i) => { if (!(s.href in hrefToIdx)) hrefToIdx[s.href] = i; });

    // 目录：nav 优先，其次 ncx
    let toc = [];
    const navItem = Object.values(manifest).find(v => (v.properties || '').includes('nav'));
    if (navItem) toc = parseNav(await readText(zip, zip.fix(normRel(opfDir, navItem.href))), opfDir);
    if (!toc.length) {
      const ncx = Object.values(manifest).find(v => (v.mediaType || '').includes('dtbncx'));
      if (ncx) toc = parseNcx(await readText(zip, zip.fix(normRel(opfDir, ncx.href))), opfDir);
    }
    toc = toc.filter(t => {
      t.href = zip.fix(t.href);
      t.spineIndex = hrefToIdx[t.href] ?? -1;
      return t.spineIndex >= 0;
    });
    if (!toc.length)
      toc = spine.map((s, i) => ({ title: `第 ${i + 1} 节`, href: s.href, level: 0, spineIndex: i }));

    let cover = null;
    const prop = Object.values(manifest).find(v => (v.properties || '').includes('cover-image'));
    if (prop) cover = zip.fix(normRel(opfDir, prop.href));
    else for (const v of Object.values(manifest)) {
      if ((v.mediaType || '').startsWith('image/') && (v.href || '').toLowerCase().includes('cover')) {
        cover = zip.fix(normRel(opfDir, v.href)); break;
      }
    }

    // 漫画识别：抽样 spine
    const sample = spine.length <= 24 ? spine : Array.from({ length: 20 }, (_, i) => spine[Math.floor(i * spine.length / 20)]);
    let imgPages = 0;
    for (const s of sample) {
      if (!zip.has(s.href)) continue;
      if ((s.mediaType || '').startsWith('image/')) imgPages++;
      else if ((s.mediaType || '').includes('html')) {
        const t = await readText(zip, s.href);
        if ((t.includes('<img') || /<image[^>]*?href/i.test(t)) && t.replace(/<[^>]*>|\s/g, '').length <= 80) imgPages++;
      }
    }
    const mode = (sample.length >= 2 && imgPages / sample.length >= 0.6) ? 'comic' : 'text';
    return { meta: { title: metaV.title, author: metaV.author, format: 'epub', mode, spine, toc, pages: null, cover }, inlineDocs: null };
  }

  function parseNav(text, base) {
    const toc = [];
    if (!text) return toc;
    let doc;
    try { doc = xmlRoot(text); } catch (e) { return toc; }
    for (const nav of allEls(doc)) {
      if (tagOf(nav) !== 'nav') continue;
      const type = nav.getAttribute('type') || nav.getAttribute('epub:type') || '';
      if (type !== 'toc') continue;
      for (const ol of [...nav.children].filter(c => tagOf(c) === 'ol')) walk(ol, 0);
      break;
    }
    function walk(ol, level) {
      for (const li of [...ol.children].filter(c => tagOf(c) === 'li')) {
        const a = [...li.children].find(c => tagOf(c) === 'a');
        if (a && a.getAttribute('href'))
          toc.push({ title: (a.textContent || '').trim() || '(无题)', href: normRel(base, a.getAttribute('href')), level, spineIndex: -1 });
        const sub = [...li.children].find(c => tagOf(c) === 'ol');
        if (sub) walk(sub, level + 1);
      }
    }
    return toc;
  }

  function parseNcx(text, base) {
    const toc = [];
    if (!text) return toc;
    let doc;
    try { doc = xmlRoot(text); } catch (e) { return toc; }
    const navMap = allEls(doc).find(el => tagOf(el) === 'navmap');
    if (!navMap) return toc;
    (function walk(np, level) {
      for (const child of [...np.children]) {
        if (tagOf(child) !== 'navpoint') continue;
        let label = '', src = '';
        (function deep(x) { // 与 python child.iter() 一致：深度优先找第一个 text 与 content
          for (const y of [...x.children]) {
            const t = tagOf(y);
            if (t === 'text' && !label && (y.textContent || '').trim()) label = y.textContent.trim();
            if (t === 'content' && y.getAttribute('src')) src = y.getAttribute('src');
            deep(y);
          }
        })(child);
        if (label && src) toc.push({ title: label, href: normRel(base, src), level, spineIndex: -1 });
        walk(child, level + 1);
      }
    })(navMap, 0);
    return toc;
  }

  /* 提取一个 spine 章节里的图片（文档顺序）：[{src,w,h}, ...] */
  async function chapterImages(zip, item) {
    const href = zip.fix(item.href);
    const text = await readText(zip, href);
    if (!text) return [];
    const base = href.includes('/') ? href.slice(0, href.lastIndexOf('/')) : '';
    const re = /<img[^>]*?\ssrc\s*=\s*["']([^"']+)["']|<image[^>]*?(?:xlink:)?href\s*=\s*["']([^"']+)["']/gi;
    const out = [];
    let m;
    while ((m = re.exec(text))) {
      const rel = normRel(base, m[1] || m[2]);
      if (!zip.has(rel)) continue;
      const head = await zip.read(rel, 131072);
      const sz = head ? (imgSize(head) || [3, 4]) : [3, 4];
      out.push({ src: rel, w: sz[0], h: sz[1] });
    }
    return out;
  }

  /* ══════════════════ TXT ══════════════════ */

  const CHAPTER_RE = /^\s{0,8}(第[0-9零〇一二三四五六七八九十百千万两]{1,14}[章节卷回部集]{1,3}[\s　]?[^\n]{0,30}|(?:Chapter|CHAPTER)\s*\d+[^\n]{0,30}|(?:序章|楔子|序言|前言|后记|尾声|终章|番外[^\n]{0,12}))\s*$/;

  function decodeTxt(bytes) {
    if (bytes.length >= 3 && bytes[0] === 0xEF && bytes[1] === 0xBB && bytes[2] === 0xBF)
      return new TextDecoder('utf-8').decode(bytes); // BOM 自动剥离
    if (bytes.length >= 2 && bytes[0] === 0xFF && bytes[1] === 0xFE)
      return new TextDecoder('utf-16le').decode(bytes);
    if (bytes.length >= 2 && bytes[0] === 0xFE && bytes[1] === 0xFF)
      return new TextDecoder('utf-16be').decode(bytes);
    for (const enc of ['utf-8', 'gb18030']) {
      try { return new TextDecoder(enc, { fatal: true }).decode(bytes); } catch (e) {}
    }
    return new TextDecoder('utf-8').decode(bytes);
  }

  function importTxt(bytes, title) {
    const text = decodeTxt(bytes);
    const lines = text.split('\n');
    const heads = [];
    for (let i = 0; i < lines.length; i++) {
      const t = lines[i].replace(/^[\r \t　]+|[\r \t　]+$/g, '');
      if (t.length >= 2 && t.length <= 40 && CHAPTER_RE.test(t)) heads.push([i, t]);
    }
    let chunks;
    if (heads.length) {
      chunks = []; let start = 0;
      for (const [i] of heads) { if (i > start) chunks.push([start, i - 1]); start = i; }
      if (start < lines.length) chunks.push([start, lines.length - 1]);
    } else {
      chunks = [];
      for (let s = 0; s < lines.length; s += 1500) chunks.push([s, Math.min(s + 1499, lines.length - 1)]);
    }
    chunks = chunks.filter(c => c[1] >= c[0]);
    if (!chunks.length) throw new Error('文件内容为空');

    const spine = [], toc = [], inlineDocs = {};
    chunks.forEach(([a, b], idx) => {
      const heading = (heads.find(h => h[0] === a) || [0, null])[1];
      const title2 = heading || (heads.length ? `片段 ${idx + 1}` : `第 ${idx + 1} 部分`);
      const parts = ["<html><head><meta charset='utf-8'><style>body{font-family:serif;line-height:1.8;padding:24px 6%}p{text-indent:2em;margin:.4em 0}h2{text-align:center}</style></head><body>"];
      if (heading) parts.push(`<h2>${escapeHtml(heading)}</h2>`);
      for (let i = a; i <= b; i++) {
        const p = lines[i].replace(/\r/g, '').trim();
        if (p) parts.push(`<p>${escapeHtml(p)}</p>`);
      }
      parts.push('</body></html>');
      const rel = `text/c${idx}.html`;
      inlineDocs[rel] = parts.join('');
      spine.push({ href: rel, mediaType: 'text/html' });
      toc.push({ title: title2, href: rel, level: 0, spineIndex: idx });
    });
    return { meta: { title, author: '文本文档', format: 'txt', mode: 'text', spine, toc, pages: null, cover: null }, inlineDocs };
  }

  /* ══════════════════ CBZ ══════════════════ */

  async function importCbz(zip, title) {
    const exts = ['jpg', 'jpeg', 'png', 'webp', 'gif', 'bmp', 'avif'];
    const imgs = [];
    for (const e of zip.entries) {
      if (e.name.endsWith('/')) continue;
      const segs = e.name.split('/');
      if (segs.some(s => s.startsWith('__MACOSX') || s.startsWith('.'))) continue; // mac 压缩垃圾/隐藏文件
      const ext = e.name.includes('.') ? e.name.split('.').pop().toLowerCase() : '';
      if (!exts.includes(ext)) continue;
      const head = await zip.readEntry(e, 131072);
      const sz = (head && imgSize(head)) || [3, 4];
      imgs.push({ name: e.name, w: sz[0], h: sz[1] });
    }
    if (!imgs.length) throw new Error('压缩包里没有图片，不是有效的 CBZ');
    imgs.sort((a, b) => naturalCmp(a.name, b.name));
    const pages = imgs.map(x => ({ src: x.name, w: x.w, h: x.h }));
    const spine = [], toc = [];
    for (let s = 0; s < pages.length; s += 20) {
      const e = Math.min(s + 19, pages.length - 1);
      spine.push({ href: `chunk/${s}-${e}`, mediaType: 'x' });
      toc.push({ title: `第 ${s + 1} 页`, href: spine[spine.length - 1].href, level: 0, spineIndex: spine.length - 1 });
    }
    return { meta: { title, author: 'CBZ 漫画', format: 'cbz', mode: 'comic', spine, toc, pages, cover: pages[0].src }, inlineDocs: null };
  }

  /* ══════════════════ 统一导入 ══════════════════ */

  async function detectFormat(bytes) {
    if (bytes.length >= 5 && bytes[0] === 0x25 && bytes[1] === 0x50 && bytes[2] === 0x44 && bytes[3] === 0x46) return 'pdf'; // %PDF-
    if (bytes.length >= 2 && bytes[0] === 0x50 && bytes[1] === 0x4B) { // PK
      try {
        const z = new ZipReader(new Blob([bytes]));
        await z.init();
        if (z.has('META-INF/container.xml') || z.entries.some(e => e.name.toLowerCase().endsWith('.opf'))) return 'epub';
      } catch (e) {}
      return 'cbz';
    }
    return 'txt';
  }

  async function importBook(bytes, name) {
    const fmt = await detectFormat(bytes);
    const base = String(name || '未命名').split(/[\\/]/).pop();
    const title = base.replace(/\.[^.]*$/, '') || '未命名';
    let res, zip = null;
    if (fmt === 'pdf') {
      res = { meta: { title, author: 'PDF 文档', format: 'pdf', mode: 'pdf', spine: [], toc: [], pages: null, cover: null }, inlineDocs: null };
    } else if (fmt === 'epub' || fmt === 'cbz') {
      zip = new ZipReader(new Blob([bytes]));
      await zip.init();
      res = fmt === 'epub' ? await importEpub(zip) : await importCbz(zip, title);
    } else {
      res = importTxt(bytes, title);
    }
    const meta = { ...res.meta, id: randId(), i: 0, r: 0, spread: 0, merge: 0, seam: 1, page: -1, addedAt: Date.now(), lastReadAt: 0 };
    return { meta, inlineDocs: res.inlineDocs, zip };
  }

  /* ══════════════════ 漫画长条布局（移植 comic_inner） ══════════════════ */

  /* 返回布局节点数组（Array）或 {empty:true} / {textFallback:true, href}
     节点：{k:'plain',src,w,h,pg} | {k:'split',src,w,h,pg,rightFirst} | {k:'pair',seam,left,right}
     imgs：EPUB 章节模式时该章的 chapterImages 结果 */
  function comicNodes(meta, idx, imgs) {
    const spread = meta.spread || 0, merge = meta.merge || 0, seam = meta.seam ?? 1;
    const wide = (w, h) => !!(w && h && w / h >= 1.1);
    const sameSize = (w1, h1, w2, h2) => {
      if (!(w1 && h1 && w2 && h2)) return false;
      return Math.abs(w1 - w2) <= Math.max(4, w1 / 100) && Math.abs(h1 - h2) <= Math.max(4, h1 / 100);
    };
    const single = (src, w, h, pg) => ({ k: 'plain', src, w, h, pg });
    const imgTag = (src, w, h, pg) => {
      if (spread && wide(w, h)) {
        const a = { k: 'split', src, w, h, pg, rightFirst: false }, b = { k: 'split', src, w, h, pg, rightFirst: true };
        return spread === 2 ? [b, a] : [a, b];
      }
      return [single(src, w, h, pg)];
    };
    /* 双页合并：槽位模型 —— 跨页大图占 2 槽，竖版单页占 1 槽；槽位为偶的竖页与其后同尺寸竖页配对 */
    function renderPages(lst, baseSlot) {
      const on = merge > 0, rtl = merge === 2 || merge === 4;
      const out = []; let i = 0, slot = baseSlot;
      while (i < lst.length) {
        const { src, w, h, pg } = lst[i];
        if (!on) { out.push(...imgTag(src, w, h, pg)); i++; continue; }
        if (wide(w, h)) { out.push(single(src, w, h, pg)); slot += 2; i++; continue; }
        if (slot % 2 === 0 && i + 1 < lst.length) {
          const n2 = lst[i + 1];
          if (!wide(n2.w, n2.h) && sameSize(w, h, n2.w, n2.h)) {
            const a = single(src, w, h, pg), b = single(n2.src, n2.w, n2.h, n2.pg);
            out.push({ k: 'pair', seam, left: rtl ? b : a, right: rtl ? a : b });
            slot += 2; i += 2; continue;
          }
        }
        out.push(single(src, w, h, pg)); slot++; i++;
      }
      return out;
    }
    function slotOf(arr, x, off) {
      let t = off;
      for (let j = 0; j < x; j++) { const p = arr[j]; t += (p.w && p.h && p.w / p.h >= 1.1) ? 2 : 1; }
      return t;
    }
    const off = (merge === 3 || merge === 4) ? 1 : 0;
    const pages = meta.pages;
    const item = meta.spine[idx];
    if (pages && pages.length && (meta.format === 'pdf' || meta.format === 'cbz' || meta.chunked)) {
      const m = /chunk\/(\d+)-(\d+)/.exec(item.href);
      if (m) {
        let s = +m[1], e = Math.min(+m[2], pages.length - 1);
        if (s <= e) {
          if (merge > 0) {
            // 跨 20 页分章边界的配对：跳过被上一章借走的页 + 借下一页凑完整组（按槽位对齐）
            const isWideP = p => !!(p.w && p.h && p.w / p.h >= 1.1);
            const samePair = (a, b) => !isWideP(a) && !isWideP(b) && sameSize(a.w, a.h, b.w, b.h);
            while (s <= e && slotOf(pages, s, off) % 2 === 1 && s > 0 && samePair(pages[s - 1], pages[s])) s++;
            if (e < pages.length - 1 && slotOf(pages, e + 1, off) % 2 === 1 && samePair(pages[e], pages[e + 1])) e++;
            const lst = pages.slice(s, e + 1).map((p, k) => ({ src: p.src, w: p.w, h: p.h, pg: s + k }));
            return renderPages(lst, slotOf(pages, s, off));
          }
          const lst = pages.slice(s, e + 1).map((p, k) => ({ src: p.src, w: p.w, h: p.h, pg: s + k }));
          return lst.flatMap(t => imgTag(t.src, t.w, t.h, t.pg));
        }
      }
      return { empty: true };
    }
    // epub 章节：提取图片
    if (imgs && imgs.length) return imgs.flatMap(t => imgTag(t.src, t.w, t.h, -1));
    return { textFallback: true, href: item.href };
  }

  /* EPUB 漫画开双页合并时：摊平全书图片为 pages 流 + 20 页分章 spine（关合并时还原） */
  async function ensureEpubFlat(meta, imgsOf) {
    if (meta.format !== 'epub' || meta.mode !== 'comic' || meta.chunked) return meta;
    const pages = [];
    for (const item of meta.spine) for (const im of await imgsOf(item)) pages.push(im);
    if (!pages.length) return meta;
    const spine = [], toc = [];
    for (let st = 0; st < pages.length; st += 20) {
      const e = Math.min(st + 19, pages.length - 1);
      spine.push({ href: `chunk/${st}-${e}`, mediaType: 'x' });
      toc.push({ title: `第 ${st + 1} 页`, href: spine[spine.length - 1].href, level: 0, spineIndex: spine.length - 1 });
    }
    return { ...meta, pages, spine_orig: meta.spine, toc_orig: meta.toc || [], spine, toc, chunked: true };
  }

  function restoreEpubOrig(meta) {
    if (!meta.chunked) return meta;
    return { ...meta, spine: meta.spine_orig || meta.spine, toc: meta.toc_orig || meta.toc || [], chunked: false, pages: null };
  }

  /* ══════════════════ MIME / IndexedDB（页面与 sw.js 共用） ══════════════════ */

  const MIME = {
    '.html': 'text/html', '.xhtml': 'application/xhtml+xml', '.htm': 'text/html',
    '.css': 'text/css', '.js': 'application/javascript', '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg', '.png': 'image/png', '.gif': 'image/gif',
    '.webp': 'image/webp', '.bmp': 'image/bmp', '.svg': 'image/svg+xml',
    '.avif': 'image/avif', '.ttf': 'font/ttf', '.otf': 'font/otf',
    '.opf': 'text/xml', '.ncx': 'text/xml',
  };
  function mimeOf(name) {
    const i = String(name || '').lastIndexOf('.');
    return (i >= 0 && MIME[name.slice(i).toLowerCase()]) || 'application/octet-stream';
  }

  const DB_NAME = 'moyue', STORE_BOOKS = 'books', STORE_COVERS = 'covers';
  function idbOpen() {
    return new Promise((resolve, reject) => {
      const rq = indexedDB.open(DB_NAME, 1);
      rq.onupgradeneeded = () => {
        const db = rq.result;
        if (!db.objectStoreNames.contains(STORE_BOOKS)) db.createObjectStore(STORE_BOOKS, { keyPath: 'id' });
        if (!db.objectStoreNames.contains(STORE_COVERS)) db.createObjectStore(STORE_COVERS);
      };
      rq.onsuccess = () => resolve(rq.result);
      rq.onerror = () => reject(rq.error);
    });
  }
  function idb(store, mode, fn) { // 事务包装
    return idbOpen().then(db => new Promise((resolve, reject) => {
      const tx = db.transaction(store, mode);
      const rq = fn(tx.objectStore(store));
      tx.oncomplete = () => { resolve(rq && rq.result); db.close(); };
      tx.onerror = () => { reject(tx.error); db.close(); };
      tx.onabort = () => { reject(tx.error); db.close(); };
    }));
  }
  const idbGet = (store, key) => idb(store, 'readonly', s => s.get(key));
  const idbPut = (store, val, key) => idb(store, 'readwrite', s => key === undefined ? s.put(val) : s.put(val, key));
  const idbDel = (store, key) => idb(store, 'readwrite', s => s.delete(key));
  const idbAll = store => idb(store, 'readonly', s => s.getAll());

  /* ══════════════════ 导出 ══════════════════ */

  return {
    ZipReader, importBook, importTxt, detectFormat, chapterImages, comicNodes,
    ensureEpubFlat, restoreEpubOrig,
    imgSize, naturalCmp, normRel, smartDecode, escapeHtml, readText, mimeOf,
    virtualUrl: (bookId, rel) => `z/${bookId}/${encodeURIComponent(rel)}`, // 相对路径，由 sw.js 拦截读取
    randId, HAS_DEFLATE,
    DB_NAME, STORE_BOOKS, STORE_COVERS, idbOpen, idbGet, idbPut, idbDel, idbAll,
  };
})();
