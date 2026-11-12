'use strict';
/* 墨阅·iPad版 —— 书库(IndexedDB) + 阅读器
   移植自 desktop/www/index.html（电脑版），差异：
   - 无后端：解析用 moyue-core.js，书存 IndexedDB，图片经 sw.js 虚拟路径 /z/ 按需解压
   - 文本模式用 Shadow DOM 内联渲染（iOS 上 iframe 内部滚动体验差）
   - 触摸交互：点按分区翻页 / 双指捏合缩放 / 长按删书 / 安全区适配 */

window.__errs = [];
window.addEventListener('error', e => { if (window.__errs.length < 20) window.__errs.push(String(e.message) + ' @ ' + (e.filename || '') + ':' + (e.lineno || '')); });

const { STORE_BOOKS, STORE_COVERS } = MoYue;
const $ = id => document.getElementById(id);
const stage = () => $('stage');

const THEMES = [["#FDFDFB", "#1B1D22"], ["#F6EFDD", "#57493A"], ["#CCE6CF", "#2C3A2E"], ["#101014", "#B8BCC6"]];
let themeIdx = +(localStorage.moyue_theme || 0), fontPct = +(localStorage.moyue_font || 100), zoomPct = 100;
let meta = null, rec = null, zip = null;       // rec: {id, meta, src(Blob), inlineDocs}
let curCh = 0, ratio = 0, appFrom = -1, appTo = -1, tailAdded = false, curPage = -1;
let saveTimer = null, restoreTimer = null;
let pdfDoc = null, pdfRendered = new Set();
let wakeLock = null, wakeSentinel = null;
let textBookCss = [], textStyleEl = null;
let coverUrls = [];

/* SW 是否已接管本页：是则漫画/文本资源走虚拟路径（按需解压省内存），否则退回 eager blob */
let swReady = !!(navigator.serviceWorker && navigator.serviceWorker.controller);
const eagerUrls = new Map();

function toast(msg) { const t = $('toast'); t.textContent = msg; t.style.opacity = 1; setTimeout(() => t.style.opacity = 0, 1800); }

/* ══════════ 资源地址：书内相对路径 → 可用 URL ══════════ */
async function resUrl(rel) {
  if (!zip) return null;
  rel = zip.fix(rel);
  if (swReady) return MoYue.virtualUrl(meta.id, rel);
  let u = eagerUrls.get(rel);
  if (!u) {
    const b = await zip.readBlob(rel, MoYue.mimeOf(rel));
    if (!b) return null;
    u = URL.createObjectURL(b);
    eagerUrls.set(rel, u);
  }
  return u;
}
function revokeEager() { for (const u of eagerUrls.values()) URL.revokeObjectURL(u); eagerUrls.clear(); }

/* ══════════ 书架 ══════════ */
async function loadLib() {
  const recs = (await MoYue.idbAll(STORE_BOOKS)) || [];
  recs.sort((a, b) => ((b.meta.lastReadAt || b.meta.addedAt || 0) - (a.meta.lastReadAt || a.meta.addedAt || 0)));
  for (const u of coverUrls) URL.revokeObjectURL(u);
  coverUrls = [];
  const g = $('grid'); g.innerHTML = '';
  $('empty').classList.toggle('hidden', recs.length > 0);
  for (const r of recs) {
    const b = r.meta;
    const pct = b.spine && b.spine.length ? Math.min(100, Math.round(((b.i || 0) + (b.r || 0)) / b.spine.length * 100)) : 0;
    const d = document.createElement('div'); d.className = 'card';
    const modeName = { epub: b.mode === 'comic' ? '漫画' : 'EPUB', pdf: 'PDF', txt: 'TXT', cbz: 'CBZ' }[b.format] || b.format;
    let covHtml = MoYue.escapeHtml((b.title || '书')[0]);
    try {
      const cov = await MoYue.idbGet(STORE_COVERS, b.id);
      if (cov) { const u = URL.createObjectURL(cov); coverUrls.push(u); covHtml = `<img src="${u}" alt="">`; }
    } catch (e) {}
    d.innerHTML = `<div class="cover">${covHtml}</div><div class="t">${MoYue.escapeHtml(b.title)}</div>
      <div class="a">${MoYue.escapeHtml(b.author || '')} · ${modeName}</div>
      <div class="pbar"><i style="width:${pct}%"></i></div><div class="p">${pct}%${pct > 0 ? ' 已读' : ''}</div>`;
    d.onclick = () => openBook(b.id);
    bindDelete(d, b);
    g.appendChild(d);
  }
}
function bindDelete(card, b) {
  let timer = null;
  const ask = e => {
    e.preventDefault();
    if (confirm(`删除《${b.title}》？\n（不影响你「文件」App 里的原书）`)) deleteBook(b.id);
  };
  card.addEventListener('contextmenu', ask);                       // 桌面右键
  card.addEventListener('pointerdown', () => {                     // 触屏长按
    timer = setTimeout(() => { timer = null; ask(new Event('x')); }, 550);
  });
  const cancel = () => { if (timer) { clearTimeout(timer); timer = null; } };
  card.addEventListener('pointerup', cancel);
  card.addEventListener('pointercancel', cancel);
  card.addEventListener('pointermove', cancel);
  card.addEventListener('pointerleave', cancel);
}
async function deleteBook(id) {
  await MoYue.idbDel(STORE_BOOKS, id);
  await MoYue.idbDel(STORE_COVERS, id);
  if (meta && meta.id === id) { meta = null; rec = null; zip = null; }
  loadLib();
}

async function extractCover(izip, m) {
  try {
    let src = null;
    if (m.format === 'epub' && m.cover && izip) src = m.cover;
    else if (m.format === 'cbz' && m.pages && m.pages.length) src = m.pages[0].src;
    if (!src || !izip) return null;
    const blob = await izip.readBlob(izip.fix(src), MoYue.mimeOf(src));
    if (!blob) return null;
    try { // 缩到最长边 400px 存 jpeg，省空间
      const bmp = await createImageBitmap(blob);
      const scale = Math.min(1, 400 / Math.max(bmp.width, bmp.height));
      const cv = document.createElement('canvas');
      cv.width = Math.max(1, Math.round(bmp.width * scale));
      cv.height = Math.max(1, Math.round(bmp.height * scale));
      cv.getContext('2d').drawImage(bmp, 0, 0, cv.width, cv.height);
      bmp.close();
      return await new Promise(r => cv.toBlob(r, 'image/jpeg', 0.85));
    } catch (e) { return blob; } // svg / 解码失败直接存原文件
  } catch (e) { return null; }
}

async function uploadFiles(files) {
  let ok = 0; const fails = [];
  for (const f of files) {
    toast(`正在导入：${f.name}`);
    try {
      const bytes = new Uint8Array(await f.arrayBuffer());
      const { m: bm, inlineDocs, zip: izip } = await importOne(bytes, f.name);
      const cov = await extractCover(izip, bm);
      const id = bm.id;
      const r = { id, meta: bm, src: new Blob([bytes]) };
      if (inlineDocs) r.inlineDocs = inlineDocs;
      await MoYue.idbPut(STORE_BOOKS, r);
      if (cov) await MoYue.idbPut(STORE_COVERS, cov, id);
      ok++;
    } catch (e) { fails.push(`${f.name}：${e && e.message || e}`); }
  }
  try { navigator.storage && navigator.storage.persist && navigator.storage.persist(); } catch (e) {}
  toast(`已导入 ${ok} 本` + (fails.length ? `，失败 ${fails.length} 本` : ''));
  if (fails.length) alert('导入失败：\n\n' + fails.join('\n'));
  loadLib();
}
async function importOne(bytes, name) {
  const { meta: m, inlineDocs, zip } = await MoYue.importBook(bytes, name);
  return { m: m, inlineDocs, zip };
}
$('file').onchange = e => { uploadFiles([...e.target.files]); e.target.value = ''; };
document.addEventListener('dragover', e => { e.preventDefault(); $('drop').classList.remove('hidden'); });
document.addEventListener('dragleave', e => { if (!e.relatedTarget) $('drop').classList.add('hidden'); });
document.addEventListener('drop', e => { e.preventDefault(); $('drop').classList.add('hidden'); uploadFiles([...e.dataTransfer.files]); });

/* ══════════ 打开书 ══════════ */
function spineIdxMap() {
  const map = {};
  (meta.spine || []).forEach((s, i) => { if (!(s.href in map)) map[s.href] = i; });
  return map;
}
async function openBook(id) {
  rec = await MoYue.idbGet(STORE_BOOKS, id);
  if (!rec) { toast('书不存在'); return; }
  meta = rec.meta;
  zip = null;
  if (meta.format === 'epub' || meta.format === 'cbz') {
    zip = new MoYue.ZipReader(rec.src);
    await zip.init();
  }
  curCh = Math.min(meta.i || 0, (meta.spine.length || 1) - 1); ratio = meta.r || 0;
  appFrom = appTo = -1; tailAdded = false; zoomPct = 100;
  $('library').classList.add('hidden'); $('reader').classList.remove('hidden');
  document.body.classList.remove('nochrome');
  $('rTitle').textContent = meta.title;
  applyTheme();
  const comic = meta.mode === 'comic', pdf = meta.mode === 'pdf';
  $('textwrap').classList.toggle('hidden', comic || pdf);
  $('strip').classList.toggle('hidden', !comic);
  $('pdfStrip').classList.toggle('hidden', !pdf);
  $('fontRow').style.display = (!comic && !pdf) ? 'flex' : 'none';
  $('spreadRow').style.display = (comic || pdf) && !(meta.merge > 0) ? 'flex' : 'none';
  $('mergeRow').style.display = (comic || pdf) ? 'flex' : 'none';
  $('seamRow').style.display = (comic || pdf) ? 'flex' : 'none';
  document.querySelectorAll('#spreadRow .chip').forEach((c, i) => c.classList.toggle('on', i === (meta.spread || 0)));
  document.querySelectorAll('#mergeRow .chip').forEach(c => c.classList.toggle('on', +c.dataset.v === (meta.merge || 0)));
  document.querySelectorAll('#seamRow .chip').forEach(c => c.classList.toggle('on', +c.dataset.v === (meta.seam ?? 1)));
  $('btnZoomOut').style.display = (comic || pdf) ? '' : 'none';
  $('zoomLabel').style.display = (comic || pdf) ? '' : 'none';
  const isPages = !!(meta.pages && meta.pages.length);
  $('btnPrev').style.display = (!comic && !pdf) ? '' : 'none';
  $('btnNext').style.display = (!comic && !pdf) ? '' : 'none';
  $('slider').max = isPages ? meta.pages.length - 1 : (meta.spine.length ? meta.spine.length - 1 : (meta.pageCount || 1) - 1) || 1;
  curPage = (meta.page >= 0 && (pdf || isPages)) ? meta.page : -1;
  keepAwake();
  if (pdf) initPdf();
  else if (comic) initComic(meta.page >= 0 ? meta.page : null);
  else initText();
}
function backToLib() {
  saveProgressNow();
  clearInterval(restoreTimer); restoreTimer = null;
  releaseWakeLock(); revokeEager();
  if (pdfDoc) { try { pdfDoc.destroy(); } catch (e) {} pdfDoc = null; }
  pdfRendered.clear(); textStyleEl = null;
  $('reader').classList.add('hidden'); $('library').classList.remove('hidden');
  $('setPop').classList.add('hidden'); closeToc();
  loadLib();
}

/* ══════════ 进度持久化 ══════════ */
function pageOfSave() { return (meta && meta.pages && meta.pages.length && curPage >= 0) ? curPage : -1; }
async function persistMeta() {
  if (!meta || !rec) return;
  meta.i = curCh;
  meta.r = ratio;
  meta.page = (curPage >= 0 && (meta.mode === 'pdf' || (meta.pages && meta.pages.length))) ? curPage : -1;
  meta.lastReadAt = Date.now();
  rec.meta = meta;
  try { await MoYue.idbPut(STORE_BOOKS, rec); } catch (e) {}
}
function saveProgress(i, r) {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => persistMeta(), 1200);
}
function saveProgressNow() { clearTimeout(saveTimer); persistMeta(); }

/* ══════════ 主题 / 字号 / 缩放 ══════════ */
function applyTheme() {
  const [bg, fg] = THEMES[themeIdx];
  document.documentElement.style.setProperty('--rbg', bg);
  document.documentElement.style.setProperty('--rfg', fg);
  document.querySelectorAll('.dot').forEach((d, i) => d.classList.toggle('on', i === themeIdx));
  refreshTextCss();
}
function setTheme(i) { themeIdx = i; localStorage.moyue_theme = i; applyTheme(); }
function setFont(v) {
  fontPct = Math.min(200, Math.max(70, v)); localStorage.moyue_font = fontPct;
  $('fontLabel').textContent = fontPct + '%'; refreshTextCss();
}
function textThemeCss() {
  const [bg, fg] = THEMES[themeIdx];
  return `:host{display:block;background:${bg}}
.moyue-book{background:transparent!important;color:${fg}!important;font-family:Georgia,"Noto Serif SC","Songti SC",serif!important;line-height:1.75!important;margin:0!important;padding:2% 8% 18vh!important;font-size:${Math.round(18 * fontPct / 100)}px!important;word-break:break-word}
.moyue-book img,.moyue-book svg,.moyue-book video{max-width:100%!important;height:auto!important}
.moyue-book a{color:inherit}`;
}
function refreshTextCss() {
  if (!textStyleEl) return;
  textStyleEl.textContent = textThemeCss() + '\n' +
    textBookCss.map(c => c.replace(/(^|[{}~,]\s*)(html|body)\b/g, '$1.moyue-book')).join('\n');
}
function setZoom(v) {
  zoomPct = Math.min(250, Math.max(50, Math.round(v)));
  $('zoomLabel').textContent = zoomPct + '%';
  const s = meta && meta.mode === 'comic' ? $('strip') : $('pdfStrip');
  s.style.width = zoomPct + '%';
}
document.addEventListener('wheel', e => {
  if (!meta || meta.mode === 'text') return;
  if (e.ctrlKey) { e.preventDefault(); setZoom(zoomPct + (e.deltaY < 0 ? 10 : -10)); }
}, { passive: false });

/* ══════════ 目录抽屉 / 设置面板 ══════════ */
function toggleToc() {
  const open = $('toc').classList.toggle('open');
  $('tocMask').classList.toggle('hidden', !open);
  if (open) {
    const list = $('tocList'); list.innerHTML = '';
    (meta.toc && meta.toc.length ? meta.toc : []).forEach(t => {
      const d = document.createElement('div');
      d.className = 'it' + (t.spineIndex === curCh ? ' cur' : '');
      d.style.paddingLeft = (14 + t.level * 18) + 'px';
      d.textContent = t.title;
      d.onclick = () => { closeToc(); jumpTo(t.spineIndex); };
      list.appendChild(d);
    });
    if (!meta.toc || !meta.toc.length) list.innerHTML = '<div class="it">（本书没有目录）</div>';
  }
}
function closeToc() { $('toc').classList.remove('open'); $('tocMask').classList.add('hidden'); }
function togglePop() {
  const p = $('setPop'); p.classList.toggle('hidden');
  if (!p.classList.contains('hidden')) fillStorage();
}
async function fillStorage() {
  try {
    const est = await navigator.storage.estimate();
    $('storText').textContent = `存储：已用 ${(est.usage / 1048576).toFixed(1)} MB / 共约 ${(est.quota / 1073741824).toFixed(1)} GB（书保存在本机浏览器里）`;
  } catch (e) { $('storRow').style.display = 'none'; }
}

$('setPop').addEventListener('click', e => {
  const dot = e.target.closest('.dot');
  if (dot) { setTheme([...document.querySelectorAll('.dot')].indexOf(dot)); return; }
  const chip = e.target.closest('.chip');
  if (!chip) return;
  const row = chip.parentElement;
  if (row.id === 'seamRow') setSeam(+chip.dataset.v);
  else if (row.id === 'mergeRow') setMerge(+chip.dataset.v);
  else if (row.id === 'spreadRow') setSpread(+chip.dataset.v);
});
$('fontRow').addEventListener('click', e => {
  if (e.target.id === 'fontMinus') setFont(fontPct - 10);
  else if (e.target.id === 'fontPlus') setFont(fontPct + 10);
});

async function setSpread(v) {
  meta.spread = v;
  document.querySelectorAll('#spreadRow .chip').forEach((c, i) => c.classList.toggle('on', i === v));
  await persistMeta();
  if (meta.mode === 'comic') loadComicFrom(curCh, 0.0);
  else if (meta.mode === 'pdf') buildPdfHolders();
}
async function setSeam(v) {
  meta.seam = v;
  document.querySelectorAll('#seamRow .chip').forEach(c => c.classList.toggle('on', +c.dataset.v === v));
  await persistMeta();
  if (meta.mode === 'comic') loadComicFrom(curCh, 0.0);
  else if (meta.mode === 'pdf') buildPdfHolders();
}
async function setMerge(v) {
  meta.merge = v;
  document.querySelectorAll('#mergeRow .chip').forEach(c => c.classList.toggle('on', +c.dataset.v === v));
  if (meta.format === 'epub' && meta.mode === 'comic') {
    // EPUB 漫画：摊平成页流（或还原原书结构）
    if (v > 0) meta = await MoYue.ensureEpubFlat(meta, item => MoYue.chapterImages(zip, item));
    else meta = MoYue.restoreEpubOrig(meta);
    rec.meta = meta;
  }
  await persistMeta();
  const isPages = !!(meta.pages && meta.pages.length);
  $('slider').max = isPages ? meta.pages.length - 1 : Math.max(0, (meta.spine.length || 1) - 1);
  curCh = Math.max(0, Math.min(curCh, (meta.spine.length || 1) - 1));
  $('spreadRow').style.display = !(meta.merge > 0) ? 'flex' : 'none';
  if (meta.mode === 'comic') loadComicFrom(curCh, 0.0);
  else if (meta.mode === 'pdf') buildPdfHolders();
}

/* ══════════ 文本模式（Shadow DOM 内联渲染） ══════════ */
async function rewriteCssUrls(css, cssBase) {
  const re = /url\(\s*(['"]?)([^'")]+)\1\s*\)/g;
  const map = new Map(); const jobs = [];
  let m;
  while ((m = re.exec(css))) {
    const u = m[2].trim();
    if (/^(data:|blob:|https?:|#)/i.test(u) || map.has(u)) continue;
    const rel = MoYue.normRel(cssBase, u);
    if (!zip || !zip.has(rel)) { map.set(u, u); continue; }
    jobs.push(resUrl(rel).then(url => { if (url) map.set(u, url); }));
  }
  await Promise.all(jobs);
  return css.replace(re, (mm, q, u) => { const v = map.get(u.trim()); return v ? `url("${v}")` : mm; });
}

function scrollToAnchorIn(shadow, frag) {
  if (!frag || !shadow) return false;
  const el = shadow.querySelector(`[id="${CSS.escape(frag)}"]`) || shadow.querySelector(`a[name="${CSS.escape(frag)}"]`);
  if (el) { el.scrollIntoView(); return true; }
  return false;
}

/* 把一章 HTML 装配进 container（书架内章节或漫画里的文字回退共用） */
async function mountTextChapter(container, idx) {
  const item = meta.spine[idx];
  const href = zip ? zip.fix(item.href) : item.href;
  const base = href.includes('/') ? href.slice(0, href.lastIndexOf('/')) : '';
  container.innerHTML = '';
  const host = document.createElement('div'); host.className = 'txthost';
  container.appendChild(host);
  const shadow = host.attachShadow({ mode: 'open' });

  /* spine 直接就是图片的 EPUB（部分漫画书） */
  if ((item.mediaType || '').startsWith('image/') && zip && zip.has(href)) {
    const u = await resUrl(href);
    const st = document.createElement('style'); st.textContent = textThemeCss();
    const w = document.createElement('div'); w.className = 'moyue-book';
    const img = document.createElement('img'); img.src = u; img.style.width = '100%';
    w.appendChild(img);
    shadow.appendChild(st); shadow.appendChild(w);
    textStyleEl = st; textBookCss = [];
    return { shadow };
  }

  const html = (rec.inlineDocs && rec.inlineDocs[href] != null) ? rec.inlineDocs[href] : await MoYue.readText(zip, href);
  const doc = new DOMParser().parseFromString(html || '<html><body></body></html>', 'text/html');

  /* 样式：<link> 拉进来内联，<style> 原地重写 url() */
  const bookCss = [];
  for (const link of [...doc.querySelectorAll('link[href]')]) {
    const relAttr = (link.getAttribute('rel') || '').toLowerCase();
    if (!relAttr.split(/\s+/).includes('stylesheet')) continue;
    const rel = MoYue.normRel(base, link.getAttribute('href'));
    const css = zip ? await MoYue.readText(zip, rel) : '';
    if (css) bookCss.push(await rewriteCssUrls(css, rel.includes('/') ? rel.slice(0, rel.lastIndexOf('/')) : ''));
  }
  for (const st of [...doc.querySelectorAll('style')]) {
    const t = st.textContent || '';
    if (t) bookCss.push(await rewriteCssUrls(t, base));
  }

  /* 资源：img/image 指到书内文件 */
  for (const im of [...doc.querySelectorAll('img[src]')]) {
    const s = im.getAttribute('src');
    if (/^(data:|blob:|https?:)/i.test(s)) continue;
    const rel = MoYue.normRel(base, s);
    if (zip && zip.has(rel)) im.src = await resUrl(rel);
  }
  for (const im of [...doc.querySelectorAll('image')]) {
    for (const at of ['href', 'xlink:href']) {
      const s = im.getAttribute(at);
      if (s && !/^(data:|blob:|https?:|#)/i.test(s)) {
        const rel = MoYue.normRel(base, s);
        if (zip && zip.has(rel)) im.setAttribute(at, await resUrl(rel));
      }
    }
  }
  /* 链接：外部新窗口打开，书内链接接管跳章/锚点 */
  for (const a of [...doc.querySelectorAll('a[href]')]) {
    const h = a.getAttribute('href') || '';
    if (/^(https?:|mailto:)/i.test(h)) { a.target = '_blank'; continue; }
    a.setAttribute('data-moyue-href', h);
    a.setAttribute('href', 'javascript:void(0)');
  }
  doc.querySelectorAll('script,iframe,object,embed').forEach(n => n.remove());

  const body = doc.body;
  const wrap = document.createElement('div');
  wrap.className = 'moyue-book' + (body.className ? ' ' + body.className : '');
  for (const n of [...body.childNodes]) wrap.appendChild(document.importNode(n, true));
  const styleEl = document.createElement('style');
  shadow.appendChild(styleEl);
  shadow.appendChild(wrap);
  textStyleEl = styleEl; textBookCss = bookCss;
  refreshTextCss();

  shadow.addEventListener('click', async e => {
    const a = e.target.closest && e.target.closest('a');
    if (!a) return;
    e.preventDefault(); e.stopPropagation();
    const raw = a.getAttribute('data-moyue-href') ?? a.getAttribute('href') ?? '';
    if (!raw) return;
    if (/^(https?:|mailto:)/i.test(raw)) { window.open(raw, '_blank'); return; }
    const filePart = raw.split('#')[0], frag = raw.split('#')[1] || '';
    if (!filePart) { scrollToAnchorIn(shadow, frag); return; }
    const target = zip ? zip.fix(MoYue.normRel(base, filePart)) : MoYue.normRel(base, filePart);
    const map = spineIdxMap();
    const ti = map[target];
    if (ti != null && ti >= 0) {
      saveProgressNow();
      if (meta.mode === 'text') loadTextChapter(ti, 0, frag);
      else jumpTo(ti);
    } else scrollToAnchorIn(shadow, frag);
  });
  return { shadow };
}

function initText() { loadTextChapter(curCh, ratio); }
async function loadTextChapter(idx, restore, anchor) {
  curCh = idx; ratio = restore || 0;
  const item = meta.spine[idx];
  $('rChapter').textContent = (meta.toc.find(t => t.spineIndex === idx) || {}).title || `第 ${idx + 1}/${meta.spine.length} 节`;
  setSlider(idx);
  clearInterval(restoreTimer); restoreTimer = null;
  const { shadow } = await mountTextChapter($('textwrap'), idx);
  const st = stage(); st.scrollTop = 0;
  if (anchor) scrollToAnchorIn(shadow, anchor);
  let tries = 0;
  restoreTimer = setInterval(() => { // 图片加载会变高，轮询修正几次
    const max = st.scrollHeight - st.clientHeight;
    if (max > 0 && !anchor) st.scrollTop = ratio * max;
    if (anchor && tries === 3) scrollToAnchorIn(shadow, anchor);
    if (++tries > 40) { clearInterval(restoreTimer); restoreTimer = null; }
  }, 150);
}
function prevCh() { if (curCh > 0) { saveProgressNow(); loadTextChapter(curCh - 1, 0); } }
function nextCh() { if (curCh < meta.spine.length - 1) { saveProgressNow(); loadTextChapter(curCh + 1, 0); } }

/* ══════════ 漫画长条 ══════════ */
async function comicImgNode(n) {
  const img = document.createElement('img');
  // 不用 loading=lazy：部分 WebView/iOS 环境下懒加载不触发导致整页空白（与电脑版同决策），
  // 图片按需解压由 sw.js 虚拟路径承担，这里立即加载
  img.decoding = 'async';
  if (n.w && n.h) img.style.aspectRatio = `${n.w}/${n.h}`;
  img.src = await resUrl(n.src);
  return img;
}
async function comicNodeDom(n) {
  if (n.k === 'pair') {
    const d = document.createElement('div');
    d.className = `pair seam${n.seam}`;
    if (n.left && n.left.pg >= 0) d.setAttribute('data-pg', n.left.pg); // 与服务端版一致：用第一项的页号
    d.appendChild(await comicImgNode(n.left));
    d.appendChild(await comicImgNode(n.right));
    return d;
  }
  if (n.k === 'split') {
    const wrap = document.createElement('div');
    wrap.className = 'split-half' + (n.rightFirst ? ' second' : '');
    if (n.pg >= 0) wrap.setAttribute('data-pg', n.pg);
    wrap.appendChild(await comicImgNode(n));
    return wrap;
  }
  const el = await comicImgNode(n);
  if (n.pg >= 0) el.setAttribute('data-pg', n.pg);
  return el;
}
async function chapterDiv(idx) {
  const d = document.createElement('div');
  d.className = 'ch'; d.setAttribute('data-ch', idx);
  let imgs = null;
  const pagesMode = !!(meta.pages && meta.pages.length && (meta.format === 'pdf' || meta.format === 'cbz' || meta.chunked));
  if (!pagesMode && meta.format === 'epub') imgs = await MoYue.chapterImages(zip, meta.spine[idx]);
  const nodes = MoYue.comicNodes(meta, idx, imgs);
  if (Array.isArray(nodes)) {
    for (const n of nodes) d.appendChild(await comicNodeDom(n));
  } else if (nodes.textFallback) {
    await mountTextChapter(d, idx); // 整章无图：按文字章渲染
  } else {
    const t = document.createElement('div'); t.className = 'txtpage'; t.textContent = '（本章没有可显示的内容）';
    d.appendChild(t);
  }
  return d;
}
function initComic(targetPage) {
  const chunk = (targetPage != null && targetPage >= 0) ? Math.floor(targetPage / 20) : curCh;
  loadComicFrom(chunk, 0).then(ok => {
    if (!ok) return;
    if (targetPage != null && targetPage >= 0) {
      // 双页合并时目标页可能没有独立元素（在配对容器里），取 data-pg ≤ 目标页的最后一个元素
      let el = null;
      for (const e of $('strip').querySelectorAll('[data-pg]')) {
        const p = +e.getAttribute('data-pg');
        if (p <= targetPage) el = e; else break;
      }
      if (el) stage().scrollTop = el.offsetTop - 4;
      curPage = targetPage; updateComicUI();
      stage().dispatchEvent(new Event('scroll'));
    }
  });
}
function jumpToPage(p) {
  const n = meta.pages.length; p = Math.max(0, Math.min(n - 1, p | 0));
  curPage = p; saveProgressNow(); initComic(p);
}
let stripToken = 0; // 长条重建令牌：连续重建时丢弃过期请求，避免竞态把滚动位置清零
async function loadComicFrom(idx, restore) {
  const my = ++stripToken;
  appFrom = appTo = idx; tailAdded = false; ratio = restore;
  const strip = $('strip');
  strip.innerHTML = ''; revokeEager();
  setZoom(zoomPct);
  const d = await chapterDiv(idx);
  if (my !== stripToken) return false;
  strip.appendChild(d);
  stage().scrollTop = 0;
  stage().dispatchEvent(new Event('scroll'));
  if (restore > 0 && restore < 1) {
    let tries = 0;
    const t = setInterval(() => {
      if (my !== stripToken) { clearInterval(t); return; }
      const ch = strip.querySelector(`[data-ch="${idx}"]`);
      if (ch) { const m = ch.offsetHeight - stage().clientHeight; if (m > 0) stage().scrollTop = restore * m; }
      if (++tries > 40) clearInterval(t);
    }, 150);
  }
  updateComicUI();
  preloadAhead();
  return true;
}
async function preloadAhead() {
  const my = stripToken;
  const limit = swReady ? 6 : 2; // 无 SW 时 eager blob 占内存，少预载几章
  while (appTo < curCh + limit && appTo < meta.spine.length - 1) {
    appTo++;
    const d = await chapterDiv(appTo);
    if (my !== stripToken) return;
    $('strip').appendChild(d);
  }
  if (my !== stripToken) return;
  if (appTo >= meta.spine.length - 1 && !tailAdded) {
    tailAdded = true;
    const t = document.createElement('div'); t.className = 'tail'; t.textContent = '· 全书完 ·';
    $('strip').appendChild(t);
  }
  updateComicUI();
}
async function prependBehind() {
  if (curCh - appFrom >= 6 || appFrom <= 0) return;
  appFrom--;
  const my = stripToken;
  const st = stage(), h1 = $('strip').offsetHeight;
  const d = await chapterDiv(appFrom);
  if (my !== stripToken) return;
  $('strip').insertBefore(d, $('strip').firstChild);
  st.scrollTop += $('strip').offsetHeight - h1;
}
function updateComicUI() {
  if (meta.pages && meta.pages.length) {
    const p = curPage >= 0 ? curPage : 0;
    $('rChapter').textContent = `第 ${p + 1}/${meta.pages.length} 页`;
    setSlider(p); updateProgressText(); return;
  }
  const title = (meta.toc.find(t => t.spineIndex === curCh) || {}).title || '';
  $('rChapter').textContent = `${title ? title + ' · ' : ''}第 ${curCh + 1}/${meta.spine.length} 节`;
  setSlider(curCh); updateProgressText();
}

/* ══════════ PDF（pdf.js 长条懒渲染） ══════════ */
let pdfEvictAt = 0;
async function initPdf() {
  setZoom(zoomPct);
  if (pdfDoc) { try { pdfDoc.destroy(); } catch (e) {} pdfDoc = null; }
  const data = await rec.src.arrayBuffer();
  pdfDoc = await pdfjsLib.getDocument({ data }).promise;
  meta.pageCount = pdfDoc.numPages;
  meta.toc = []; for (let s = 0; s < pdfDoc.numPages; s += 20)
    meta.toc.push({ title: `第 ${s + 1}–${Math.min(s + 20, pdfDoc.numPages)} 页`, spineIndex: Math.floor(s / 20) });
  meta.spine = meta.toc.map((t, i) => ({ href: '', mediaType: '' }));
  meta.toc.forEach((t, i) => t.spineIndex = i);
  $('slider').max = pdfDoc.numPages - 1;
  await buildPdfHolders();
  pdfJump(meta.page >= 0 ? meta.page : Math.min(meta.i || 0, pdfDoc.numPages - 1));
}
async function buildPdfHolders() {
  const strip = $('pdfStrip');
  strip.innerHTML = ''; pdfRendered.clear();
  setZoom(zoomPct);
  const vps = [];
  for (let p = 1; p <= pdfDoc.numPages; p++) {
    const page = await pdfDoc.getPage(p);
    vps.push(page.getViewport({ scale: 1 }));
  }
  const merge = meta.merge || 0, off = (merge === 3 || merge === 4) ? 1 : 0, rtl = (merge === 2 || merge === 4);
  const spread = meta.spread || 0;
  const mkHolder = (n, aspect, half) => {
    const d = document.createElement('div'); d.className = 'holder'; d.dataset.page = n;
    if (half) d.dataset.half = half;
    d.style.aspectRatio = aspect;
    strip.appendChild(d); return d;
  };
  let i = 0;
  while (i < vps.length) {
    const vp = vps[i], n = i + 1;
    const wide = vp.width / vp.height >= 1.1;
    if (merge > 0 && wide) { mkHolder(n, vp.width + '/' + vp.height); i++; continue; }
    if (merge > 0 && !wide && i + 1 < vps.length) {
      const v2 = vps[i + 1];
      if (v2.width === vp.width && v2.height === vp.height && (((i - off) % 2) + 2) % 2 === 0) {
        // 两个同尺寸竖版页拼成一行跨页（右起时后页在左）
        const d = document.createElement('div'); d.className = 'pair';
        const pair = rtl ? [i + 2, i + 1] : [i + 1, i + 2];
        for (const pn of pair) {
          const h = document.createElement('div'); h.className = 'holder'; h.dataset.page = pn;
          h.style.aspectRatio = vps[pn - 1].width + '/' + vps[pn - 1].height;
          d.appendChild(h);
        }
        strip.appendChild(d); i += 2; continue;
      }
    }
    if (spread && wide) {
      mkHolder(n, (vp.width / 2) + '/' + vp.height, 'l');
      mkHolder(n, (vp.width / 2) + '/' + vp.height, 'r');
    } else mkHolder(n, vp.width + '/' + vp.height);
    i++;
  }
  observePdf();
  // 前两页直接渲染，不依赖 IntersectionObserver（首屏立即可见）
  [...document.querySelectorAll('.holder')].slice(0, 2).forEach(h => {
    if (!pdfRendered.has(h)) markRendered(h, renderPdfHolder(h));
  });
  // 兜底：IO 不触发或渲染被挂起时，周期性补渲染视口附近缺 canvas 的页
  let tries = 0;
  const retry = setInterval(() => {
    if ($('reader').classList.contains('hidden') || ++tries > 20) { clearInterval(retry); return; }
    const st = stage(), top = st.scrollTop, bot = top + st.clientHeight;
    document.querySelectorAll('.holder').forEach(h => {
      if (!h.querySelector('canvas') && h.offsetTop < bot + 2000 && h.offsetTop + h.offsetHeight > top - 2000)
        markRendered(h, renderPdfHolder(h));
    });
  }, 3000);
}
function markRendered(h, pr) { Promise.resolve(pr).then(() => pdfRendered.add(h)).catch(() => {}); }
function observePdf() {
  const io = new IntersectionObserver(ents => {
    for (const en of ents) {
      if (en.isIntersecting && !pdfRendered.has(en.target) && !en.target.querySelector('canvas'))
        markRendered(en.target, renderPdfHolder(en.target));
    }
  }, { root: stage(), rootMargin: '1500px' });
  document.querySelectorAll('.holder').forEach(h => io.observe(h));
}
async function renderPdfHolder(holder) {
  const n = +holder.dataset.page;
  const page = await pdfDoc.getPage(n);
  const half = holder.dataset.half;
  const targetW = 1600;
  const vp = page.getViewport({ scale: 1 });
  if (half) { // 宽页拆分：离屏整页渲染后裁半页
    const v2 = page.getViewport({ scale: targetW / vp.width });
    const off = document.createElement('canvas');
    off.width = v2.width; off.height = v2.height;
    await page.render({ canvasContext: off.getContext('2d'), viewport: v2 }).promise;
    const cv = document.createElement('canvas');
    cv.width = Math.floor(v2.width / 2); cv.height = v2.height;
    const ctx = cv.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height);
    ctx.drawImage(off, half === 'l' ? 0 : cv.width, 0, cv.width, cv.height, 0, 0, cv.width, cv.height);
    holder.appendChild(cv);
  } else {
    const v2 = page.getViewport({ scale: targetW / vp.width });
    const cv = document.createElement('canvas');
    cv.width = v2.width; cv.height = v2.height;
    const ctx = cv.getContext('2d');
    ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, cv.width, cv.height);
    await page.render({ canvasContext: ctx, viewport: v2 }).promise;
    holder.appendChild(cv);
  }
}
function pdfEvictFar() { // 远离视口的页回收画布，防 iPad 内存无限增长（回到视口会自动重渲染）
  const st = stage(), top = st.scrollTop, bot = top + st.clientHeight;
  document.querySelectorAll('.holder').forEach(h => {
    if (h.querySelector('canvas') && (h.offsetTop > bot + 5000 || h.offsetTop + h.offsetHeight < top - 5000)) {
      h.querySelector('canvas').remove();
      pdfRendered.delete(h);
    }
  });
}
function pdfJump(pageIdx) {
  const holders = document.querySelectorAll('.holder');
  for (const h of holders) {
    if (+h.dataset.page >= pageIdx + 1) { stage().scrollTop = h.offsetTop - 4; break; }
  }
  stage().dispatchEvent(new Event('scroll'));
  curCh = Math.min(Math.floor(pageIdx / 20), (meta.spine.length || 1) - 1);
  ratio = 0; setSlider(pageIdx); $('rChapter').textContent = `第 ${pageIdx + 1}/${pdfDoc.numPages} 页`;
}

/* ══════════ 统一滚动处理 ══════════ */
function setSlider(v) { $('slider').value = v; updateProgressText(); }
function updateProgressText() {
  const isPages = !!(meta && meta.pages && meta.pages.length);
  const n = isPages ? meta.pages.length : (meta.spine.length || meta.pageCount || 1);
  const pct = Math.min(100, Math.round((+$('slider').value + 1) / n * 100));
  $('progressText').textContent = `${pct}% · 第 ${+$('slider').value + 1}/${n}${isPages ? ' 页' : ' 节'}`;
}
function jumpTo(idx) {
  if (meta.mode === 'text') loadTextChapter(idx, 0);
  else if (meta.mode === 'comic') loadComicFrom(idx, 0);
  else if (meta.mode === 'pdf') pdfJump(idx);
  saveProgressNow();
}
function sliderCommit(v) {
  if (meta.mode === 'comic' && meta.pages && meta.pages.length) jumpToPage(+v);
  else jumpTo(+v);
}
stage().addEventListener('scroll', () => {
  if (!meta) return;
  const st = stage();
  if (meta.mode === 'text') {
    const max = st.scrollHeight - st.clientHeight;
    if (max > 0) ratio = Math.min(1, Math.max(0, st.scrollTop / max));
    saveProgress();
    return;
  }
  if (meta.mode === 'comic') {
    const strip = $('strip');
    let ch = null;
    for (const d of strip.querySelectorAll('[data-ch]')) {
      if (d.getBoundingClientRect().top <= st.getBoundingClientRect().top + st.clientHeight * 0.35) ch = +d.getAttribute('data-ch');
    }
    if (ch != null && ch >= 0 && ch !== curCh) { curCh = ch; updateComicUI(); }
    let pgEl = null;
    for (const el of strip.querySelectorAll('[data-pg]')) {
      if (el.getBoundingClientRect().top <= st.getBoundingClientRect().top + st.clientHeight * 0.35) pgEl = el;
    }
    if (pgEl) { const p = +pgEl.getAttribute('data-pg'); if (p !== curPage) { curPage = p; updateComicUI(); } }
    const cd = strip.querySelector(`[data-ch="${curCh}"]`);
    if (cd) { const top = cd.offsetTop, m = cd.offsetHeight - st.clientHeight; ratio = m > 0 ? Math.min(1, Math.max(0, (st.scrollTop - top) / m)) : 1; }
    saveProgress();
    const bottom = strip.offsetHeight - st.clientHeight - st.scrollTop;
    if (bottom < st.clientHeight * 3) preloadAhead();
    if (st.scrollTop <= 4) prependBehind();
    return;
  }
  if (meta.mode === 'pdf' && pdfDoc) {
    let cur = null;
    for (const h of document.querySelectorAll('.holder')) {
      if (h.getBoundingClientRect().top <= st.getBoundingClientRect().top + st.clientHeight * 0.35) cur = +h.dataset.page - 1;
    }
    if (cur != null) {
      setSlider(cur); $('rChapter').textContent = `第 ${cur + 1}/${pdfDoc.numPages} 页`;
      curCh = Math.min(Math.floor(cur / 20), (meta.spine.length || 1) - 1);
      curPage = cur;
      const h = document.querySelector(`.holder[data-page='${cur + 1}']`);
      if (h) { const m = h.offsetHeight - st.clientHeight; ratio = m > 0 ? Math.min(1, Math.max(0, (st.scrollTop - h.offsetTop) / m)) : 1; }
      saveProgress();
    }
    const now = Date.now();
    if (now - pdfEvictAt > 1500) { pdfEvictAt = now; pdfEvictFar(); }
  }
}, { passive: true });

/* ══════════ 触摸交互：点按分区 / 捏合缩放 / 双击复位 ══════════ */
function toggleChrome() { document.body.classList.toggle('nochrome'); }
function pageStep(dir) {
  const st = stage();
  if (meta.mode === 'text') {
    const max = st.scrollHeight - st.clientHeight;
    if (dir > 0 && st.scrollTop >= max - 2 && curCh < meta.spine.length - 1) { nextCh(); return; }  // 章末右点 → 下一章
    if (dir < 0 && st.scrollTop <= 2 && curCh > 0) { prevCh(); return; }                             // 章首左点 → 上一章
  }
  st.scrollBy({ top: dir * st.clientHeight * 0.88, behavior: 'smooth' });
}
stage().addEventListener('click', e => {
  if (!meta || $('reader').classList.contains('hidden')) return;
  if (e.target.closest('a')) return; // 书内链接自行处理
  const w = stage().clientWidth;
  if (e.clientX < w / 3) pageStep(-1);
  else if (e.clientX > w * 2 / 3) pageStep(1);
  else toggleChrome();
});
let pinch = null;
stage().addEventListener('touchstart', e => {
  if (e.touches.length === 2 && meta && meta.mode !== 'text')
    pinch = { d: Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY), z: zoomPct };
}, { passive: true });
stage().addEventListener('touchmove', e => {
  if (pinch && e.touches.length === 2) {
    e.preventDefault();
    const d = Math.hypot(e.touches[0].clientX - e.touches[1].clientX, e.touches[0].clientY - e.touches[1].clientY);
    setZoom(pinch.z * d / pinch.d);
  }
}, { passive: false });
stage().addEventListener('touchend', () => { pinch = null; });
stage().addEventListener('dblclick', e => {
  if (meta && meta.mode !== 'text' && !e.target.closest('a')) setZoom(100);
});
stage().addEventListener('contextmenu', e => { if (e.target.closest('img')) e.preventDefault(); }); // 禁漫画长按菜单

/* ══════════ 键盘（桌面测试用） ══════════ */
document.addEventListener('keydown', e => {
  if ($('reader').classList.contains('hidden')) return;
  if (e.key === 'Escape') { closeToc(); $('setPop').classList.add('hidden'); backToLib(); }
  else if (e.key === 'ArrowRight' || e.key === 'PageDown') {
    meta.mode === 'text' ? nextCh() : stage().scrollBy({ top: stage().clientHeight * 0.88, behavior: 'smooth' });
  } else if (e.key === 'ArrowLeft' || e.key === 'PageUp') {
    meta.mode === 'text' ? prevCh() : stage().scrollBy({ top: -stage().clientHeight * 0.88, behavior: 'smooth' });
  }
});

/* ══════════ 屏幕常亮 / 生命周期 ══════════ */
async function keepAwake() {
  releaseWakeLock();
  try { wakeLock = await (navigator.wakeLock && navigator.wakeLock.request('screen')); } catch (e) { wakeLock = null; }
}
function releaseWakeLock() { try { wakeLock && wakeLock.release(); } catch (e) {} wakeLock = null; }
window.addEventListener('pagehide', saveProgressNow);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) saveProgressNow();
  else if (meta && !$('reader').classList.contains('hidden')) keepAwake();
});

/* ══════════ 启动 ══════════ */
$('libTitle').textContent = '墨阅';
applyTheme();
$('fontLabel').textContent = fontPct + '%';
$('btnBack').onclick = backToLib;
$('btnPrev').onclick = prevCh;
$('btnNext').onclick = nextCh;
$('btnToc').onclick = toggleToc;
$('btnSet').onclick = togglePop;
$('btnZoomOut').onclick = () => setZoom(zoomPct - 10);
$('btnZoomIn').onclick = () => setZoom(zoomPct + 10);
$('slider').oninput = () => updateProgressText();
$('slider').onchange = function () { sliderCommit(this.value); };
$('tocMask').onclick = closeToc;
$('tocClose').onclick = closeToc;
$('btnImport2').onclick = () => $('file').click();

if ('serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    swReady = true;
    // 新版 SW 接管后自动刷新一次，让页面用上最新外壳（仅一次，避免循环）
    if (!sessionStorage.getItem('moyue_reloaded')) {
      sessionStorage.setItem('moyue_reloaded', '1');
      location.reload();
    }
  });
}
if (!MoYue.HAS_DEFLATE)
  setTimeout(() => alert('当前浏览器不支持 ZIP 解压（需 iPadOS 16.4+ / 较新浏览器），漫画类书籍无法导入'), 600);

loadLib().then(() => {
  const m = location.hash.match(/id=([A-Za-z0-9]+)/); // 支持 #id=书id 直达阅读
  if (m) openBook(m[1]);
});
