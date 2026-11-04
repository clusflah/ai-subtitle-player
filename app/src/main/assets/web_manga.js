// 漫画识别：找出网页上连续的漫画图片（一个容器里一串宽大的竖图），
// 连同上一话、下一话、目录链接报告给 App，App 显示「漫画阅读模式」入口。
// 每个框架都跑（有的网站把漫画放在另一个域名的 iframe 里），各自报告，App 选图最多的那个。
(() => {
  if (window.__voxManga) return;
  let last = '';
  let timer = 0;

  // 懒加载的图片真正的地址常放在这些属性里
  const LAZY = ['data-src', 'data-original', 'data-lazy-src', 'data-lazy', 'data-echo', 'data-url', 'data-img',
    'data-original-src', 'data-actualsrc', 'data-kksrc', 'data-srcset', 'file', '_src'];

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return ''; }
  }

  function bestSrcset(v) {
    // "a.jpg 1x, b.jpg 2x" → 取最后一个（最大的）
    const parts = v.split(',').map((s) => s.trim().split(/\s+/)[0]).filter(Boolean);
    return parts[parts.length - 1] || '';
  }

  function srcOf(img) {
    for (const a of LAZY) {
      const v = img.getAttribute(a);
      if (v && !/^data:/.test(v) && !/^\s*$/.test(v)) return abs(a.indexOf('srcset') >= 0 ? bestSrcset(v) : v.trim());
    }
    const set = img.getAttribute('srcset');
    if (set) { const s = bestSrcset(set); if (s && !/^data:/.test(s)) return abs(s); }
    const cur = img.currentSrc || img.src || '';
    if (!cur || /^(data|blob|about):/.test(cur)) return '';
    return cur;
  }

  const host = location.hostname.split('.').slice(-2).join('.');

  // 像漫画页的图：在网页上够宽（或原图够大），不是横幅、图标；不在指向别的网站的链接里（那是广告）
  function candidate(img) {
    const src = srcOf(img);
    if (!src || !/^https?:/.test(src)) return null;
    if (/\.(svg|gif)(\?|$)/i.test(src) && !img.getAttribute('data-src')) return null;
    const r = img.getBoundingClientRect();
    const vw = Math.max(document.documentElement.clientWidth || 0, innerWidth || 0) || 1;
    const nw = img.naturalWidth, nh = img.naturalHeight;
    const loaded = nw > 2 && nh > 2;
    const wideOnPage = r.width >= Math.min(vw * 0.45, 320);
    const bigNatural = loaded && nw >= 400 && nh >= 400;
    if (!wideOnPage && !bigNatural) return null;
    // 已加载的：太扁的是横幅广告
    if (loaded && nh / nw < 0.55) return null;
    if (!loaded && r.width > 0 && r.height > 0 && r.height / r.width < 0.3 && r.height > 20) return null;
    const a = img.closest('a[href]');
    if (a) {
      const h = (a.hostname || '').split('.').slice(-2).join('.');
      if (h && h !== host) return null;
    }
    if (getComputedStyle(img).display === 'none') return null;
    return src;
  }

  // 网页自己解密出来的图（blob: 地址，App 下载不了）和还没加载出来的占位图：位置像漫画页（够宽）就算一个位置。
  // 只在顶层网页认（App 只能请顶层网页交出图片）
  function blobSlot(img) {
    if (window !== window.top) return '';
    const cur = img.currentSrc || img.getAttribute('src') || '';
    const blob = /^blob:/.test(cur);
    if (!blob && cur && !/^data:/.test(cur)) return '';
    if (srcOf(img)) return '';
    const r = img.getBoundingClientRect();
    const vw = Math.max(document.documentElement.clientWidth || 0, innerWidth || 0) || 1;
    if (r.width < Math.min(vw * 0.45, 320)) return '';
    if (getComputedStyle(img).display === 'none') return '';
    const a = img.closest('a[href]');
    if (a) {
      const h = (a.hostname || '').split('.').slice(-2).join('.');
      if (h && h !== host) return '';
    }
    return blob ? 'blob' : 'empty';
  }

  /** 解密图那种网页上的漫画图片位置（按网页上的顺序）；exportImage 按序号找 */
  let slots = [];

  /** 按共同的容器分组（往上找 4 层），返回图最多的那个容器和图数 */
  function bestContainer(list) {
    const counts = new Map();
    for (const c of list) {
      let e = c.img.parentElement;
      for (let d = 0; e && d < 4; d++, e = e.parentElement) {
        counts.set(e, (counts.get(e) || 0) + 1);
        if (e === document.body) break;
      }
    }
    let best = null, n = 0;
    for (const [e, k] of counts) {
      // 一样多时取更深（更小）的容器
      if (k > n || (k === n && best && best.contains(e))) { best = e; n = k; }
    }
    return [best, n];
  }

  function scanImages() {
    const imgs = [...document.images];
    const cands = [];
    const blobs = [];
    for (const img of imgs) {
      const src = candidate(img);
      if (src) { cands.push({ img, src }); continue; }
      const kind = blobSlot(img);
      if (kind) blobs.push({ img, src: '', kind });
    }
    // 有解密出来的图：连同普通图、占位图一起看
    if (blobs.some(b => b.kind === 'blob')) {
      const all = cands.concat(blobs);
      const [best, n] = bestContainer(all);
      if (best && n >= 3 && blobs.some(b => b.kind === 'blob' && best.contains(b.img))) {
        const inBest = new Set(all.filter(c => best.contains(c.img)).map(c => c.img));
        slots = imgs.filter(img => inBest.has(img));
        const bySrc = new Map(cands.map(c => [c.img, c.src]));
        return slots.map((img, i) => bySrc.get(img) || 'voxblob:' + i);
      }
    }
    slots = [];
    if (cands.length < 3) return [];
    // 取图最多的那组：头像、封面推荐、广告图分散在别处
    const [best, n] = bestContainer(cands);
    if (!best || n < 3) return [];
    const seen = new Set();
    const pages = [];
    for (const c of cands) {
      if (!best.contains(c.img) || seen.has(c.src)) continue;
      seen.add(c.src);
      pages.push(c.src);
    }
    return pages;
  }

  // ---- 已知网站：整话的图片地址在网页数据里，网页上一次只显示一张（翻页式），上面按图片认不出来 ----
  let siteData;   // undefined 还没看过；null 不是这类网站
  let siteResult = null;

  // 看漫画（manhuagui）：打包压缩过的脚本里调 SMH.reader({images, sl, nextId, prevId})。
  // 把这段脚本再跑一遍，SMH.reader 临时换成只记下参数的（返回什么都不做的对象，网页不会重新初始化）
  function manhuaguiData() {
    if (!(window.SMH && typeof SMH.reader === 'function')) return null;
    const s = [...document.scripts].map((x) => x.textContent || '').find((t) => t.length < 300000 && /p,a,c,k,e,d/.test(t));
    if (!s) return null;
    let d = null;
    const orig = SMH.reader;
    const noop = new Proxy(function () {}, { get: () => noop, apply: () => noop });
    SMH.reader = (x) => { d = x; return noop; };
    try { (0, eval)(s); } catch (e) {} finally { SMH.reader = orig; }
    return d && Array.isArray(d.images) && d.images.length ? d : null;
  }

  function siteManga() {
    if (window !== window.top) return null;
    if (siteResult) return siteResult;
    if (siteData === undefined) {
      try { siteData = manhuaguiData(); } catch (e) { siteData = null; }
    }
    const d = siteData;
    if (!d) return null;
    // 图片服务器（有好几个）按网页上正在显示的那张定
    const set = new Set(d.images);
    const shown = [...document.images].find((i) => {
      try { return set.has(decodeURIComponent(new URL(i.currentSrc || i.src).pathname)); } catch (e) { return false; }
    });
    if (!shown) return null;
    const origin = new URL(shown.currentSrc || shown.src).origin;
    const q = d.sl ? '?e=' + d.sl.e + '&m=' + d.sl.m : '';
    const dir = location.pathname.replace(/[^/]*$/, '');
    const chapter = (id) => (id ? new URL(dir + id + '.html', location.href).href : '');
    siteResult = {
      pages: d.images.map((p) => origin + encodeURI(p) + q),
      next: chapter(d.nextId),
      prev: chapter(d.prevId),
      toc: new URL(dir, location.href).href,
    };
    return siteResult;
  }

  const NEXT = /^\s*(下一[话話章回节節集卷页頁]|下[一]?章|下一篇|next\s*(chapter|episode|›|»|>)?|›|»)\s*[>›»]?\s*$/i;
  const PREV = /^\s*[<‹«]?\s*(上一[话話章回节節集卷]|上[一]?章|上一篇|prev(ious)?\s*(chapter|episode)?|‹|«)\s*$/i;
  const TOC = /^\s*(目录|目錄|章节目录|章節目錄|章节列表|返回目录|返回目錄|全部章节|漫画首页|漫畫首頁|详情|詳情|table of contents|chapters?)\s*$/i;

  function link(re) {
    for (const a of document.querySelectorAll('a[href]')) {
      const t = (a.textContent || a.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
      if (!t || t.length > 20 || !re.test(t)) continue;
      const href = a.getAttribute('href') || '';
      if (!href || /^(javascript:|#)/i.test(href)) continue;
      const u = abs(href);
      if (!/^https?:/.test(u) || u === location.href) continue;
      return u;
    }
    return '';
  }

  function scan() {
    timer = 0;
    let pages = [];
    try { pages = scanImages(); } catch (e) {}
    const top = window === window.top;
    let site = null;
    try { site = siteManga(); } catch (e) {}
    if (site && site.pages.length > pages.length) pages = site.pages;
    const info = {
      frame: location.href,
      top,
      title: top ? document.title : '',
      pages,
      next: (site && site.next) || (top || pages.length ? link(NEXT) : ''),
      prev: (site && site.prev) || (top || pages.length ? link(PREV) : ''),
      toc: (site && site.toc) || (top ? link(TOC) : ''),
    };
    const s = JSON.stringify(info);
    if (s === last) return;
    last = s;
    try { VoxBridge.mangaFound(s); } catch (e) {}
  }

  function later(ms) {
    if (timer) return;
    timer = setTimeout(scan, ms);
  }

  // 网页加载、懒加载新图片时重新看一遍（最多 1 秒一次）
  const obs = new MutationObserver(() => later(1000));
  function start() {
    try { obs.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'data-src'] }); } catch (e) {}
    later(600);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
  addEventListener('load', () => later(300));

  // ---- 交出网页里解密出来的图（App 的阅读模式要的时候调） ----
  // 一张一张来：还没加载的要滚过去等网页加载、解密，几张同时滚会互相抢
  const queue = [];
  let busy = false;

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  async function grab(index, frame) {
    if (frame && frame !== location.href) return '';
    const img = slots[index];
    if (!img || !img.isConnected) return '';
    const ready = () => {
      const s = img.currentSrc || img.src || '';
      return /^blob:/.test(s) && img.complete && img.naturalWidth > 2 ? s : '';
    };
    let src = ready();
    if (!src) {
      // 滚过去，网页看到它才加载；最多等 20 秒
      try { img.scrollIntoView({ block: 'center' }); } catch (e) {}
      for (let k = 0; k < 100 && !(src = ready()); k++) await sleep(200);
    }
    if (!src) return '';
    try {
      const blob = await (await fetch(src)).blob();
      return await new Promise((resolve, reject) => {
        const fr = new FileReader();
        fr.onload = () => resolve(String(fr.result).replace(/^data:[^,]*,/, ''));
        fr.onerror = () => reject(fr.error);
        fr.readAsDataURL(blob);
      });
    } catch (e) {
      // 网页显示完就把 blob 地址作废了（revokeObjectURL）：图还在页面上，按原尺寸画下来
      const c = document.createElement('canvas');
      c.width = img.naturalWidth;
      c.height = img.naturalHeight;
      c.getContext('2d').drawImage(img, 0, 0);
      return c.toDataURL('image/jpeg', 0.95).replace(/^data:[^,]*,/, '');
    }
  }

  async function pump() {
    if (busy) return;
    busy = true;
    while (queue.length) {
      const job = queue.shift();
      let data = '';
      try { data = await grab(job.index, job.frame); } catch (e) {}
      try { VoxBridge.mangaImage(job.id, data); } catch (e) {}
    }
    busy = false;
  }

  function exportImage(index, id, frame) {
    queue.push({ index, id, frame });
    pump();
  }

  window.__voxManga = { scan, exportImage };
})();
