// 点选屏蔽：在网页上点一个广告，高亮它，App 底部的操作栏可以扩大到外层、缩小回去、屏蔽或取消。
// 只在顶层页面里跑（广告在 iframe 里时选中的是那个 iframe）。由 App 在开始点选时注入。
(function () {
  'use strict';
  if (window.__voxPicker) { window.__voxPicker.start(); return; }

  var active = false;
  var current = null;
  var history = [];
  var box = null;

  function matches(el, sel) {
    var fn = el && (el.matches || el.webkitMatchesSelector || el.msMatchesSelector);
    return fn ? fn.call(el, sel) : false;
  }

  function cssEscape(s) {
    if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(s);
    return String(s).replace(/([^\w-])/g, '\\$1').replace(/^(\d)/, '\\3$1 ');
  }

  // 像随机生成的名字（带一长串数字、哈希）换一次网页就变了，不拿来当选择器
  function stableName(s) {
    return /^[A-Za-z_-][\w-]{0,40}$/.test(s) && !/\d{3,}/.test(s) && !/[0-9a-f]{6,}/i.test(s) &&
      // 拆成格子的广告块（xxx_0_7）、大小写乱跳的随机名字（PsbCMkyi）
      !/_\d+_\d+$/.test(s) && !(/^[A-Za-z]{4,12}$/.test(s) && (s.match(/[A-Z]/g) || []).length >= 3 && /[a-z]/.test(s));
  }

  function isFloating(e) {
    var p = getComputedStyle(e).position;
    return p === 'fixed' || p === 'sticky';
  }

  /** 点到的元素所在的最外层浮动层（贴边广告条） */
  function floatingAncestor(el) {
    var found = null;
    for (var e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      if (isFloating(e)) found = e;
    }
    return found;
  }

  /** 点到的是很小的一块（广告拆成的小格子、图标）：往外扩到像样大小的那层 */
  function grow(el) {
    var path = [];
    var e = el;
    for (var i = 0; i < 6; i++) {
      var r = e.getBoundingClientRect();
      if (r.width >= 120 && r.height >= 60) break;
      var p = e.parentElement;
      if (!p || p === document.body || p === document.documentElement) break;
      path.push(e);
      e = p;
    }
    return { el: e, path: path };
  }

  function selectorOf(el) {
    if (el.id && stableName(el.id)) return '#' + cssEscape(el.id);
    var parts = [];
    for (var e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      if (e !== el && e.id && stableName(e.id)) { parts.unshift('#' + cssEscape(e.id)); break; }
      var p = e.tagName.toLowerCase();
      var cls = [];
      for (var ci = 0; ci < e.classList.length && cls.length < 3; ci++) {
        var name = e.classList[ci];
        if (stableName(name)) cls.push(cssEscape(name));
      }
      if (cls.length) p += '.' + cls.join('.');
      var parent = e.parentElement;
      if (parent) {
        var ch = parent.children;
        var matchCount = 0;
        var sameTagIdx = 0;
        var foundIdx = 1;
        for (var k = 0; k < ch.length; k++) {
          var sib = ch[k];
          if (matches(sib, p)) matchCount++;
          if (sib.tagName === e.tagName) {
            sameTagIdx++;
            if (sib === e) foundIdx = sameTagIdx;
          }
        }
        if (matchCount > 1) p += ':nth-of-type(' + foundIdx + ')';
      }
      parts.unshift(p);
      // 已经只指向这一个（或一类）元素、又带了类名：够了，越短越不容易因为网页改版失效
      var s = parts.join(' > ');
      if (cls.length && document.querySelectorAll(s).length <= 3) return s;
      if (parts.length >= 6) break;
      // 走到了 body 下面一层：从 body 开始写，免得 div:nth-of-type(3) 这种短选择器把网页别处的元素也藏了
      if (parent === document.body) { parts.unshift('body'); break; }
    }
    return parts.join(' > ');
  }

  function describe(el) {
    var r = el.getBoundingClientRect();
    return el.tagName.toLowerCase() + ' ' + Math.round(r.width) + '×' + Math.round(r.height);
  }

  function highlight() {
    if (!box) {
      box = document.createElement('div');
      box.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #ff6d00;background:rgba(255,109,0,.25);border-radius:4px;transition:all .1s';
      document.documentElement.appendChild(box);
    }
    if (!current) { box.style.display = 'none'; return; }
    var r = current.getBoundingClientRect();
    box.style.display = 'block';
    box.style.left = r.left + 'px';
    box.style.top = r.top + 'px';
    box.style.width = r.width + 'px';
    box.style.height = r.height + 'px';
  }

  function report() {
    highlight();
    if (!current) return;
    var sel = selectorOf(current);
    var count = 0;
    try { count = document.querySelectorAll(sel).length; } catch (e) {}
    try { VoxBridge.pickerSelect(sel, count, describe(current), !!floatingAncestor(current) || isFloating(current)); } catch (e) {}
  }

  // 点选时网页自己的点击、跳转都不要：在最前面截住
  function swallow(e) {
    if (!active) return;
    e.stopImmediatePropagation();
    if (e.type === 'click' || e.type === 'auxclick' || e.type === 'contextmenu') e.preventDefault();
  }

  function onClick(e) {
    if (!active) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    var x = e.clientX, y = e.clientY;
    var el = document.elementFromPoint(x, y);
    if (!el || el === box) return;
    if (el === document.body || el === document.documentElement) return;
    // 在浮动广告条里：直接选整条；否则太小的块往外扩一点。「缩小」可以退回点到的那块
    var fl = floatingAncestor(el);
    history = [];
    if (fl && fl !== el) {
      for (var p = el; p && p !== fl; p = p.parentElement) history.push(p);
      current = fl;
    } else {
      var g = grow(el);
      history = g.path;
      current = g.el;
    }
    report();
  }

  var EVENTS = ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'auxclick', 'contextmenu', 'dblclick'];

  var api = {
    start: function () {
      if (active) return;
      active = true;
      window.__voxPicking = true;
      current = null;
      history = [];
      window.addEventListener('click', onClick, true);
      for (var i = 0; i < EVENTS.length; i++) window.addEventListener(EVENTS[i], swallow, true);
      window.addEventListener('scroll', highlight, true);
    },
    stop: function () {
      active = false;
      window.__voxPicking = false;
      current = null;
      window.removeEventListener('click', onClick, true);
      for (var i = 0; i < EVENTS.length; i++) window.removeEventListener(EVENTS[i], swallow, true);
      window.removeEventListener('scroll', highlight, true);
      if (box) {
        if (box.parentNode) box.parentNode.removeChild(box);
        box = null;
      }
    },
    /** 扩大到外层 */
    parent: function () {
      if (!current) return;
      var p = current.parentElement;
      if (!p || p === document.body || p === document.documentElement) return;
      history.push(current);
      current = p;
      report();
    },
    /** 缩小回刚才那层 */
    child: function () {
      if (!history.length) return;
      current = history.pop();
      report();
    },
    /** 屏蔽本站所有贴边浮动广告（web_guard.js 负责） */
    hideFloating: function () {
      if (current) current.style.setProperty('display', 'none', 'important');
      try { window.__voxFloat && window.__voxFloat.enable(); } catch (e) {}
      api.stop();
    },
    /** 屏蔽：马上藏起来（以后靠规则），结束点选 */
    hide: function (sel) {
      var appliedSheet = false;
      try {
        if (document.adoptedStyleSheets !== undefined && window.CSSStyleSheet && CSSStyleSheet.prototype.replaceSync) {
          var sheet = new CSSStyleSheet();
          sheet.replaceSync(sel + '{display:none!important}');
          document.adoptedStyleSheets = document.adoptedStyleSheets.concat([sheet]);
          appliedSheet = true;
        }
      } catch (e) {}
      if (!appliedSheet) {
        try {
          var style = document.createElement('style');
          style.textContent = sel + '{display:none!important}';
          (document.head || document.documentElement).appendChild(style);
        } catch (e2) {}
        try {
          var els = document.querySelectorAll(sel);
          for (var i = 0; i < els.length; i++) els[i].style.setProperty('display', 'none', 'important');
        } catch (e3) {}
      }
      api.stop();
    },
  };
  window.__voxPicker = api;
  api.start();
})();
