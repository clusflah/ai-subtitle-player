// 译幕网页字幕：注入到网页的每个框架里（含 iframe），找出正在播放的视频，
// 用 Web Audio 读出它的声音，降到 16kHz 单声道交给 App 识别；同时定时报告视频的播放位置，App 按它显示字幕。
// 读不到声音的视频（跨域且没开 CORS、有版权保护）不接管，免得把网页的声音弄没了，改为告诉 App 视频地址。
(function () {
  if (window.__voxtitle) return;
  window.__voxtitle = true;
  var B = window.VoxBridge;
  if (!B) return;

  var RATE = 16000;
  var BUFFER = 4096;
  var frameId = Math.random().toString(36).slice(2);
  var ctx = null;
  var hooked = new WeakMap(); // video -> true（已接上 Web Audio）
  var reported = new WeakMap(); // video -> 已报告过的问题
  var active = null;
  var enabled = false;

  function pick() {
    var best = null, area = 0;
    var list = document.querySelectorAll('video');
    for (var i = 0; i < list.length; i++) {
      var v = list[i];
      if (v.paused || v.ended || v.readyState < 2) continue;
      var r = v.getBoundingClientRect();
      var a = r.width * r.height;
      if (a > area) { best = v; area = a; }
    }
    return best;
  }

  /** 能不能读到声音：null 表示能；否则是原因 */
  function problemOf(v) {
    if (v.mediaKeys) return 'drm';
    var src = v.currentSrc || v.src || '';
    if (!src || src.indexOf('blob:') === 0 || src.indexOf('data:') === 0) return null;
    try {
      if (new URL(src, location.href).origin === location.origin) return null;
    } catch (e) { return null; }
    if (v.crossOrigin) return null;
    return 'cors';
  }

  function toBase64(int16) {
    var bytes = new Uint8Array(int16.buffer);
    var s = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(s);
  }

  function hook(v) {
    if (hooked.get(v)) return true;
    try {
      if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)();
      var source = ctx.createMediaElementSource(v);
      source.connect(ctx.destination);
      var proc = ctx.createScriptProcessor(BUFFER, 2, 1);
      var step = ctx.sampleRate / RATE;
      var pos = 0; // 下一个输出样本在输入里的位置（跨缓冲区连续）
      proc.onaudioprocess = function (e) {
        if (!enabled || active !== v || v.paused) { pos = 0; return; }
        var input = e.inputBuffer;
        var n = input.length;
        var l = input.getChannelData(0);
        var r = input.numberOfChannels > 1 ? input.getChannelData(1) : l;
        var out = new Int16Array(Math.ceil((n - pos) / step));
        var k = 0;
        for (; pos < n; pos += step) {
          var i = pos | 0;
          var s = (l[i] + r[i]) * 0.5;
          if (s > 1) s = 1; else if (s < -1) s = -1;
          out[k++] = s * 32767;
        }
        pos -= n;
        // 这段声音的开头大约在当前播放位置之前一个缓冲区
        var startMs = v.currentTime * 1000 - n / ctx.sampleRate * 1000 * v.playbackRate;
        try { B.audio(frameId, toBase64(k === out.length ? out : out.subarray(0, k)), startMs, v.playbackRate); } catch (err) {}
      };
      source.connect(proc);
      proc.connect(ctx.destination); // 不接到输出上不会触发回调；输出是静音，不影响网页的声音
      hooked.set(v, true);
      return true;
    } catch (err) {
      try { B.problem(frameId, 'hook', String(err && err.message || err)); } catch (e) {}
      return false;
    }
  }

  function tick() {
    try { enabled = B.enabled(); } catch (e) { return; }
    var v = pick();
    if (!v) {
      if (active && (active.paused || !active.isConnected)) {
        report(active, true);
      }
      return;
    }
    active = v;
    if (enabled) {
      var p = problemOf(v);
      if (p && !hooked.get(v)) {
        if (reported.get(v) !== p) {
          reported.set(v, p);
          try { B.problem(frameId, p, v.currentSrc || v.src || ''); } catch (e) {}
        }
      } else if (hook(v) && ctx && ctx.state === 'suspended') {
        ctx.resume();
      }
    }
    report(v, v.paused);
  }

  function report(v, paused) {
    // 顶层页面里的视频报告它在屏幕上的位置（CSS 像素），App 把字幕放在视频下沿；iframe 里的算不出相对整页的位置
    var top = -1, bottom = -1;
    if (window === window.top && !document.fullscreenElement && v.isConnected) {
      var r = v.getBoundingClientRect();
      top = r.top; bottom = r.bottom;
    }
    try { B.tick(frameId, location.href, v.currentTime * 1000, paused, v.playbackRate, top, bottom, window.innerWidth); } catch (e) {}
  }

  setInterval(tick, 250);
})();
