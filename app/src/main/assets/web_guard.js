// 浮动广告：拦截广告开着时，
// 1. 点到贴边浮动层（position: fixed / sticky）里指向别的网站的链接、或者第三方 iframe 里指向别的网站的链接，不跳转，交给 App 提示（可以「仍然打开」）；
// 2. 用户在点选屏蔽里选了「屏蔽本站浮动广告」的网站：贴着屏幕上下边的大块浮动层（带图片、iframe）一律藏起来；
//    一张图拆成几十个名字随机的 fixed 小块（每块单独都不大）的，按贴着的边合起来认，连同上面盖着的看不见的细条一起藏。
(function () {
  'use strict';
  if (/(^|\.)challenges\.cloudflare\.com$/i.test(location.hostname) || /^\/cdn-cgi\/challenge-platform\b/i.test(location.pathname)) return;
  if (window.__voxFloat) return;
  var guard = false;
  try { guard = !!(window.VoxBridge && VoxBridge.adGuard()); } catch (e) {}
  if (!guard) { window.__voxFloat = { enable: function () {} }; return; }

  function closest(el, sel) {
    if (el && typeof el.closest === 'function') return el.closest(sel);
    for (var cur = el; cur && cur.nodeType === 1; cur = cur.parentElement) {
      var fn = cur.matches || cur.webkitMatchesSelector || cur.msMatchesSelector;
      if (fn && fn.call(cur, sel)) return cur;
    }
    return null;
  }

  function cssEscape(s) {
    if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(s);
    return String(s).replace(/([^\w-])/g, '\\$1').replace(/^(\d)/, '\\3$1 ');
  }

  function site(h) {
    var p = (h || '').toLowerCase().split('.');
    if (p.length <= 2) return p.join('.');
    var sl = ['com', 'net', 'org', 'gov', 'edu', 'co', 'ac', 'or', 'ne', 'go'];
    return (sl.indexOf(p[p.length - 2]) >= 0 ? p.slice(-3) : p.slice(-2)).join('.');
  }
  var me = site(location.hostname);
  // 顶层网页的网站（iframe 里拿不到顶层地址时用 ancestorOrigins）
  var topSite = me;
  try { topSite = site(window.top.location.hostname); } catch (e) {
    try {
      var o = location.ancestorOrigins;
      if (o && o.length) topSite = site(new URL(o[o.length - 1]).hostname);
    } catch (e2) {}
  }
  var inFrame = window !== window.top;
  var thirdPartyFrame = inFrame && me !== topSite;

  function floatingAncestor(el) {
    for (var e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      var p = getComputedStyle(e).position;
      if (p === 'fixed' || p === 'sticky') return e;
    }
    return null;
  }

  // ---- 1. 浮动广告里的外站链接 ----
  function onClick(ev) {
    // 点选屏蔽开着：点击交给点选
    if (window.__voxPicking) return;
    var a = ev.target && closest(ev.target, 'a[href]');
    if (!a) return;
    var url;
    try { url = new URL(a.getAttribute('href'), location.href); } catch (e) { return; }
    if (!/^https?:$/.test(url.protocol)) return;
    var s = site(url.hostname);
    if (s === me || s === topSite) return;
    // 浮动层里的外站链接，或者第三方 iframe（广告框）里的外站链接
    if (!thirdPartyFrame && !floatingAncestor(a)) return;
    ev.preventDefault();
    ev.stopImmediatePropagation();
    try { VoxBridge.adLinkBlocked(url.href); } catch (e) {}
  }
  addEventListener('click', onClick, true);

  // ---- 2. 藏起贴边的浮动广告 ----
  var on = false;
  try { on = !!VoxBridge.floatAds(location.hostname); } catch (e) {}
  var timer = 0;

  // 像浮动广告：贴着上下边、够宽、不太高，里面有图片 / iframe / 视频 / 背景图；不是导航栏（没有输入框，本站链接不多）
  function isFloatAd(e) {
    var r = e.getBoundingClientRect();
    var vw = innerWidth, vh = innerHeight;
    if (r.width < vw * 0.5 || r.height < 24 || r.height > vh * 0.5) return false;
    var atEdge = (r.bottom >= vh - 8 && r.top > vh * 0.4) || (r.top <= 8 && r.bottom < vh * 0.6);
    if (!atEdge) return false;
    if (e.querySelector('input, textarea, select, video[controls]')) return false;
    var links = e.querySelectorAll('a[href]');
    var own = 0;
    for (var li = 0; li < links.length; li++) {
      try { if (site(new URL(links[li].getAttribute('href'), location.href).hostname) === me) own++; } catch (x) {}
    }
    if (own >= 3) return false;
    if (e.tagName === 'IFRAME' || e.tagName === 'IMG' || e.tagName === 'CANVAS') return true;
    if (e.querySelector('img, iframe, canvas, video, svg image')) return true;
    // 拆成小块的图片广告：一堆带背景图的小格子
    var bg = 0;
    var children = e.querySelectorAll('*');
    for (var ci = 0; ci < children.length; ci++) {
      var c = children[ci];
      if (getComputedStyle(c).backgroundImage !== 'none' && ++bg >= 2) return true;
      if (bg === 0 && c.childElementCount === 0 && c.textContent.trim().length > 40) break;
    }
    return getComputedStyle(e).backgroundImage !== 'none';
  }

  function hide(e) {
    e.__voxHidden = true;
    e.style.setProperty('display', 'none', 'important');
  }

  function isTile(e, r) {
    if (r.width <= 0 || r.height <= 0 || r.width >= innerWidth * 0.5 || r.height > innerHeight * 0.3) return false;
    if (e.childElementCount > 0 || (e.textContent || '').trim()) return false;
    return true;
  }

  function hideTiles(tiles) {
    var edges = ['top', 'bottom'];
    for (var ei = 0; ei < edges.length; ei++) {
      var edge = edges[ei];
      var group = [];
      for (var ti = 0; ti < tiles.length; ti++) {
        if (tiles[ti].edge === edge) group.push(tiles[ti]);
      }
      if (group.length < 3) continue;
      var xs = group.map(function (t) {
        return [Math.max(0, t.r.left), Math.min(innerWidth, t.r.right)];
      }).sort(function (a, b) {
        return a[0] - b[0];
      });
      var covered = 0, s = -1, en = -1;
      for (var xi = 0; xi < xs.length; xi++) {
        var a = xs[xi][0], b = xs[xi][1];
        if (a > en) { if (en > s) covered += en - s; s = a; en = b; } else if (b > en) en = b;
      }
      if (en > s) covered += en - s;
      if (covered < innerWidth * 0.5) continue;
      var bgCount = 0;
      for (var gi = 0; gi < group.length; gi++) {
        if (group[gi].bg) bgCount++;
      }
      if (bgCount < 6) continue;
      for (var hi = 0; hi < group.length; hi++) {
        hide(group[hi].e);
        rememberTile(group[hi].e);
      }
    }
  }

  var tileRuleMap = Object.create(null);
  var tileRuleList = [];
  var tileSheet = null;
  function rememberTile(e) {
    var changed = false;
    function add(sel) {
      if (sel && !tileRuleMap[sel]) {
        tileRuleMap[sel] = true;
        tileRuleList.push(sel);
        changed = true;
      }
    }
    if (e.id) add('#' + cssEscape(e.id));
    if (e.classList.length === 1) add('.' + cssEscape(e.classList[0]));
    if (!changed) return;
    try {
      if (!tileSheet) {
        tileSheet = document.createElement('style');
        (document.head || document.documentElement).appendChild(tileSheet);
      }
      tileSheet.textContent = tileRuleList.join(',') + '{display:none!important}';
    } catch (x) {}
  }

  function sweep() {
    timer = 0;
    if (!on || !document.body) return;
    var all = document.documentElement.querySelectorAll('body *, body ~ *');
    var n = Math.min(all.length, 6000);
    var vh = innerHeight;
    var tiles = [];
    for (var i = 0; i < n; i++) {
      var e = all[i];
      if (e.__voxHidden) continue;
      var st = getComputedStyle(e);
      var p = st.position;
      if (p !== 'fixed' && p !== 'sticky') continue;
      if (isFloatAd(e)) { hide(e); continue; }
      var r = e.getBoundingClientRect();
      if (!isTile(e, r)) continue;
      var edge = r.top > vh * 0.5 ? 'bottom' : r.bottom < vh * 0.5 ? 'top' : null;
      if (edge) tiles.push({ e: e, r: r, edge: edge, bg: st.backgroundImage !== 'none' });
    }
    if (tiles.length) hideTiles(tiles);
  }

  function later(ms) { if (!timer) timer = setTimeout(sweep, ms); }

  function start() {
    if (!on) return;
    sweep();
    try {
      new MutationObserver(function () { later(tileRuleList.length ? 100 : 800); })
        .observe(document.documentElement, { childList: true, subtree: true });
    } catch (e) {}
    var k = 0;
    var iv = setInterval(function () { sweep(); if (++k > 20) clearInterval(iv); }, 1500);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();

  window.__voxFloat = {
    enable: function () { if (on) { sweep(); return; } on = true; start(); },
  };
})();
