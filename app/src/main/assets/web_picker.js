// 点选屏蔽：在网页上点一个广告，高亮它，App 底部的操作栏可以扩大到外层、缩小回去、屏蔽或取消。
// 只在顶层页面里跑（广告在 iframe 里时选中的是那个 iframe）。由 App 在开始点选时注入。
(() => {
  if (window.__voxPicker) { window.__voxPicker.start(); return; }

  let active = false;
  let current = null;
  let history = [];
  let box = null;

  // 像随机生成的名字（带一长串数字、哈希）换一次网页就变了，不拿来当选择器
  const stableName = (s) => /^[A-Za-z_-][\w-]{0,40}$/.test(s) && !/\d{3,}/.test(s) && !/[0-9a-f]{6,}/i.test(s);

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
    try { VoxBridge.pickerSelect(sel, count, describe(current)); } catch (e) {}
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
    current = el;
    history = [];
    report();
  }

  const EVENTS = ['mousedown', 'mouseup', 'pointerdown', 'pointerup', 'touchstart', 'touchend', 'auxclick', 'contextmenu', 'dblclick'];

  const api = {
    start() {
      if (active) return;
      active = true;
      current = null;
      history = [];
      window.addEventListener('click', onClick, true);
      for (const t of EVENTS) window.addEventListener(t, swallow, true);
      window.addEventListener('scroll', highlight, true);
    },
    stop() {
      active = false;
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
