/* 墨阅·iPad版 —— Service Worker
   1) 应用外壳预缓存：添加到主屏幕后离线可用
   2) 虚拟文件系统 /z/{书id}/{条目名}：按需从 IndexedDB 里的原始压缩包解出单个文件，
      让漫画长条像电脑版本地服务一样按需加载（不整本解压占内存） */
'use strict';
importScripts('moyue-core.js');

const VERSION = 'moyue-ipad-v8';
const BASE = new URL('./', self.registration.scope).href;
const SHELL = [
  './', './index.html', './app.js', './moyue-core.js', './manifest.webmanifest',
  './icons/apple-touch-icon.png', './icons/icon-192.png', './icons/icon-512.png',
  './vendor/pdf.min.js', './vendor/pdf.worker.min.js',
].map(p => new URL(p, BASE).href);

self.addEventListener('install', e => {
  e.waitUntil(caches.open(VERSION).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k !== VERSION) await caches.delete(k);
    await self.clients.claim();
  })());
});

/* ── 书籍虚拟文件系统 ── */
const zipCache = new Map(); // 书id → Promise<ZipReader>，LRU 上限 4 本
function zipOf(bid) {
  let p = zipCache.get(bid);
  if (!p) {
    p = (async () => {
      const r = await MoYue.idbGet(MoYue.STORE_BOOKS, bid);
      if (!r || !r.src) throw new Error('book not found');
      const z = new MoYue.ZipReader(r.src);
      await z.init();
      return z;
    })();
    p.catch(() => zipCache.delete(bid)); // 失败不缓存
    zipCache.set(bid, p);
    if (zipCache.size > 4) zipCache.delete(zipCache.keys().next().value);
  } else { // LRU 触碰
    zipCache.delete(bid); zipCache.set(bid, p);
  }
  return p;
}
async function serveBook(bid, rel) {
  try {
    const z = await zipOf(bid);
    const entry = z.findEntry(z.fix(rel));
    if (!entry) return new Response('not found', { status: 404 });
    const bytes = await z.readEntry(entry);
    return new Response(bytes, {
      headers: { 'Content-Type': MoYue.mimeOf(rel), 'Content-Length': String(bytes.byteLength), 'Cache-Control': 'no-store' },
    });
  } catch (err) {
    return new Response(String(err && err.message || err), { status: 500 });
  }
}

/* ── 拦截 ── */
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (url.origin !== location.origin) return;
  const m = /\/z\/([0-9a-f]{16})\/(.+)$/.exec(url.pathname);
  if (m && e.request.method === 'GET') {
    e.respondWith(serveBook(m[1], decodeURIComponent(m[2])));
    return;
  }
  if (e.request.mode === 'navigate') { // 页面：网络优先（保证更新），离线回缓存
    e.respondWith(networkFirst(e.request));
    return;
  }
  if (e.request.method === 'GET' && url.href.startsWith(BASE)) {
    e.respondWith(cacheFirst(e.request));
  }
});

async function networkFirst(req) {
  const cache = await caches.open(VERSION);
  try {
    const res = await fetch(req);
    cache.put(req, res.clone());
    return res;
  } catch (e) {
    return (await cache.match(req)) || (await cache.match(new URL('./index.html', BASE).href)) || Response.error();
  }
}
async function cacheFirst(req) {
  const cache = await caches.open(VERSION);
  const hit = await cache.match(req);
  if (hit) return hit;
  const res = await fetch(req);
  if (res.ok) cache.put(req, res.clone());
  return res;
}
