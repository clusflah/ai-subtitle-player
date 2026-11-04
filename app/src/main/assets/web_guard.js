// 浮动广告：拦截广告开着时，
// 1. 点到贴边浮动层（position: fixed / sticky）里指向别的网站的链接、或者第三方 iframe 里指向别的网站的链接，不跳转，交给 App 提示（可以「仍然打开」）；
// 2. 用户在点选屏蔽里选了「屏蔽本站浮动广告」的网站：贴着屏幕上下边的大块浮动层（带图片、iframe）一律藏起来
//    （这类广告常把一张图拆成几十个名字随机的小块，用选择器屏蔽不住）。
(() => {
  if (window.__voxFloat) return;
  let guard = false;
  try { guard = !!(window.VoxBridge && VoxBridge.adGuard()); } catch (e) {}
  if (!guard) { window.__voxFloat = { enable() {} }; return; }

  const site = (h) => {
    const p = (h || '').toLowerCase().split('.');
    if (p.length <= 2) return p.join('.');
    const sl = ['com', 'net', 'org', 'gov', 'edu', 'co', 'ac', 'or', 'ne', 'go'];
    return (sl.indexOf(p[p.length - 2]) >= 0 ? p.slice(-3) : p.slice(-2)).join('.');
  };
  const me = site(location.hostname);
  // 顶层网页的网站（iframe 里拿不到顶层地址时用 ancestorOrigins）
  let top = me;
  try { top = site(window.top.location.hostname); } catch (e) {
    try {
      const o = location.ancestorOrigins;
      if (o && o.length) top = site(new URL(o[o.length - 1]).hostname);
    } catch (e2) {}
  }
  const inFrame = window !== window.top;
  const thirdPartyFrame = inFrame && me !== top;

  function floatingAncestor(el) {
    for (let e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      const p = getComputedStyle(e).position;
      if (p === 'fixed' || p === 'sticky') return e;
    }
    return null;
  }

  // ---- 1. 浮动广告里的外站链接 ----
  function onClick(ev) {
    // 点选屏蔽开着：点击交给点选
    if (window.__voxPicking) return;
    const a = ev.target && ev.target.closest && ev.target.closest('a[href]');
    if (!a) return;
    let url;
    try { url = new URL(a.getAttribute('href'), location.href); } catch (e) { return; }
    if (!/^https?:$/.test(url.protocol)) return;
    const s = site(url.hostname);
    if (s === me || s === top) return;
    // 浮动层里的外站链接，或者第三方 iframe（广告框）里的外站链接
    if (!thirdPartyFrame && !floatingAncestor(a)) return;
    ev.preventDefault();
    ev.stopImmediatePropagation();
    try { VoxBridge.adLinkBlocked(url.href); } catch (e) {}
  }
  addEventListener('click', onClick, true);

  // ---- 2. 藏起贴边的浮动广告 ----
  let on = false;
  try { on = !!VoxBridge.floatAds(location.hostname); } catch (e) {}
  let timer = 0;

  // 像浮动广告：贴着上下边、够宽、不太高，里面有图片 / iframe / 视频 / 背景图；不是导航栏（没有输入框，本站链接不多）
  function isFloatAd(e) {
    const r = e.getBoundingClientRect();
    const vw = innerWidth, vh = innerHeight;
    if (r.width < vw * 0.5 || r.height < 24 || r.height > vh * 0.5) return false;
    const atEdge = r.bottom >= vh - 8 && r.top > vh * 0.4 || r.top <= 8 && r.bottom < vh * 0.6;
    if (!atEdge) return false;
    if (e.querySelector('input, textarea, select, video[controls]')) return false;
    let own = 0;
    for (const a of e.querySelectorAll('a[href]')) {
      try { if (site(new URL(a.getAttribute('href'), location.href).hostname) === me) own++; } catch (x) {}
    }
    if (own >= 3) return false;
    if (e.tagName === 'IFRAME' || e.tagName === 'IMG' || e.tagName === 'CANVAS') return true;
    if (e.querySelector('img, iframe, canvas, video, svg image')) return true;
    // 拆成小块的图片广告：一堆带背景图的小格子
    let bg = 0;
    for (const c of e.querySelectorAll('*')) {
      if (getComputedStyle(c).backgroundImage !== 'none' && ++bg >= 2) return true;
      if (bg === 0 && c.childElementCount === 0 && c.textContent.trim().length > 40) break;
    }
    return getComputedStyle(e).backgroundImage !== 'none';
  }

  function sweep() {
    timer = 0;
    if (!on || !document.body) return;
    // 只看 body 下前几层和所有 fixed 元素：广告脚本一般把浮层挂在 body 上
    const all = document.body.querySelectorAll('*');
    const n = Math.min(all.length, 6000);
    for (let i = 0; i < n; i++) {
      const e = all[i];
      if (e.__voxHidden) continue;
      const p = getComputedStyle(e).position;
      if (p !== 'fixed' && p !== 'sticky') continue;
      if (isFloatAd(e)) {
        e.__voxHidden = true;
        e.style.setProperty('display', 'none', 'important');
      }
    }
  }

  function later(ms) { if (!timer) timer = setTimeout(sweep, ms); }

  function start() {
    if (!on) return;
    sweep();
    try { new MutationObserver(() => later(800)).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
    // 有的广告过一会儿才出来、或者先藏着再显示
    let k = 0;
    const iv = setInterval(() => { sweep(); if (++k > 20) clearInterval(iv); }, 1500);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();

  window.__voxFloat = {
    /** 点选屏蔽里选了「屏蔽本站浮动广告」：马上生效 */
    enable() { if (on) { sweep(); return; } on = true; start(); },
  };
})();
