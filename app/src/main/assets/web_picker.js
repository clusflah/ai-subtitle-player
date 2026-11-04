// 点选屏蔽：在网页上点一个广告，高亮它，App 底部的操作栏可以扩大到外层、缩小回去、屏蔽或取消。
// 只在顶层页面里跑（广告在 iframe 里时选中的是那个 iframe）。由 App 在开始点选时注入。
(() => {
  if (window.__voxPicker) { window.__voxPicker.start(); return; }

  let active = false;
  let current = null;
  let history = [];
  let box = null;

  // 像随机生成的名字（带一长串数字、哈希）换一次网页就变了，不拿来当选择器
  const stableName = (s) => /^[A-Za-z_-][\w-]{0,40}$/.test(s) && !/\d{3,}/.test(s) && !/[0-9a-f]{6,}/i.test(s) &&
    // 拆成格子的广告块（xxx_0_7）、大小写乱跳的随机名字（PsbCMkyi）
    !/_\d+_\d+$/.test(s) && !(/^[A-Za-z]{4,12}$/.test(s) && (s.match(/[A-Z]/g) || []).length >= 3 && /[a-z]/.test(s));

  function isFloating(e) {
    const p = getComputedStyle(e).position;
    return p === 'fixed' || p === 'sticky';
  }

  /** 点到的元素所在的最外层浮动层（贴边广告条） */
  function floatingAncestor(el) {
    let found = null;
    for (let e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      if (isFloating(e)) found = e;
    }
    return found;
  }

  /** 点到的是很小的一块（广告拆成的小格子、图标）：往外扩到像样大小的那层 */
  function grow(el) {
    const path = [];
    let e = el;
    for (let i = 0; i < 6; i++) {
      const r = e.getBoundingClientRect();
      if (r.width >= 120 && r.height >= 60) break;
      const p = e.parentElement;
      if (!p || p === document.body || p === document.documentElement) break;
      path.push(e);
      e = p;
    }
    return { el: e, path };
  }

  function selectorOf(el) {
    if (el.id && stableName(el.id)) return '#' + CSS.escape(el.id);
    const parts = [];
    for (let e = el; e && e.nodeType === 1 && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      if (e !== el && e.id && stableName(e.id)) { parts.unshift('#' + CSS.escape(e.id)); break; }
      let p = e.tagName.toLowerCase();
      const cls = [...e.classList].filter(stableName).slice(0, 3);
      if (cls.length) p += '.' + cls.map((c) => CSS.escape(c)).join('.');
      const parent = e.parentElement;
      if (parent && [...parent.children].filter((s) => s.matches(p)).length > 1) {
        p += ':nth-of-type(' + ([...parent.children].filter((s) => s.tagName === e.tagName).indexOf(e) + 1) + ')';
      }
      parts.unshift(p);
      // 已经只指向这一个（或一类）元素、又带了类名：够了，越短越不容易因为网页改版失效
      const s = parts.join(' > ');
      if (cls.length && document.querySelectorAll(s).length <= 3) return s;
      if (parts.length >= 6) break;
      // 走到了 body 下面一层：从 body 开始写，免得 div:nth-of-type(3) 这种短选择器把网页别处的元素也藏了
      if (parent === document.body) { parts.unshift('body'); break; }
    }
    return parts.join(' > ');
  }

  function describe(el) {
    const r = el.getBoundingClientRect();
    return el.tagName.toLowerCase() + ' ' + Math.round(r.width) + '×' + Math.round(r.height);
  }

  function highlight() {
    if (!box) {
      box = document.createElement('div');
      box.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;border:2px solid #ff6d00;background:rgba(255,109,0,.25);border-radius:4px;transition:all .1s';
      document.documentElement.appendChild(box);
    }
    if (!current) { box.style.display = 'none'; return; }
    const r = current.getBoundingClientRect();
    Object.assign(box.style, { display: 'block', left: r.left + 'px', top: r.top + 'px', width: r.width + 'px', height: r.height + 'px' });
  }

  function report() {
    highlight();
    if (!current) return;
    const sel = selectorOf(current);
    let count = 0;
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
    const x = e.clientX, y = e.clientY;
    let el = document.elementFromPoint(x, y);
    if (!el || el === box) return;
    if (el === document.body || el === document.documentElement) return;
    // 在浮动广告条里：直接选整条；否则太小的块往外扩一点。「缩小」可以退回点到的那块
    const fl = floatingAncestor(el);
    history = [];
    if (fl && fl !== el) {
      for (let e = el; e && e !== fl; e = e.parentElement) history.push(e);
      current = fl;
    } else {
      const g = grow(el);
      history = g.path;
      current = g.el;
    }
    report();
  }

  const EVENTS = ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'auxclick', 'contextmenu', 'dblclick'];

  const api = {
    start() {
      if (active) return;
      active = true;
      window.__voxPicking = true;
      current = null;
      history = [];
      window.addEventListener('click', onClick, true);
      for (const t of EVENTS) window.addEventListener(t, swallow, true);
      window.addEventListener('scroll', highlight, true);
    },
    stop() {
      active = false;
      window.__voxPicking = false;
      current = null;
      window.removeEventListener('click', onClick, true);
      for (const t of EVENTS) window.removeEventListener(t, swallow, true);
      window.removeEventListener('scroll', highlight, true);
      if (box) { box.remove(); box = null; }
    },
    /** 扩大到外层 */
    parent() {
      if (!current) return;
      const p = current.parentElement;
      if (!p || p === document.body || p === document.documentElement) return;
      history.push(current);
      current = p;
      report();
    },
    /** 缩小回刚才那层 */
    child() {
      if (!history.length) return;
      current = history.pop();
      report();
    },
    /** 屏蔽本站所有贴边浮动广告（web_guard.js 负责） */
    hideFloating() {
      if (current) current.style.setProperty('display', 'none', 'important');
      try { window.__voxFloat && window.__voxFloat.enable(); } catch (e) {}
      api.stop();
    },
    /** 屏蔽：马上藏起来（以后靠规则），结束点选 */
    hide(sel) {
      try {
        const sheet = new CSSStyleSheet();
        sheet.replaceSync(sel + '{display:none!important}');
        document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
      } catch (e) {
        document.querySelectorAll(sel).forEach((el) => el.style.setProperty('display', 'none', 'important'));
      }
      api.stop();
    },
  };
  window.__voxPicker = api;
  api.start();
})();
