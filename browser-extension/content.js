(() => {
  if (window.__voxMangaExtension) return;
  window.__voxMangaExtension = true;

  const LAZY = ['data-src', 'data-original', 'data-lazy-src', 'data-lazy', 'data-echo', 'data-url', 'data-img', 'data-original-src', 'data-actualsrc', 'data-kksrc', 'data-srcset', 'file', '_src'];
  const key = `vox-manga:${location.origin}${location.pathname}`;
  let pages = [];
  let scanTimer;

  function absolute(url) { try { return new URL(url, location.href).href; } catch (_) { return ''; } }
  function bestSrcset(value) { return value.split(',').map(x => x.trim().split(/\s+/)[0]).filter(Boolean).at(-1) || ''; }
  function source(image) {
    for (const name of LAZY) {
      const value = image.getAttribute(name);
      if (value && !/^data:/i.test(value)) return absolute(name.includes('srcset') ? bestSrcset(value) : value.trim());
    }
    const srcset = image.getAttribute('srcset');
    if (srcset) return absolute(bestSrcset(srcset));
    const value = image.currentSrc || image.src || '';
    return /^(https?:)/i.test(value) ? value : '';
  }
  function candidate(image) {
    const src = source(image);
    if (!src || /\.(svg|gif)(\?|$)/i.test(src)) return null;
    const rect = image.getBoundingClientRect();
    const viewport = Math.max(document.documentElement.clientWidth, innerWidth, 1);
    const loaded = image.naturalWidth > 2 && image.naturalHeight > 2;
    if (!(rect.width >= Math.min(viewport * .45, 320) || (loaded && image.naturalWidth >= 400 && image.naturalHeight >= 400))) return null;
    if ((loaded && image.naturalHeight / image.naturalWidth < .55) || (!loaded && rect.height > 20 && rect.height / rect.width < .3)) return null;
    if (getComputedStyle(image).display === 'none') return null;
    const link = image.closest('a[href]');
    if (link && link.hostname && link.hostname !== location.hostname) return null;
    return { image, src };
  }
  function findPages() {
    const candidates = [...document.images].map(candidate).filter(Boolean);
    if (candidates.length < 3) return [];
    const counts = new Map();
    for (const item of candidates) {
      for (let element = item.image.parentElement, depth = 0; element && depth < 4; element = element.parentElement, depth++) {
        counts.set(element, (counts.get(element) || 0) + 1);
        if (element === document.body) break;
      }
    }
    let container, amount = 0;
    for (const [element, count] of counts) if (count > amount || (count === amount && container?.contains(element))) [container, amount] = [element, count];
    if (!container || amount < 3) return [];
    const seen = new Set();
    return candidates.filter(x => container.contains(x.image) && !seen.has(x.src) && seen.add(x.src)).map(x => x.src);
  }
  function rescan() {
    pages = findPages();
    const button = document.querySelector('#vox-manga-entry');
    if (pages.length >= 3 && !document.querySelector('#vox-manga-reader')) {
      if (!button) {
        const entry = document.createElement('button');
        entry.id = 'vox-manga-entry'; entry.textContent = '阅读漫画';
        entry.addEventListener('click', openReader); document.documentElement.append(entry);
      } else button.textContent = '阅读漫画';
    } else button?.remove();
  }
  function later() { clearTimeout(scanTimer); scanTimer = setTimeout(rescan, 700); }
  function getSettings() { return new Promise(resolve => chrome.storage.local.get([key], r => resolve(r[key] || { mode: 'fit', rtl: false, page: 0 }))); }
  function save(settings) { chrome.storage.local.set({ [key]: settings }); }
  function preload(index) { [index - 1, index + 1, index + 2].filter(i => i >= 0 && i < pages.length).forEach(i => { const img = new Image(); img.src = pages[i]; }); }
  async function openReader() {
    rescan(); if (pages.length < 3) return;
    // 由网页按钮直接打开时，这一调用仍在用户手势内，浏览器会隐藏地址栏和工具栏。
    // 从扩展弹窗发来的消息没有这个权限，仍可在阅读器中点“全屏”。
    if (!document.fullscreenElement) document.documentElement.requestFullscreen?.().catch(() => {});
    document.querySelector('#vox-manga-entry')?.remove();
    const settings = await getSettings();
    let current = Math.min(settings.page || 0, pages.length - 1);
    const root = document.createElement('section'); root.id = 'vox-manga-reader'; root.tabIndex = -1;
    root.innerHTML = `<header class="vox-toolbar"><button data-action="close">退出</button><button data-action="fullscreen">全屏</button><button data-action="mode">显示</button><button data-action="rtl">RTL</button><span class="vox-title"></span><span class="vox-progress"></span></header><main class="vox-pages"></main>`;
    const list = root.querySelector('.vox-pages'), title = root.querySelector('.vox-title'), progress = root.querySelector('.vox-progress');
    title.textContent = document.title || '漫画阅读';
    const render = () => {
      const mode = settings.mode || 'fit';
      list.className = `vox-pages ${mode}`;
      const shown = mode === 'fit' ? [current] : mode === 'double' ? (settings.rtl ? [current + 1, current] : [current, current + 1]).filter(i => i < pages.length) : pages.map((_, i) => i);
      list.replaceChildren(...shown.map(i => { const page = document.createElement('article'); page.className = 'vox-page'; const image = new Image(); image.src = pages[i]; image.alt = `第 ${i + 1} 页`; image.loading = Math.abs(i - current) > 2 ? 'lazy' : 'eager'; page.append(image); return page; }));
      if (settings.rtl && (mode === 'width' || mode === 'strip')) list.dir = 'rtl'; else list.removeAttribute('dir');
      progress.textContent = `${current + 1} / ${pages.length}`;
      root.querySelector('[data-action="mode"]').textContent = ({ fit: '单页', width: '宽度', double: '双页', strip: '长条' })[mode];
      root.querySelector('[data-action="rtl"]').setAttribute('aria-pressed', String(!!settings.rtl));
      save({ ...settings, page: current }); preload(current);
    };
    const turn = direction => { current = Math.max(0, Math.min(pages.length - 1, current + (settings.rtl ? -direction : direction))); render(); list.scrollTo({ top: 0, behavior: 'smooth' }); };
    root.addEventListener('click', event => {
      const action = event.target.closest('button')?.dataset.action;
      if (action === 'close') {
        root.remove();
        if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
        rescan();
      }
      if (action === 'fullscreen') {
        if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
        else document.documentElement.requestFullscreen?.().catch(() => {});
      }
      if (action === 'mode') { const modes = ['fit', 'width', 'double', 'strip']; settings.mode = modes[(modes.indexOf(settings.mode || 'fit') + 1) % modes.length]; render(); }
      if (action === 'rtl') { settings.rtl = !settings.rtl; render(); }
    });
    root.addEventListener('keydown', event => { if (event.key === 'Escape') root.querySelector('[data-action="close"]').click(); if (event.key === 'ArrowRight' || event.key === 'PageDown') { event.preventDefault(); turn(1); } if (event.key === 'ArrowLeft' || event.key === 'PageUp') { event.preventDefault(); turn(-1); } });
    document.documentElement.append(root); render(); root.focus();
  }
  chrome.runtime?.onMessage.addListener((message, _, reply) => {
    rescan();
    if (message.type === 'VOX_MANGA_STATUS') reply({ count: pages.length });
    if (message.type === 'VOX_MANGA_OPEN') openReader();
  });
  new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['src', 'data-src', 'srcset'] });
  addEventListener('load', later); later();
})();
