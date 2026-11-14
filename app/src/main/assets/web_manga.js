// 漫画识别：找出网页上连续的漫画图片（一个容器里一串宽大的竖图），
// 连同上一话、下一话、目录链接报告给 App，App 显示「漫画阅读模式」入口。
// 每个框架都跑（有的网站把漫画放在另一个域名的 iframe 里），各自报告，App 选图最多的那个。
(function () {
  'use strict';
  if (window.__voxManga) return;
  var last = '';
  var timer = 0;

  function isConnected(n) {
    return !!n && (n.isConnected !== undefined ? n.isConnected : !!(document.documentElement && document.documentElement.contains(n)));
  }

  function toArray(list) {
    return Array.prototype.slice.call(list || []);
  }

  function closest(el, sel) {
    if (el && typeof el.closest === 'function') return el.closest(sel);
    for (var cur = el; cur && cur.nodeType === 1; cur = cur.parentElement) {
      var fn = cur.matches || cur.webkitMatchesSelector || cur.msMatchesSelector;
      if (fn && fn.call(cur, sel)) return cur;
    }
    return null;
  }

  // 懒加载的图片真正的地址常放在这些属性里
  var LAZY = ['data-src', 'data-original', 'data-lazy-src', 'data-lazy', 'data-echo', 'data-url', 'data-img',
    'data-original-src', 'data-actualsrc', 'data-kksrc', 'data-srcset', 'file', '_src'];

  function abs(u) {
    try { return new URL(u, location.href).href; } catch (e) { return ''; }
  }

  function bestSrcset(v) {
    // "a.jpg 1x, b.jpg 2x" → 取最后一个（最大的）
    var parts = v.split(',').map(function (s) {
      return s.trim().split(/\s+/)[0];
    }).filter(Boolean);
    return parts[parts.length - 1] || '';
  }

  function srcOf(img) {
    for (var li = 0; li < LAZY.length; li++) {
      var a = LAZY[li];
      var v = img.getAttribute(a);
      if (v && !/^data:/.test(v) && !/^\s*$/.test(v)) return abs(a.indexOf('srcset') >= 0 ? bestSrcset(v) : v.trim());
    }
    var set = img.getAttribute('srcset');
    if (set) { var s = bestSrcset(set); if (s && !/^data:/.test(s)) return abs(s); }
    var cur = img.currentSrc || img.src || '';
    if (!cur || /^(data|blob|about):/.test(cur)) return '';
    return cur;
  }

  var host = location.hostname.split('.').slice(-2).join('.');

  // 像漫画页的图：在网页上够宽（或原图够大），不是横幅、图标；不在指向别的网站的链接里（那是广告）
  function candidate(img) {
    var src = srcOf(img);
    if (!src || !/^https?:/.test(src)) return null;
    if (/\.(svg|gif)(\?|$)/i.test(src) && !img.getAttribute('data-src')) return null;
    var r = img.getBoundingClientRect();
    var vw = Math.max(document.documentElement.clientWidth || 0, innerWidth || 0) || 1;
    var nw = img.naturalWidth, nh = img.naturalHeight;
    var loaded = nw > 2 && nh > 2;
    var wideOnPage = r.width >= Math.min(vw * 0.45, 320);
    var bigNatural = loaded && nw >= 400 && nh >= 400;
    if (!wideOnPage && !bigNatural) return null;
    // 已加载的：太扁的是横幅广告
    if (loaded && nh / nw < 0.55) return null;
    if (!loaded && r.width > 0 && r.height > 0 && r.height / r.width < 0.3 && r.height > 20) return null;
    var a = closest(img, 'a[href]');
    if (a) {
      var h = (a.hostname || '').split('.').slice(-2).join('.');
      if (h && h !== host) return null;
    }
    if (getComputedStyle(img).display === 'none') return null;
    return src;
  }

  // 网页自己解密出来的图（blob: 地址，App 下载不了）和还没加载出来的占位图：位置像漫画页（够宽）就算一个位置。
  // 只在顶层网页认（App 只能请顶层网页交出图片）
  function blobSlot(img) {
    if (window !== window.top) return '';
    var cur = img.currentSrc || img.getAttribute('src') || '';
    var blob = /^blob:/.test(cur);
    if (!blob && cur && !/^data:/.test(cur)) return '';
    if (srcOf(img)) return '';
    var r = img.getBoundingClientRect();
    var vw = Math.max(document.documentElement.clientWidth || 0, innerWidth || 0) || 1;
    if (r.width < Math.min(vw * 0.45, 320)) return '';
    if (getComputedStyle(img).display === 'none') return '';
    var a = closest(img, 'a[href]');
    if (a) {
      var h = (a.hostname || '').split('.').slice(-2).join('.');
      if (h && h !== host) return '';
    }
    return blob ? 'blob' : 'empty';
  }

  /** 解密图那种网页上的漫画图片位置（按网页上的顺序）；exportImage 按序号找 */
  var slots = [];

  /** 按共同的容器分组（往上找 4 层），返回图最多的那个容器和图数 */
  function bestContainer(list) {
    var counts = new Map();
    for (var i = 0; i < list.length; i++) {
      var e = list[i].img.parentElement;
      for (var d = 0; e && d < 4; d++, e = e.parentElement) {
        counts.set(e, (counts.get(e) || 0) + 1);
        if (e === document.body) break;
      }
    }
    var best = null, n = 0;
    counts.forEach(function (k, el) {
      // 一样多时取更深（更小）的容器
      if (k > n || (k === n && best && best.contains(el))) { best = el; n = k; }
    });
    return { el: best, count: n };
  }

  function scanImages() {
    var imgs = toArray(document.images);
    var cands = [];
    var blobs = [];
    for (var i = 0; i < imgs.length; i++) {
      var img = imgs[i];
      var src = candidate(img);
      if (src) { cands.push({ img: img, src: src }); continue; }
      var kind = blobSlot(img);
      if (kind) blobs.push({ img: img, src: '', kind: kind });
    }
    var hasBlob = false;
    for (var bi = 0; bi < blobs.length; bi++) {
      if (blobs[bi].kind === 'blob') { hasBlob = true; break; }
    }
    // 有解密出来的图：连同普通图、占位图一起看
    if (hasBlob) {
      var all = cands.concat(blobs);
      var resBlob = bestContainer(all);
      var bestB = resBlob.el;
      if (bestB && resBlob.count >= 3) {
        var blobInsideBest = false;
        for (var j = 0; j < blobs.length; j++) {
          if (blobs[j].kind === 'blob' && bestB.contains(blobs[j].img)) { blobInsideBest = true; break; }
        }
        if (blobInsideBest) {
          var inBest = new Set();
          for (var ai = 0; ai < all.length; ai++) {
            if (bestB.contains(all[ai].img)) inBest.add(all[ai].img);
          }
          slots = imgs.filter(function (item) { return inBest.has(item); });
          var bySrc = new Map();
          for (var ci = 0; ci < cands.length; ci++) bySrc.set(cands[ci].img, cands[ci].src);
          return slots.map(function (item, idx) { return bySrc.get(item) || 'voxblob:' + idx; });
        }
      }
    }
    slots = [];
    if (cands.length < 3) return [];
    // 取图最多的那组：头像、封面推荐、广告图分散在别处
    var res = bestContainer(cands);
    var best = res.el;
    if (!best || res.count < 3) return [];
    var seen = Object.create(null);
    var pages = [];
    for (var k = 0; k < cands.length; k++) {
      var c = cands[k];
      if (!best.contains(c.img) || seen[c.src]) continue;
      seen[c.src] = true;
      pages.push(c.src);
    }
    return pages;
  }

  // ---- 已知网站：整话的图片地址或上下话信息在网页脚本数据里（翻页式或单页应用） ----
  var smhHref = '';
  var smhData;
  var dm5Cid = 0;
  var dm5Pages = null;
  var dm5Fetching = false;
  var copyRetries = 0;

  function encodePath(p) {
    return encodeURI(p).replace(/%25([0-9A-Fa-f]{2})/g, '%$1').replace(/#/g, '%23');
  }

  function normPath(p) {
    try { return decodeURIComponent(p); } catch (e) { return p; }
  }

  function makeNoop() {
    var fn = function () { return fn; };
    if (typeof Proxy === 'function') {
      return new Proxy(fn, {
        get: function () { return fn; },
        apply: function () { return fn; }
      });
    }
    var names = ['init', 'preLoad', 'load', 'render', 'bind', 'go', 'next', 'prev', 'set', 'get', 'ready', 'done'];
    for (var ni = 0; ni < names.length; ni++) fn[names[ni]] = fn;
    return fn;
  }

  // 看漫画（manhuagui）：手机版调 SMH.reader({images, sl, nextId, prevId})，电脑版调 SMH.imgData({path, files, sl, nextId, prevId})。
  // 把这段脚本再跑一遍，临时换成只记下参数的（返回什么都不做的对象，网页不会重新初始化）
  function manhuaguiData() {
    if (!window.SMH || (typeof SMH.reader !== 'function' && typeof SMH.imgData !== 'function')) return null;
    var s = null;
    for (var i = 0; i < document.scripts.length; i++) {
      var t = document.scripts[i].textContent || '';
      if (t.length < 300000 && /p,a,c,k,e,d/.test(t)) { s = t; break; }
    }
    if (!s) return null;
    var d = null;
    var origReader = SMH.reader;
    var origImgData = SMH.imgData;
    var noop = makeNoop();
    SMH.reader = function (x) { d = x; return noop; };
    SMH.imgData = function (x) { d = x; return noop; };
    try { (0, eval)(s); } catch (e) {} finally {
      SMH.reader = origReader;
      SMH.imgData = origImgData;
    }
    if (d && !Array.isArray(d.images) && Array.isArray(d.files)) {
      var basePath = d.path || '';
      d.images = d.files.map(function (f) { return basePath + f; });
    }
    return d && Array.isArray(d.images) && d.images.length ? d : null;
  }

  function manhuaguiManga() {
    var baseHref = location.href.split('#')[0];
    if (smhHref !== baseHref) {
      smhHref = baseHref;
      smhData = null;
    }
    if (!smhData) {
      try { smhData = manhuaguiData(); } catch (e) { smhData = null; }
    }
    var d = smhData;
    if (!d) return null;
    // 图片服务器按网页上正在显示的那张定（d.images 里常混着未编码中文与已编码的 %20/%E6，两边都解码后再比对）
    var set = Object.create(null);
    for (var i = 0; i < d.images.length; i++) set[normPath(d.images[i])] = true;
    var shown = null;
    for (var j = 0; j < document.images.length; j++) {
      var img = document.images[j];
      try {
        if (set[normPath(new URL(img.currentSrc || img.src).pathname)]) { shown = img; break; }
      } catch (e) {}
    }
    var origin = shown ? new URL(shown.currentSrc || shown.src).origin : 'https://i.hamreus.com';
    var q = d.sl ? '?e=' + d.sl.e + '&m=' + d.sl.m : '';
    var dir = location.pathname.replace(/[^/]*$/, '');
    function chapter(id) { return id ? new URL(dir + id + '.html', location.href).href : ''; }
    var bname = d.bname || d.bookName || '';
    var cname = d.cname || d.chapterTitle || '';
    return {
      pages: d.images.map(function (p) { return origin + encodePath(p) + q; }),
      next: chapter(d.nextId),
      prev: chapter(d.prevId),
      toc: new URL(dir, location.href).href,
      title: [bname, cname].filter(Boolean).join(' '),
    };
  }

  // 动漫屋（dm5.com / m.dm5.com）：手机版整话签名地址在 window.newImgs，桌面翻页版通过 chapterfun.ashx 按页获取
  function fetchDm5(cid, count) {
    if (dm5Fetching) return;
    dm5Fetching = true;
    var mid = window.DM5_MID || '';
    var dt = window.DM5_VIEWSIGN_DT || '';
    var sign = window.DM5_VIEWSIGN || '';
    var keyEl = document.getElementById('dm5_key');
    var key = (keyEl && keyEl.value) || '';
    var base = location.pathname.replace(/\/?$/, '/');
    var out = [];
    var seen = Object.create(null);
    var limit = Math.min(count, 150);

    function finish() {
      dm5Fetching = false;
      if (out.length && Number(window.DM5_CID) === cid) {
        dm5Cid = cid;
        dm5Pages = out;
        later(50);
      }
    }

    function nextPage(page) {
      if (page > limit || out.length >= count) {
        finish();
        return;
      }
      var u = base + 'chapterfun.ashx?cid=' + encodeURIComponent(cid) +
        '&page=' + page + '&key=' + encodeURIComponent(key) +
        '&language=1&gtk=6&_cid=' + encodeURIComponent(cid) +
        '&_mid=' + encodeURIComponent(mid) +
        '&_dt=' + encodeURIComponent(dt) +
        '&_sign=' + encodeURIComponent(sign);
      fetch(u, {
        credentials: 'include',
        headers: { 'X-Requested-With': 'XMLHttpRequest' },
      }).then(function (r) {
        if (!r.ok) throw new Error('status');
        return r.text();
      }).then(function (js) {
        if (!js) { finish(); return; }
        var arr = null;
        try { arr = (0, eval)(js); } catch (e) {}
        if (!Array.isArray(arr) || !arr.length) { finish(); return; }
        var added = 0;
        for (var i = 0; i < arr.length; i++) {
          var full = abs(String(arr[i] || ''));
          if (full && !seen[full]) {
            seen[full] = true;
            out.push(full);
            added++;
          }
        }
        if (!added) { finish(); return; }
        nextPage(page + added);
      }).catch(function () {
        finish();
      });
    }

    nextPage(1);
  }

  function dm5Data() {
    if (Array.isArray(window.newImgs) && window.newImgs.length) {
      var pages = window.newImgs.map(function (u) { return abs(String(u || '')); }).filter(function (u) { return /^https?:/.test(u); });
      if (pages.length) return { pages: pages };
    }
    var cid = Number(window.DM5_CID || 0);
    var count = Number(window.DM5_IMAGE_COUNT || 0);
    if (cid > 0 && count > 0) {
      if (dm5Cid === cid && dm5Pages && dm5Pages.length) return { pages: dm5Pages };
      fetchDm5(cid, count);
    }
    return null;
  }

  // 拷贝漫画 H5（mangacopy.com 等 Vue 单页应用）：上下话按钮不是 <a> 链接，章节图片和 prev/next UUID 在 Vue 组件 content.chapter 里
  function mangacopyData() {
    var app = document.getElementById('app');
    var root = app && app.__vue__;
    if (!root) {
      if (/\/comicContent\//i.test(location.pathname) && copyRetries++ < 20) later(500);
      return null;
    }
    var comp = null;
    (function walk(v, d) {
      if (!v || comp || d > 10) return;
      var c = v.$data && v.$data.content;
      if (c && c.chapter && Array.isArray(c.chapter.contents)) { comp = v; return; }
      var children = v.$children || [];
      for (var i = 0; i < children.length; i++) walk(children[i], d + 1);
    })(root, 0);
    if (!comp) {
      if (/\/comicContent\//i.test(location.pathname) && copyRetries++ < 20) later(500);
      return null;
    }
    var content = comp.$data.content || {};
    var ch = content.chapter || {};
    var comic = content.comic || {};
    var pages = (ch.contents || []).map(function (x) {
      return x && x.url ? abs(x.url) : '';
    }).filter(function (u) {
      return /^https?:/.test(u);
    });
    if (!pages.length) {
      if (/\/comicContent\//i.test(location.pathname) && copyRetries++ < 20) later(500);
      return null;
    }
    var pathWord = ch.comic_path_word || comic.path_word || (root.$route && root.$route.params && root.$route.params.pathWord) || '';
    var router = root.$router;
    function chapterUrl(uuid) {
      if (!uuid || !pathWord) return '';
      if (router && typeof router.resolve === 'function') {
        try {
          var r = router.resolve({ name: 'comicContent', params: { pathWord: pathWord, uuid: uuid } });
          if (r && r.href) return abs(r.href);
        } catch (e) {}
      }
      var m = location.pathname.match(/^(.*\/comicContent\/)[^/]+\/[^/]+/i);
      return abs((m ? m[1] : '/h5/comicContent/') + encodeURIComponent(pathWord) + '/' + encodeURIComponent(uuid));
    }
    var toc = '';
    if (pathWord) {
      if (router && typeof router.resolve === 'function') {
        try {
          var rt = router.resolve({ name: 'details', params: { type: 'comic', pathWord: pathWord } });
          if (rt && rt.href) toc = abs(rt.href);
        } catch (e) {}
      }
      if (!toc) {
        var prefix = /^\/h5\//i.test(location.pathname) ? '/h5' : '';
        toc = abs(prefix + '/details/comic/' + encodeURIComponent(pathWord));
      }
    }
    return {
      pages: pages,
      next: chapterUrl(ch.next),
      prev: chapterUrl(ch.prev),
      toc: toc,
      title: [comic.name, ch.name].filter(Boolean).join(' '),
    };
  }

  function siteManga() {
    if (window !== window.top) return null;
    return manhuaguiManga() || dm5Data() || mangacopyData();
  }

  var NEXT = /^\s*(下一[话話章回节節集卷页頁]|下[一]?章|下一篇|next\s*(chapter|episode|›|»|>)?|›|»)\s*[>›»]?\s*$/i;
  var PREV = /^\s*[<‹«]?\s*(上一[话話章回节節集卷]|上[一]?章|上一篇|prev(ious)?\s*(chapter|episode)?|‹|«)\s*$/i;
  var TOC = /^\s*(目录|目錄|章节目录|章節目錄|章节列表|返回目录|返回目錄|全部章节|漫画首页|漫畫首頁|详情|詳情|table of contents|chapters?)\s*$/i;

  function jsNavUrl(s) {
    if (!s) return '';
    var m = s.match(/(?:pushHistory|location\.(?:assign|replace)|window\.open)\s*\(\s*['"]([^'"]+)['"]/i) ||
      s.match(/(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]/i);
    if (!m) return '';
    var target = m[1].trim();
    if (!/^(\/|https?:\/\/|\.\.?\/)/i.test(target) && !/\.(html?|php|aspx?)([?#]|$)/i.test(target)) return '';
    return abs(target);
  }

  function link(re) {
    var anchors = document.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i];
      var t = (a.textContent || a.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
      if (!t || t.length > 20 || !re.test(t)) continue;
      var href = (a.getAttribute('href') || '').trim();
      if (!href || href.indexOf('#') === 0) continue;
      var u = /^javascript:/i.test(href)
        ? (jsNavUrl(href) || jsNavUrl(a.getAttribute('onclick') || ''))
        : abs(href);
      if (!/^https?:/.test(u) || u === location.href) continue;
      return u;
    }
    return '';
  }

  function scan() {
    timer = 0;
    var pages = [];
    try { pages = scanImages(); } catch (e) {}
    var isTop = window === window.top;
    var site = null;
    try { site = siteManga(); } catch (e) {}
    if (site && site.pages && site.pages.length && site.pages.length >= pages.length) pages = site.pages;
    var info = {
      frame: location.href,
      top: isTop,
      title: (site && site.title) || (isTop ? document.title : ''),
      pages: pages,
      next: (site && site.next) || (isTop || pages.length ? link(NEXT) : ''),
      prev: (site && site.prev) || (isTop || pages.length ? link(PREV) : ''),
      toc: (site && site.toc) || (isTop ? link(TOC) : ''),
    };
    var s = JSON.stringify(info);
    if (s === last) return;
    last = s;
    try { VoxBridge.mangaFound(s); } catch (e) {}
  }

  function later(ms) {
    if (timer) return;
    timer = setTimeout(scan, ms);
  }

  // 网页加载、懒加载新图片时重新看一遍（最多 1 秒一次）。
  // 只看和图片有关的变化：广告轮播、计时器之类整页不停在变，每次都重新量一遍所有图片，网页开久了会越来越卡
  function hasImg(n) {
    return n.nodeType === 1 && (n.tagName === 'IMG' || (n.firstElementChild && n.getElementsByTagName('img').length > 0));
  }
  function relevant(records) {
    for (var i = 0; i < records.length; i++) {
      var r = records[i];
      if (r.type === 'attributes') { if (r.target.tagName === 'IMG') return true; continue; }
      for (var ai = 0; ai < r.addedNodes.length; ai++) if (hasImg(r.addedNodes[ai])) return true;
      for (var ri = 0; ri < r.removedNodes.length; ri++) if (hasImg(r.removedNodes[ri])) return true;
    }
    return false;
  }
  // App 在用阅读模式看：网页在后面，少看几次
  var idle = false;
  try {
    var obs = new MutationObserver(function (records) { if (relevant(records)) later(idle ? 5000 : 1000); });
    function start() {
      try { obs.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'data-src'] }); } catch (e) {}
      later(600);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
  } catch (e) {}
  addEventListener('load', function () { later(300); });
  // 图片加载完尺寸才知道（DOM 没变）：也重新看
  document.addEventListener('load', function (e) { if (e.target && e.target.tagName === 'IMG') later(idle ? 5000 : 1000); }, true);

  // 单页应用切换章节（pushState / replaceState / popstate）：重新扫描新章节
  function routeKey() {
    return location.pathname + location.search + (location.hash.indexOf('#/') === 0 ? location.hash : '');
  }
  var lastRoute = routeKey();
  function onRouteChange() {
    var cur = routeKey();
    if (cur === lastRoute) return;
    lastRoute = cur;
    copyRetries = 0;
    later(350);
  }
  addEventListener('popstate', onRouteChange);
  addEventListener('hashchange', onRouteChange);
  var histKeys = ['pushState', 'replaceState'];
  for (var hi = 0; hi < histKeys.length; hi++) {
    (function (k) {
      var orig = history[k];
      if (typeof orig === 'function') {
        history[k] = function () {
          var r = orig.apply(this, arguments);
          onRouteChange();
          return r;
        };
      }
    })(histKeys[hi]);
  }

  // ---- 交出网页里解密出来的图（App 的阅读模式要的时候调） ----
  // 一张一张来：还没加载的要滚过去等网页加载、解密，几张同时滚会互相抢
  var queue = [];
  var busy = false;

  function sleep(ms) {
    return new Promise(function (r) { setTimeout(r, ms); });
  }

  function waitForReady(readyFn, retriesLeft) {
    var src = readyFn();
    if (src || retriesLeft <= 0) return Promise.resolve(src);
    return sleep(200).then(function () {
      return waitForReady(readyFn, retriesLeft - 1);
    });
  }

  function grab(index, frame) {
    if (frame && frame !== location.href) return Promise.resolve('');
    var img = slots[index];
    if (!img || !isConnected(img)) return Promise.resolve('');
    function ready() {
      var s = img.currentSrc || img.src || '';
      return /^blob:/.test(s) && img.complete && img.naturalWidth > 2 ? s : '';
    }
    var src = ready();
    var p;
    if (src) {
      p = Promise.resolve(src);
    } else {
      // 滚过去，网页看到它才加载；最多等 20 秒
      try { img.scrollIntoView({ block: 'center' }); } catch (e) {
        try { img.scrollIntoView(); } catch (e2) {}
      }
      p = waitForReady(ready, 100);
    }
    return p.then(function (readySrc) {
      if (!readySrc) return '';
      return fetch(readySrc).then(function (r) {
        return r.blob();
      }).then(function (blob) {
        return new Promise(function (resolve, reject) {
          var fr = new FileReader();
          fr.onload = function () { resolve(String(fr.result).replace(/^data:[^,]*,/, '')); };
          fr.onerror = function () { reject(fr.error); };
          fr.readAsDataURL(blob);
        });
      }).catch(function () {
        // 网页显示完就把 blob 地址作废了（revokeObjectURL）：图还在页面上，按原尺寸画下来
        var c = document.createElement('canvas');
        c.width = img.naturalWidth;
        c.height = img.naturalHeight;
        c.getContext('2d').drawImage(img, 0, 0);
        return c.toDataURL('image/jpeg', 0.95).replace(/^data:[^,]*,/, '');
      });
    });
  }

  function pump() {
    if (busy || !queue.length) return;
    busy = true;
    var job = queue.shift();
    Promise.resolve().then(function () {
      return grab(job.index, job.frame);
    }).then(function (data) {
      try { VoxBridge.mangaImage(job.id, data || ''); } catch (e) {}
    }, function () {
      try { VoxBridge.mangaImage(job.id, ''); } catch (e) {}
    }).then(function () {
      busy = false;
      if (queue.length) pump();
    });
  }

  function exportImage(index, id, frame) {
    queue.push({ index: index, id: id, frame: frame });
    pump();
  }

  window.__voxManga = {
    scan: scan,
    exportImage: exportImage,
    idle: function (v) { idle = !!v; if (!idle) later(300); },
  };
})();
