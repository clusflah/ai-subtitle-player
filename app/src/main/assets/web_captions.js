// 译幕网页字幕：注入到网页的每个框架里（含 iframe），在网页自己的脚本之前运行。
// 找出正在播放的视频，把它的声音降到 16kHz 单声道交给 App 识别；同时定时报告视频的播放位置，App 按它显示字幕。
//
// 声音有两种拿法：
// 1. 提前识别：网页用 MSE 播放（B 站、YouTube、hls.js、flv.js 等）时，截下网页塞给播放器的音频数据，
//    它通常比画面提前缓冲十几到几十秒，在这里解码好交给 App，字幕能赶在声音前面出来；
// 2. 边播边识别：拿不到 MSE 数据或解不了时，用 Web Audio 读出正在播放的声音，字幕会晚一两秒。
// 读不到声音的视频（跨域且没开 CORS、有版权保护）不接管，免得把网页的声音弄没了，改为告诉 App，由 App 想办法。
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
  /** 当前视频正在用（或还在尝试）提前识别：这时不用 Web Audio 边播边读 */
  var aheadBusy = false;

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

  /** 能不能用 Web Audio 读到声音：null 表示能；否则是原因 */
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
    var bytes = new Uint8Array(int16.buffer, int16.byteOffset, int16.byteLength);
    var s = '';
    for (var i = 0; i < bytes.length; i += 0x8000) {
      s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(s);
  }

  function concat(a, b) {
    if (!a || !a.length) return b;
    var out = new Uint8Array(a.length + b.length);
    out.set(a, 0);
    out.set(b, a.length);
    return out;
  }

  // ---------- 1. 提前识别：截下网页塞给 MSE 的音频数据 ----------

  var feeders = [];
  var feederOf = new WeakMap(); // SourceBuffer -> Feeder
  var sourceOf = {}; // blob 地址 -> MediaSource
  var MS = window.MediaSource || window.WebKitMediaSource;

  function u32(b, i) { return b[i] * 16777216 + (b[i + 1] << 16) + (b[i + 2] << 8) + b[i + 3]; }
  function u64(b, i) { return u32(b, i) * 4294967296 + u32(b, i + 4); }
  function fourcc(b, i) { return String.fromCharCode(b[i], b[i + 1], b[i + 2], b[i + 3]); }

  /** MP4 盒子 [start, end) 里的子盒子 */
  function boxes(b, start, end) {
    var out = [];
    var i = start;
    while (i + 8 <= end) {
      var size = u32(b, i), head = 8;
      if (size === 1) { size = u64(b, i + 8); head = 16; } else if (size === 0) size = end - i;
      if (size < head || i + size > end) break;
      out.push({ type: fourcc(b, i + 4), body: i + head, end: i + size });
      i += size;
    }
    return out;
  }

  function child(b, box, type) {
    var list = boxes(b, box.body, box.end);
    for (var k = 0; k < list.length; k++) if (list[k].type === type) return list[k];
    return null;
  }

  /** EBML 变长整数；marker 为 true 时保留长度标记位（元素 ID 的写法） */
  function vint(b, i, marker) {
    var first = b[i];
    if (first === undefined) return null;
    var len = 1, mask = 0x80;
    while (len <= 8 && !(first & mask)) { len++; mask >>= 1; }
    if (len > 8 || i + len > b.length) return null;
    var v = marker ? first : (first & (mask - 1));
    var ones = (first & (mask - 1)) === mask - 1;
    for (var k = 1; k < len; k++) {
      v = v * 256 + b[i + k];
      if (b[i + k] !== 255) ones = false;
    }
    return { value: v, len: len, unknown: !marker && ones };
  }

  function uint(b, start, end) {
    var v = 0;
    for (var i = start; i < end; i++) v = v * 256 + b[i];
    return v;
  }

  /** 一个音频 SourceBuffer：把网页塞进来的数据切成「初始化段 + 一段段媒体段」，按需解码 */
  function Feeder(ms, kind) {
    this.ms = ms;
    this.kind = kind; // 'mp4' | 'webm'
    this.pending = new Uint8Array(0); // 还没凑完整的数据
    this.ftyp = null;
    this.init = null;
    this.track = 0; // MP4 音轨 ID
    this.scale = kind === 'webm' ? 1000000 : 0; // MP4：音轨的时间刻度；WebM：TimecodeScale（纳秒）
    this.head = null; // WebM：还在收的初始化段
    this.segments = []; // { start: 毫秒, bytes, done }
    this.bytes = 0;
    this.ok = 0;
    this.failures = 0;
    this.broken = false;
    this.busy = false;
  }

  Feeder.prototype.push = function (data, offset) {
    var bytes = data instanceof ArrayBuffer ? new Uint8Array(data.slice(0))
      : new Uint8Array(data.buffer.slice(data.byteOffset, data.byteOffset + data.byteLength));
    this.pending = concat(this.pending, bytes);
    if (this.kind === 'mp4') this.parseMp4(offset); else this.parseWebm(offset);
    // 留着的数据别太多（音视频混在一起的缓冲区每段都很大）
    while (this.segments.length > 1 && (this.bytes > 48 * 1024 * 1024 || this.segments.length > 300)) {
      var old = this.segments.shift();
      if (old.bytes) this.bytes -= old.bytes.length;
    }
  };

  Feeder.prototype.addSegment = function (start, bytes) {
    if (!this.init || !isFinite(start)) return;
    this.segments.push({ start: start, bytes: bytes, done: false });
    this.bytes += bytes.length;
  };

  Feeder.prototype.parseMp4 = function (offset) {
    var b = this.pending, i = 0, moof = -1;
    while (i + 8 <= b.length) {
      var size = u32(b, i), type = fourcc(b, i + 4);
      if (size === 1) { if (i + 16 > b.length) break; size = u64(b, i + 8); }
      if (size < 8 || i + size > b.length) break;
      if (type === 'ftyp') this.ftyp = b.slice(i, i + size);
      else if (type === 'moov') this.readMoov(b, { body: i + 8, end: i + size }, b.slice(i, i + size));
      else if (type === 'moof') moof = i;
      else if (type === 'mdat' && moof >= 0) { this.addMp4Segment(b.slice(moof, i + size), offset); moof = -1; }
      i += size;
    }
    this.pending = b.slice(moof >= 0 ? moof : i);
  };

  Feeder.prototype.readMoov = function (b, moov, bytes) {
    var traks = boxes(b, moov.body, moov.end);
    for (var k = 0; k < traks.length; k++) {
      if (traks[k].type !== 'trak') continue;
      var mdia = child(b, traks[k], 'mdia');
      var hdlr = mdia && child(b, mdia, 'hdlr');
      if (!hdlr || fourcc(b, hdlr.body + 8) !== 'soun') continue;
      var tkhd = child(b, traks[k], 'tkhd');
      var mdhd = child(b, mdia, 'mdhd');
      if (!tkhd || !mdhd) continue;
      this.track = u32(b, tkhd.body + (b[tkhd.body] === 1 ? 20 : 12));
      this.scale = u32(b, mdhd.body + (b[mdhd.body] === 1 ? 20 : 12));
      this.init = this.ftyp ? concat(this.ftyp, bytes) : bytes;
      return;
    }
  };

  Feeder.prototype.addMp4Segment = function (seg, offset) {
    if (!this.scale) return;
    var moof = boxes(seg, 0, seg.length)[0];
    var trafs = boxes(seg, moof.body, moof.end);
    for (var k = 0; k < trafs.length; k++) {
      if (trafs[k].type !== 'traf') continue;
      var tfhd = child(seg, trafs[k], 'tfhd');
      if (!tfhd || u32(seg, tfhd.body + 4) !== this.track) continue;
      var tfdt = child(seg, trafs[k], 'tfdt');
      if (!tfdt) return;
      var t = seg[tfdt.body] === 1 ? u64(seg, tfdt.body + 4) : u32(seg, tfdt.body + 4);
      this.addSegment(t / this.scale * 1000 + (offset || 0) * 1000, seg);
      return;
    }
  };

  // WebM：EBML 头、Segment 头、Info、Tracks 等是初始化段，每个 Cluster 是一段
  var EBML = 0x1A45DFA3, SEGMENT = 0x18538067, CLUSTER = 0x1F43B675, INFO = 0x1549A966;

  Feeder.prototype.parseWebm = function (offset) {
    var b = this.pending, i = 0;
    while (i < b.length) {
      var id = vint(b, i, true);
      var size = id && vint(b, i + id.len, false);
      if (!size) break;
      var body = i + id.len + size.len;
      if (id.value === SEGMENT) {
        // 只进入 Segment，不整个收下
        this.head = concat(this.head, b.slice(i, body));
        i = body;
        continue;
      }
      var end;
      if (size.unknown) {
        // 长度未知的 Cluster：到下一个 Cluster 为止；还没出现就等更多数据
        end = -1;
        for (var j = body; j + 4 <= b.length; j++) {
          if (b[j] === 0x1F && b[j + 1] === 0x43 && b[j + 2] === 0xB6 && b[j + 3] === 0x75) { end = j; break; }
        }
        if (end < 0) break;
      } else {
        end = body + size.value;
        if (end > b.length) break;
      }
      var el = b.slice(i, end);
      if (id.value === EBML) {
        // 新的初始化段（换清晰度等）
        this.head = el;
        this.init = null;
        this.scale = 1000000;
      } else if (id.value === CLUSTER) {
        if (!this.init) this.init = this.head;
        var tc = vint(b, body, true);
        var tcs = tc && vint(b, body + tc.len, false);
        if (tc && tc.value === 0xE7 && tcs) {
          var at = body + tc.len + tcs.len;
          this.addSegment(uint(b, at, at + tcs.value) * this.scale / 1e6 + (offset || 0) * 1000, el);
        }
      } else if (!this.init) {
        if (id.value === INFO) {
          for (var p = body; p < end;) {
            var cid = vint(b, p, true), cs = cid && vint(b, p + cid.len, false);
            if (!cs) break;
            var cb = p + cid.len + cs.len;
            if (cid.value === 0x2AD7B1) this.scale = uint(b, cb, cb + cs.value);
            p = cb + cs.value;
          }
        }
        this.head = concat(this.head, el);
      }
      i = end;
    }
    this.pending = b.slice(i);
  };

  /** 解码离播放位置最近、还没解过的一段，交给 App */
  Feeder.prototype.decodeNext = function (v) {
    if (this.busy || this.broken || !this.init) return;
    var now = v.currentTime * 1000;
    var seg = null;
    for (var k = 0; k < this.segments.length; k++) {
      var s = this.segments[k];
      if (s.done) continue;
      // 早就播过去的不要了
      if (s.start < now - 15000) { s.done = true; this.bytes -= s.bytes.length; s.bytes = null; continue; }
      if (!seg || s.start < seg.start) seg = s;
    }
    if (!seg) return;
    var self = this;
    seg.done = true;
    this.busy = true;
    // 原始数据留着：换了视频语言要重新识别时还要再解一遍（总量由 push 里的上限管）
    var data = concat(this.init, seg.bytes);
    var start = seg.start;
    var oc = new OfflineAudioContext(1, 1, RATE);
    var done = function () { self.busy = false; };
    var onAudio = function (buf) {
      var n = buf.length, ch = buf.numberOfChannels;
      var out = new Int16Array(n);
      var chans = [];
      for (var c = 0; c < ch; c++) chans.push(buf.getChannelData(c));
      for (var i = 0; i < n; i++) {
        var sum = 0;
        for (var c2 = 0; c2 < ch; c2++) sum += chans[c2][i];
        var x = sum / ch;
        out[i] = x > 1 ? 32767 : x < -1 ? -32768 : x * 32767;
      }
      // 时间明显对不上（例如网页改过时间戳的偏移）：不用这条路
      if (Math.abs(start - now) > 10 * 60 * 1000) { self.broken = true; return done(); }
      self.ok++;
      self.failures = 0;
      try { B.ahead(frameId, toBase64(out), start); } catch (e) {}
      done();
    };
    var onError = function () {
      self.failures++;
      if (self.ok === 0 && self.failures >= 3) self.broken = true;
      done();
    };
    try {
      var p = oc.decodeAudioData(data.buffer, onAudio, onError);
      if (p && p.catch) p.catch(function () {});
    } catch (e) { onError(); }
  };

  /** 当前视频对应的音频 Feeder（视频的地址是 MediaSource 的 blob 地址） */
  function feederFor(v) {
    var ms = sourceOf[v.currentSrc || v.src];
    if (!ms) return null;
    for (var k = 0; k < feeders.length; k++) if (feeders[k].ms === ms && !feeders[k].broken) return feeders[k];
    return null;
  }

  if (MS && window.SourceBuffer && window.OfflineAudioContext) {
    try {
      var create = URL.createObjectURL;
      URL.createObjectURL = function (o) {
        var u = create.apply(this, arguments);
        try { if (o instanceof MS) sourceOf[u] = o; } catch (e) {}
        return u;
      };
      var add = MS.prototype.addSourceBuffer;
      MS.prototype.addSourceBuffer = function (mime) {
        var sb = add.apply(this, arguments);
        try {
          var kind = /webm/i.test(mime) ? 'webm' : /mp4/i.test(mime) ? 'mp4' : null;
          if (kind && (/^audio\//i.test(mime) || /mp4a|opus|vorbis|ac-3|ec-3|flac/i.test(mime))) {
            var f = new Feeder(this, kind);
            feederOf.set(sb, f);
            feeders.push(f);
          }
        } catch (e) {}
        return sb;
      };
      var append = SourceBuffer.prototype.appendBuffer;
      SourceBuffer.prototype.appendBuffer = function (data) {
        var f = feederOf.get(this);
        if (f && !f.broken) {
          try { f.push(data, this.timestampOffset); } catch (e) { f.broken = true; }
        }
        return append.apply(this, arguments);
      };
    } catch (e) {}
  }

  // ---------- 2. 边播边识别：Web Audio 读出正在播放的声音 ----------

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
        if (!enabled || active !== v || v.paused || aheadBusy) { pos = 0; return; }
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

  // ---------- 页面缩放（只管顶层页面） ----------

  var zoomApplied = null;

  function pageTweaks() {
    var z = 100;
    try { z = B.pageZoom(); } catch (e) {}
    var root = document.documentElement;
    // 横屏时很多手机网站按屏幕宽度放大（rem、vw），双指缩不回去：整页按用户选的比例缩放
    if (root && zoomApplied !== z && (z !== 100 || zoomApplied !== null)) {
      root.style.zoom = z === 100 ? '' : String(z / 100);
      zoomApplied = z;
    }
    // 网页禁止了双指缩放（user-scalable=no、maximum-scale=1）：放开
    var m = document.querySelector('meta[name="viewport"]');
    if (m && m.content && /user-scalable\s*=\s*(no|0)|maximum-scale\s*=\s*1(\.0*)?(?![\d.])/i.test(m.content)) {
      m.content = m.content.replace(/,?\s*(user-scalable|maximum-scale|minimum-scale)\s*=\s*[^,]*/gi, '').replace(/^\s*,\s*/, '') +
        ', minimum-scale=0.25, maximum-scale=5, user-scalable=yes';
    }
  }

  /** App 要求从头识别（换了视频语言）的次数：变了就把还留着数据的段重新解码送一遍 */
  var generation = -1;

  function tick() {
    try { enabled = B.enabled(); } catch (e) { return; }
    var g = generation;
    try { g = B.generation(); } catch (e) {}
    if (g !== generation) {
      if (generation !== -1) {
        for (var fi = 0; fi < feeders.length; fi++) {
          var segs = feeders[fi].segments;
          for (var si = 0; si < segs.length; si++) segs[si].done = !segs[si].bytes;
        }
      }
      generation = g;
    }
    if (window === window.top) {
      try { pageTweaks(); } catch (e) {}
    }
    var v = pick();
    if (!v) {
      if (active && (active.paused || !active.isConnected)) {
        report(active, true);
      }
      return;
    }
    active = v;
    var feeder = v.mediaKeys ? null : feederFor(v);
    aheadBusy = !!feeder;
    if (enabled) {
      if (feeder) {
        feeder.decodeNext(v);
      } else {
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
