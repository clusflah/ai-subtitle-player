// 屏蔽网页上的「打开 App」「下载 App」推广：悬浮按钮、横幅、跳转失败的弹窗。
// 网页开头注入（每个框架一份）；VoxBridge.cleanPage() 关着就什么都不做。
(() => {
  if (window.__voxClean) return;
  window.__voxClean = true;

  // ---- 拦截广告：AdGuard 规则里要藏起来的元素（没开拦截、规则还没下好时是空的） ----
  try {
    const ad = window.VoxBridge && VoxBridge.adCss(location.hostname);
    if (ad) {
      // 不往网页里加 <style> 节点：React / Next.js 的网页会比对页面结构，多一个节点就整页重新渲染
      if (document.adoptedStyleSheets !== undefined && window.CSSStyleSheet && CSSStyleSheet.prototype.replaceSync) {
        const sheet = new CSSStyleSheet();
        sheet.replaceSync(ad);
        document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
      } else {
        const style = document.createElement('style');
        style.textContent = ad;
        (document.head || document.documentElement).appendChild(style);
      }
    }
  } catch (e) {}

  try { if (window.VoxBridge && !VoxBridge.cleanPage()) return; } catch (e) {}

  // ---- 已知网站：直接用样式藏起来，不会先闪一下 ----
  const SITES = [
    [/(^|\.)bilibili\.com$/, [
      'm-open-app.m-nav-openapp', 'm-open-app.fixed-openapp', 'm-open-app.m-video-main-launchapp',
      '.m-related-openapp', '.openapp-dialog', '.caution-dialog', '.gsl-buffer-app', '.m-home__no-more',
      '.v-card-toapp .open-app', '.launch-app-btn', '.home-float-openapp',
    ]],
    [/(^|\.)zhihu\.com$/, ['.OpenInAppButton', '.MobileAppHeader-downloadLink', '.ContentItem-expandButton + .OpenInApp']],
    [/(^|\.)douban\.com$/, ['.TalionNav-primary .download', '.Advertisement', '.oia-wrap', '.sub-app-download']],
  ];
  const host = location.hostname;
  const rules = SITES.filter(([re]) => re.test(host)).flatMap(([, list]) => list);
  if (rules.length) {
    const style = document.createElement('style');
    style.textContent = rules.join(',\n') + ' { display: none !important; }';
    (document.head || document.documentElement).appendChild(style);
  }

  // B 站：网页上的卡片、标题点了会去开 App（失败了再弹窗、往剪贴板里写东西）。
  // 视频卡片改成在网页里打开，其他的点了没反应
  addEventListener('click', e => {
    const m = e.target && e.target.closest && e.target.closest('m-open-app');
    if (!m) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    const link = m.getAttribute('universallink');
    if (link && /^https?:/.test(link) && m.querySelector('img, picture')) location.href = link;
  }, true);

  // ---- 其他网站：按文字找 ----
  const WORD = /app|客户端|应用/i;
  // 「打开 App」「下载客户端」「App 内观看」这类按钮上的字
  const BUTTON = /(打开|下载|安装|前往|去|用|在|进入|启动).{0,8}(app|客户端|应用)|(app|客户端)\s*(内|中|里)?\s*(打开|观看|播放|查看|阅读|看|体验|浏览)|open\s+(in\s+)?(the\s+)?app|get\s+the\s+app|download\s+(the\s+)?app/i;
  // 悬浮横幅、弹窗里剩下的说明文字
  const PROMO = /(app|客户端)[\s\S]*(下载|打开|安装|跳转|体验)|(下载|打开|安装|跳转)[\s\S]*(app|客户端)/i;
  const MAX_BUTTON = 24;
  const MAX_PANEL = 150;

  const hidden = new WeakSet();
  const hide = el => {
    hidden.add(el);
    el.style.setProperty('display', 'none', 'important');
  };
  const isVisible = el => el.offsetWidth > 0 || el.offsetHeight > 0;

  /** 往上找悬浮（fixed、sticky）的那一层 */
  const floating = el => {
    for (let e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
      const p = getComputedStyle(e).position;
      if (p === 'fixed' || p === 'sticky') return e;
    }
    return null;
  };

  /** 里面（连自己）有几个链接、按钮、图标 */
  const CONTROLS = 'a, button, [role=button], input, svg, img';
  const controls = el => el.querySelectorAll(CONTROLS).length + (el.matches(CONTROLS) ? 1 : 0);

  const textOf = el =>(el.innerText || '').replace(/\s+/g, ' ').trim();

  /** 去掉藏起来的按钮以后，这一层还剩的字 */
  const restText = (panel, buttons) => {
    let t = textOf(panel);
    for (const b of buttons) t = t.replace(textOf(b), '');
    return t.trim();
  };

  const scan = () => {
    const body = document.body;
    if (!body) return;
    const buttons = [];
    const panels = new Set();
    const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
    for (let n = walker.nextNode(); n; n = walker.nextNode()) {
      const v = n.nodeValue;
      if (!v || v.length > MAX_PANEL || !WORD.test(v)) continue;
      let el = n.parentElement;
      if (!el || hidden.has(el) || el.closest('script, style, textarea, [contenteditable]') || !isVisible(el)) continue;
      const f = floating(el);
      if (f && !hidden.has(f)) panels.add(f);
      if (v.length > MAX_BUTTON * 2) continue;
      // 往上取到字还不多的那一层（「打开」「App」可能分在两个元素里）；
      // 不越过悬浮的那一层，也不把旁边的图标、按钮（导航栏的搜索、登录）带上
      for (let up = el.parentElement; up && up !== body && up !== f && textOf(up).length <= MAX_BUTTON && controls(up) <= controls(el); up = up.parentElement) el = up;
      const t = textOf(el);
      if (t.length > MAX_BUTTON || !BUTTON.test(t)) continue;
      const button = el.closest('a, button, [role=button]') || el;
      if (hidden.has(button) || button.querySelector('video, input, textarea')) continue;
      // 普通位置的按钮只藏链接、按钮；悬浮的都藏
      if (f || button.matches('a, button, [role=button]')) buttons.push(button);
    }
    // 悬浮横幅、弹窗：字不多、去掉按钮以后还在说 App 的整个藏起来（只是带了个「下载 App」的导航栏不藏）
    for (const f of panels) {
      if (textOf(f).length > MAX_PANEL || f.querySelector('video, input, textarea')) continue;
      if (!PROMO.test(restText(f, buttons))) continue;
      hide(f);
      // 弹窗锁住了页面滚动的放开
      for (const r of [document.documentElement, body]) if (r.style.overflow === 'hidden') r.style.removeProperty('overflow');
    }
    for (const b of buttons) hide(b);
  };

  let timer = 0;
  const later = () => {
    if (!timer) timer = setTimeout(() => { timer = 0; try { scan(); } catch (e) {} }, 400);
  };
  const start = () => {
    later();
    new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true });
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
