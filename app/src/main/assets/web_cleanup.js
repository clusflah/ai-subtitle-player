// 屏蔽网页上的「打开 App」「下载 App」推广：悬浮按钮、横幅、底部面板、全屏弹窗遮罩与 confirm 弹窗。
// 网页开头注入（每个框架一份）；白名单网盘（115、夸克、阿里等）及发现页跳转播放页面直接放行。
(function () {
  'use strict';
  if (/(^|\.)challenges\.cloudflare\.com$/i.test(location.hostname) || /^\/cdn-cgi\/challenge-platform\b/i.test(location.pathname)) return;
  if (window.__voxClean) return;
  window.__voxClean = true;

  function matches(el, sel) {
    var fn = el && (el.matches || el.webkitMatchesSelector || el.msMatchesSelector);
    return fn ? fn.call(el, sel) : false;
  }

  function closest(el, sel) {
    if (el && typeof el.closest === 'function') return el.closest(sel);
    for (var cur = el; cur && cur.nodeType === 1; cur = cur.parentElement) {
      if (matches(cur, sel)) return cur;
    }
    return null;
  }

  // ---- 拦截广告：AdGuard 规则里要藏起来的元素（没开拦截、规则还没下好时是空的） ----
  try {
    var ad = window.VoxBridge && VoxBridge.adCss(location.hostname);
    if (ad) {
      // 不往网页里加 <style> 节点：React / Next.js 的网页会比对页面结构，多一个节点就整页重新渲染
      if (document.adoptedStyleSheets !== undefined && window.CSSStyleSheet && CSSStyleSheet.prototype.replaceSync) {
        var sheet = new CSSStyleSheet();
        sheet.replaceSync(ad);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
      } else {
        var adStyle = document.createElement('style');
        adStyle.textContent = ad;
        (document.head || document.documentElement).appendChild(adStyle);
      }
    }
  } catch (e) {}

  try {
    if (window.VoxBridge) {
      if (typeof VoxBridge.shouldClean === 'function') {
        if (!VoxBridge.shouldClean(location.hostname)) return;
      } else if (!VoxBridge.cleanPage()) {
        return;
      }
    }
  } catch (e) {}

  // ---- 拦截 JS confirm("是否打开某某 App") 原生弹窗 ----
  var CONFIRM_RE = /(打开|下载|安装|跳转|前往|启动|拉起|唤起|使用)[\s\S]{0,28}(app|客户端|应用)|(app|客户端|应用)[\s\S]{0,28}(打开|下载|安装|观看|播放|查看|阅读|浏览|体验|操作)|即将离开[\s\S]{0,24}(打开|跳转|前往)|open\s+(in\s+)?(the\s+)?app|download\s+(the\s+)?app/i;
  try {
    var origConfirm = window.confirm;
    if (typeof origConfirm === 'function') {
      window.confirm = function (msg) {
        if (typeof msg === 'string' && msg.length <= 160 && CONFIRM_RE.test(msg)) return false;
        return origConfirm.apply(this, arguments);
      };
    }
  } catch (e) {}

  // ---- 已知网站：直接用样式藏起来，不会先闪一下 ----
  var SITES = [
    [/(^|\.)bilibili\.com$/, [
      'm-open-app.m-nav-openapp', 'm-open-app.fixed-openapp', 'm-open-app.m-video-main-launchapp',
      '.m-related-openapp', '.openapp-dialog', '.caution-dialog', '.gsl-buffer-app', '.m-home__no-more',
      '.v-card-toapp .open-app', '.launch-app-btn', '.home-float-openapp',
      '.bili-app-link-dialog', '.open-app-dialog', '.m-video-season-openapp', '.launch-app-bar',
    ]],
    [/(^|\.)zhihu\.com$/, [
      '.OpenInAppButton', '.MobileAppHeader-downloadLink', '.ContentItem-expandButton + .OpenInApp', '.OpenInAppModal',
    ]],
    [/(^|\.)douban\.com$/, ['.TalionNav-primary .download', '.Advertisement', '.oia-wrap', '.sub-app-download']],
    [/(^|\.)weibo\.(cn|com)$/, ['.m-tips-tp', '.open-app-btn', '.m-btn-openapp', '.woo-modal-wrap .open-app']],
    [/(^|\.)xiaohongshu\.com$/, ['.launch-app-container', '.bottom-bar-container .open-app', '.reds-modal-open-app']],
    [/(^|\.)baidu\.com$/, ['.nav-bar-bottom-open-app', '.tb-open-app', '.open-app-banner', '.callicon-wrap', '.wise-open-app', '.layer-open-app']],
    [/(^|\.)csdn\.net$/, ['.feed-Sign-span', '.btn_open_app_prompt_div', '.open_app_channelCode', '.weixin-shadowbox', '.wap-shadowbox']],
    [/(^|\.)jianshu\.com$/, ['.download-app-guidance', '.call-app-btn', '.guidance-wrap', '.open-in-app-modal']],
    [/(^|\.)(toutiao|ixigua)\.com$/, ['.download-banner', '.open-app-modal', '.launch-app-banner', '.banner-open-app']],
    [/(^|\.)iqiyi\.com$/, ['.m-iqylink-guide', '.m-iqylink-diversion', '.m-box-items-full .c-open-app', '[name="m-extendBar"]']],
    [/(^|\.)v\.qq\.com$/, ['.mod_promotion', '.open_app_bottom', '.at-app-banner', '.u_open_app']],
    [/(^|\.)youku\.com$/, ['.callEnd_box', '.clipboard-call-app', '.h5-detail-guide', '.guide-open-app']],
    [/(^|\.)mgtv\.com$/, ['.mg-app-guide', '.open-app-bar', '.m-open-app-pop']],
  ];
  var host = location.hostname;
  var rules = [];
  for (var si = 0; si < SITES.length; si++) {
    if (SITES[si][0].test(host)) {
      Array.prototype.push.apply(rules, SITES[si][1]);
    }
  }
  if (rules.length) {
    var cssText = rules.join(',\n') + ' { display: none !important; }';
    if (document.adoptedStyleSheets !== undefined && window.CSSStyleSheet && CSSStyleSheet.prototype.replaceSync) {
      try {
        var siteSheet = new CSSStyleSheet();
        siteSheet.replaceSync(cssText);
        document.adoptedStyleSheets = document.adoptedStyleSheets.concat([siteSheet]);
      } catch (e2) {
        var styleFallback = document.createElement('style');
        styleFallback.textContent = cssText;
        (document.head || document.documentElement).appendChild(styleFallback);
      }
    } else {
      var style = document.createElement('style');
      style.textContent = cssText;
      (document.head || document.documentElement).appendChild(style);
    }
  }

  // B 站：网页上的卡片、标题点了会去开 App（失败了再弹窗、往剪贴板里写东西）。
  // 视频卡片改成在网页里打开，其他的点了没反应
  addEventListener('click', function (e) {
    var m = e.target && closest(e.target, 'm-open-app');
    if (!m) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    var link = m.getAttribute('universallink');
    if (link && /^https?:/.test(link) && m.querySelector('img, picture')) location.href = link;
  }, true);

  // ---- 其他网站：按文字与弹窗结构识别 ----
  var WORD = /app|客户端|应用/i;
  // 「打开 App」「下载客户端」「App 内观看」这类按钮上的字
  var BUTTON = /(打开|下载|安装|前往|去|用|在|进入|启动|唤起|拉起|立即体验).{0,10}(app|客户端|应用)|(app|客户端|应用)\s*(内|中|里)?\s*(打开|观看|播放|查看|阅读|看|体验|浏览|使用|操作|看高清|看完整)|open\s+(in\s+)?(the\s+)?app|get\s+the\s+app|download\s+(the\s+)?app|continue\s+in\s+(the\s+)?app/i;
  // 悬浮横幅、底部抽屉、居中弹窗里的提示文字
  var PROMO = /(app|客户端|应用)[\s\S]*(下载|打开|安装|跳转|体验|观看|播放|查看|阅读|浏览|操作|使用|高清|完整版|流畅|立享|专享)|(下载|打开|安装|跳转|唤起|拉起|启动|前往)[\s\S]*(app|客户端|应用)|即将离开[\s\S]*(打开|客户端|app)|open\s+(in\s+)?(the\s+)?app|download\s+(the\s+)?app/i;
  var MAX_BUTTON = 28;
  var MAX_PANEL = 280;

  var hidden = new WeakSet();
  function hide(el) {
    if (!el || hidden.has(el)) return;
    hidden.add(el);
    el.style.setProperty('display', 'none', 'important');
  }
  function isVisible(el) {
    return el.offsetWidth > 0 || el.offsetHeight > 0;
  }

  /** 往上找悬浮（fixed、sticky）的那一层；若外层还有全屏模态容器，一并定位 */
  function floating(el) {
    var found = null;
    for (var e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      var p = getComputedStyle(e).position;
      if (p === 'fixed' || p === 'sticky') {
        found = e;
        break;
      }
    }
    return found;
  }

  /** 里面（连自己）有几个链接、按钮、输入框 */
  var CONTROLS = 'a, button, [role=button], input';
  function controls(el) {
    return el.querySelectorAll(CONTROLS).length + (matches(el, CONTROLS) ? 1 : 0);
  }

  function textOf(el) {
    return (el.innerText || '').replace(/\s+/g, ' ').trim();
  }

  /** 去掉藏起来的按钮以后，这一层还剩的字 */
  function restText(panel, buttons) {
    var t = textOf(panel);
    for (var i = 0; i < buttons.length; i++) t = t.replace(textOf(buttons[i]), '');
    return t.trim();
  }

  /** 识别网站顶部导航栏（避免因为导航栏带了一个「下载 App」小按钮就把整条顶部导航栏藏掉） */
  function isTopNavBar(el) {
    if (el.tagName === 'HEADER' || el.tagName === 'NAV' || matches(el, '[role=navigation]')) return true;
    if (matches(el, '[role=dialog], [aria-modal=true]')) return false;
    var r = el.getBoundingClientRect();
    if (r.top <= 4 && r.height > 0 && r.height <= 76 && controls(el) >= 4) return true;
    return false;
  }

  /** 是否为全屏透明/半透明遮罩背板（隐藏弹窗后一并清理残留暗色蒙层，恢复点击与滚动） */
  function isPureBackdropMask(el) {
    if (!el || el === document.body || el === document.documentElement || hidden.has(el)) return false;
    if (getComputedStyle(el).position !== 'fixed') return false;
    var r = el.getBoundingClientRect();
    if (r.width < innerWidth * 0.85 || r.height < innerHeight * 0.85) return false;
    if (el.querySelector('video, input, textarea, iframe')) return false;
    return textOf(el).length === 0;
  }

  function cleanupModalLocksAndMask(pf) {
    var parent = pf.parentElement;
    var prev = pf.previousElementSibling;
    var next = pf.nextElementSibling;
    if (isPureBackdropMask(prev)) hide(prev);
    if (isPureBackdropMask(next)) hide(next);
    if (parent && parent !== document.body && parent !== document.documentElement && isPureBackdropMask(parent)) {
      hide(parent);
    }
    var roots = [document.documentElement, document.body];
    for (var ri = 0; ri < roots.length; ri++) {
      var root = roots[ri];
      if (!root) continue;
      if (root.style.overflow === 'hidden') root.style.removeProperty('overflow');
      if (root.style.overflowY === 'hidden') root.style.removeProperty('overflow-y');
      if (root.style.position === 'fixed') root.style.removeProperty('position');
    }
  }

  function scan() {
    var body = document.body;
    if (!body) return;
    var buttons = [];
    var panels = [];
    var panelSeen = new WeakSet();
    var walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
    for (var n = walker.nextNode(); n; n = walker.nextNode()) {
      var v = n.nodeValue;
      if (!v || v.length > MAX_PANEL || !WORD.test(v)) continue;
      var el = n.parentElement;
      if (!el || hidden.has(el) || closest(el, 'script, style, textarea, [contenteditable]') || !isVisible(el)) continue;
      var f = floating(el);
      if (f && !hidden.has(f) && !panelSeen.has(f)) {
        panelSeen.add(f);
        panels.push(f);
      }
      if (v.length > MAX_BUTTON * 2) continue;
      // 往上取到字还不多的那一层（「打开」「App」可能分在两个元素里）；
      // 不越过悬浮的那一层，也不把旁边的按钮（导航栏的搜索、登录）带上
      for (var up = el.parentElement; up && up !== body && up !== f && textOf(up).length <= MAX_BUTTON && controls(up) <= controls(el); up = up.parentElement) el = up;
      var t = textOf(el);
      if (t.length > MAX_BUTTON || !BUTTON.test(t)) continue;
      var button = closest(el, 'a, button, [role=button]') || el;
      if (hidden.has(button) || button.querySelector('video, input, textarea')) continue;
      // 普通位置的按钮只藏链接、按钮；悬浮的都藏
      if (f || matches(button, 'a, button, [role=button]')) buttons.push(button);
    }
    // 悬浮横幅、底部面板、弹窗：字不多、非顶部导航栏，且属于「打开 App」推广面板的整个藏起（含遮罩层）
    for (var pi = 0; pi < panels.length; pi++) {
      var pf = panels[pi];
      var fullText = textOf(pf);
      if (fullText.length > MAX_PANEL || pf.querySelector('video, input, textarea') || isTopNavBar(pf)) continue;
      var rest = restText(pf, buttons);
      var rect = pf.getBoundingClientRect();
      var isModalPopup = matches(pf, '[role=dialog], [aria-modal=true]') || rect.top > 56 ||
        (rect.width >= innerWidth * 0.8 && rect.height >= innerHeight * 0.8);
      var matchedPromo = PROMO.test(rest) || (isModalPopup && controls(pf) <= 5 && (PROMO.test(fullText) || BUTTON.test(fullText)));
      if (!matchedPromo) continue;
      hide(pf);
      cleanupModalLocksAndMask(pf);
    }
    for (var bi = 0; bi < buttons.length; bi++) hide(buttons[bi]);
  }

  var timer = 0;
  function later() {
    if (!timer) timer = setTimeout(function () { timer = 0; try { scan(); } catch (e) {} }, 350);
  }
  function start() {
    later();
    try { new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
