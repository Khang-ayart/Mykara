/* Mykara engine: vẽ Scene JSON (schema/scene.schema.json) lên canvas 2D tại thời điểm t.
 *
 * Mọi thứ là hàm theo thời gian tMs, không giữ trạng thái giữa các khung, nên tua, preview và export
 * cho cùng một kết quả. Dùng chung cho Preview (chạy trong WebView) và Export (từng khung một).
 *
 * API: Engine.init(canvas) · Engine.setScene(obj) · Engine.ready() · Engine.draw(tMs)
 */
var Engine = (function () {
  'use strict';

  var cv = null, ctx = null, scene = null, W = 0, H = 0;
  var images = {};            // url -> {img, state: 'loading'|'ok'|'fail'}
  var sprites = {};           // key -> canvas 256x256
  var layouts = {};           // text layer id -> bố cục
  var textLayers = {};        // id -> layer (để hình ngữ nghĩa tìm vị trí chữ)
  var pending = 0;
  var yOverride = null;       // vị trí dọc tạm khi người dùng đang kéo lời (0..1), null = theo cảnh
  var SPR = 256;

  // ---------- tiện ích ----------
  function pseudo(i) {
    var x = Math.sin(i * 12.9898) * 43758.5453;
    return (x - Math.floor(x)) * 2 - 1;
  }
  function clamp01(x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }
  function easeOut(x) { x = clamp01(x); return 1 - Math.pow(1 - x, 3); }
  function easeBack(x) {
    x = clamp01(x);
    var c1 = 1.70158, c3 = c1 + 1;
    return 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
  }
  function hashStr(s) {
    var h = 0;
    for (var i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
    return h;
  }

  // ---------- âm thanh phân tích ----------
  function bassAt(t) {
    var a = scene.analysis;
    if (!a || !a.bassEnvelope) return 0;
    var e = a.bassEnvelope, i = Math.floor(t / 1000 * e.hz);
    if (i < 0 || i >= e.values.length) return 0;
    return e.values[i];
  }
  function beatInfo(t) {
    var b = scene.analysis && scene.analysis.beatsMs;
    if (!b || !b.length) return { since: 1e9, interval: 500 };
    var lo = 0, hi = b.length - 1, idx = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (b[mid] <= t) { idx = mid; lo = mid + 1; } else { hi = mid - 1; }
    }
    if (idx < 0) return { since: 1e9, interval: 500 };
    var next = idx + 1 < b.length ? b[idx + 1] : b[idx] + 500;
    return { since: t - b[idx], interval: Math.max(200, next - b[idx]) };
  }
  function beatEnv(t) { return Math.exp(-beatInfo(t).since / 160); }

  // ---------- ảnh ----------
  function loadImage(url) {
    if (!url || images[url]) return images[url];
    var rec = { img: new Image(), state: 'loading' };
    images[url] = rec;
    pending++;
    rec.img.onload = function () { rec.state = 'ok'; pending--; };
    rec.img.onerror = function () { rec.state = 'fail'; pending--; };
    rec.img.src = url;
    return rec;
  }

  // ---------- hình vector (14 hình thử nghiệm, 256x256) ----------
  function lg(c, x0, y0, x1, y1, stops) {
    var g = c.createLinearGradient(x0, y0, x1, y1);
    for (var i = 0; i < stops.length; i++) g.addColorStop(stops[i][0], stops[i][1]);
    return g;
  }
  function cloudPath(c, ox, oy, s) {
    c.beginPath();
    c.arc(ox + 78 * s, oy + 142 * s, 42 * s, Math.PI * 0.5, Math.PI * 1.5);
    c.arc(ox + 120 * s, oy + 95 * s, 52 * s, Math.PI, Math.PI * 2);
    c.arc(ox + 178 * s, oy + 142 * s, 40 * s, Math.PI * 1.5, Math.PI * 0.5);
    c.closePath();
  }
  function drop(c, x, y, s, color) {
    c.beginPath();
    c.moveTo(x, y - 16 * s);
    c.bezierCurveTo(x + 14 * s, y + 2 * s, x + 12 * s, y + 16 * s, x, y + 16 * s);
    c.bezierCurveTo(x - 12 * s, y + 16 * s, x - 14 * s, y + 2 * s, x, y - 16 * s);
    c.fillStyle = color; c.fill();
  }
  var VECTORS = {
    cloud: function (c) {
      cloudPath(c, 0, 10, 1);
      c.fillStyle = lg(c, 0, 50, 0, 190, [[0, '#ffffff'], [1, '#c9d6ea']]); c.fill();
      c.lineWidth = 4; c.strokeStyle = 'rgba(120,140,175,0.5)'; c.stroke();
    },
    rain: function (c) {
      cloudPath(c, 20, -20, 0.8);
      c.fillStyle = lg(c, 0, 20, 0, 150, [[0, '#d5deee'], [1, '#8fa3c2']]); c.fill();
      drop(c, 80, 190, 1.4, '#5fb4ff'); drop(c, 128, 215, 1.4, '#5fb4ff'); drop(c, 176, 190, 1.4, '#5fb4ff');
    },
    wind: function (c) {
      c.lineCap = 'round'; c.lineJoin = 'round'; c.strokeStyle = '#ffffff';
      c.lineWidth = 13;
      c.beginPath(); c.moveTo(28, 92); c.bezierCurveTo(110, 92, 160, 92, 188, 82);
      c.bezierCurveTo(222, 68, 214, 30, 182, 38); c.stroke();
      c.beginPath(); c.moveTo(28, 128); c.bezierCurveTo(120, 128, 190, 128, 214, 138);
      c.bezierCurveTo(244, 152, 232, 192, 198, 184); c.stroke();
      c.lineWidth = 11;
      c.beginPath(); c.moveTo(56, 166); c.bezierCurveTo(100, 166, 126, 166, 142, 176);
      c.bezierCurveTo(166, 192, 150, 222, 126, 214); c.stroke();
    },
    sun: function (c) {
      c.save(); c.translate(128, 128);
      c.strokeStyle = '#ffc933'; c.lineWidth = 13; c.lineCap = 'round';
      for (var k = 0; k < 12; k++) {
        c.rotate(Math.PI / 6);
        c.beginPath(); c.moveTo(0, -74); c.lineTo(0, -108); c.stroke();
      }
      c.beginPath(); c.arc(0, 0, 56, 0, Math.PI * 2);
      c.fillStyle = lg(c, -40, -40, 40, 40, [[0, '#fff176'], [1, '#ff9800']]); c.fill();
      c.restore();
    },
    moon: function (c) {
      c.beginPath();
      c.moveTo(196, 130); c.arc(116, 130, 80, 0, Math.PI * 2);
      c.moveTo(226, 106); c.arc(158, 106, 68, 0, Math.PI * 2);
      c.fillStyle = lg(c, 40, 50, 200, 210, [[0, '#fffbe0'], [1, '#f6d66b']]);
      c.fill('evenodd');
    },
    star: function (c) {
      c.beginPath();
      for (var i = 0; i < 10; i++) {
        var r = i % 2 === 0 ? 100 : 42, a = -Math.PI / 2 + i * Math.PI / 5;
        var x = 128 + r * Math.cos(a), y = 134 + r * Math.sin(a);
        if (i === 0) c.moveTo(x, y); else c.lineTo(x, y);
      }
      c.closePath();
      c.fillStyle = lg(c, 60, 40, 200, 220, [[0, '#fff59d'], [1, '#ffb300']]); c.fill();
      c.lineJoin = 'round'; c.lineWidth = 6; c.strokeStyle = '#ffd54f'; c.stroke();
    },
    snow: function (c) {
      c.save(); c.translate(128, 128);
      c.strokeStyle = '#e8f4ff'; c.lineWidth = 11; c.lineCap = 'round';
      for (var k = 0; k < 6; k++) {
        c.rotate(Math.PI / 3);
        c.beginPath(); c.moveTo(0, 0); c.lineTo(0, -102);
        c.moveTo(0, -62); c.lineTo(-24, -86); c.moveTo(0, -62); c.lineTo(24, -86);
        c.moveTo(0, -36); c.lineTo(-16, -52); c.moveTo(0, -36); c.lineTo(16, -52);
        c.stroke();
      }
      c.restore();
    },
    fire: function (c) {
      function flame(s, ox, oy, g) {
        c.beginPath();
        c.moveTo(ox + 128 * s, oy + 18 * s);
        c.bezierCurveTo(ox + 150 * s, oy + 70 * s, ox + 205 * s, oy + 100 * s, ox + 205 * s, oy + 160 * s);
        c.bezierCurveTo(ox + 205 * s, oy + 215 * s, ox + 168 * s, oy + 240 * s, ox + 128 * s, oy + 240 * s);
        c.bezierCurveTo(ox + 88 * s, oy + 240 * s, ox + 51 * s, oy + 215 * s, ox + 51 * s, oy + 160 * s);
        c.bezierCurveTo(ox + 51 * s, oy + 125 * s, ox + 75 * s, oy + 105 * s, ox + 92 * s, oy + 80 * s);
        c.bezierCurveTo(ox + 100 * s, oy + 100 * s, ox + 108 * s, oy + 108 * s, ox + 118 * s, oy + 110 * s);
        c.bezierCurveTo(ox + 108 * s, oy + 80 * s, ox + 110 * s, oy + 45 * s, ox + 128 * s, oy + 18 * s);
        c.fillStyle = g; c.fill();
      }
      flame(1, 0, 0, lg(c, 0, 240, 0, 20, [[0, '#ffb300'], [0.6, '#ff6d00'], [1, '#e53935']]));
      flame(0.55, 57, 108, lg(c, 0, 240, 0, 110, [[0, '#fff59d'], [1, '#ffca28']]));
    },
    flower: function (c) {
      c.save(); c.translate(128, 128);
      c.fillStyle = lg(c, 0, -100, 0, 0, [[0, '#ffc2d6'], [1, '#ff7aa2']]);
      for (var k = 0; k < 5; k++) {
        c.rotate(Math.PI * 2 / 5);
        c.beginPath(); c.ellipse(0, -58, 32, 50, 0, 0, Math.PI * 2); c.fill();
      }
      c.beginPath(); c.arc(0, 0, 28, 0, Math.PI * 2); c.fillStyle = '#ffd54f'; c.fill();
      c.restore();
    },
    leaf: function (c) {
      c.beginPath();
      c.moveTo(36, 220); c.bezierCurveTo(26, 100, 110, 28, 222, 36);
      c.bezierCurveTo(230, 142, 160, 234, 36, 220);
      c.fillStyle = lg(c, 40, 220, 220, 40, [[0, '#2e7d32'], [1, '#9ccc65']]); c.fill();
      c.lineCap = 'round'; c.lineWidth = 6; c.strokeStyle = 'rgba(27,94,32,0.75)';
      c.beginPath(); c.moveTo(36, 220); c.lineTo(186, 72); c.stroke();
    },
    heart: function (c) {
      c.beginPath();
      c.moveTo(128, 224);
      c.bezierCurveTo(30, 152, 22, 82, 70, 52);
      c.bezierCurveTo(100, 34, 125, 50, 128, 78);
      c.bezierCurveTo(131, 50, 156, 34, 186, 52);
      c.bezierCurveTo(234, 82, 226, 152, 128, 224);
      c.fillStyle = lg(c, 60, 40, 200, 220, [[0, '#ff6b81'], [1, '#d81b45']]); c.fill();
      c.beginPath(); c.ellipse(86, 84, 22, 12, -0.6, 0, Math.PI * 2);
      c.fillStyle = 'rgba(255,255,255,0.38)'; c.fill();
    },
    wave: function (c) {
      function w(off, amp, g) {
        c.beginPath(); c.moveTo(8, 240);
        for (var x = 8; x <= 248; x += 4) c.lineTo(x, 130 + off + amp * Math.sin((x - 8) / 240 * Math.PI * 2 * 1.5));
        c.lineTo(248, 240); c.closePath(); c.fillStyle = g; c.fill();
      }
      w(0, 24, lg(c, 0, 100, 0, 240, [[0, '#81d4fa'], [1, '#0288d1']]));
      w(34, 18, lg(c, 0, 140, 0, 240, [[0, '#4fc3f7'], [1, '#01579b']]));
    },
    bird: function (c) {
      c.beginPath();
      c.moveTo(24, 118); c.quadraticCurveTo(80, 52, 128, 124);
      c.quadraticCurveTo(176, 52, 232, 118);
      c.quadraticCurveTo(180, 108, 128, 160);
      c.quadraticCurveTo(76, 108, 24, 118);
      c.fillStyle = '#ffffff'; c.fill();
      c.lineWidth = 5; c.strokeStyle = 'rgba(90,110,140,0.55)'; c.lineJoin = 'round'; c.stroke();
    },
    lightning: function (c) {
      c.beginPath();
      c.moveTo(152, 10); c.lineTo(62, 140); c.lineTo(120, 140); c.lineTo(98, 246); c.lineTo(202, 104); c.lineTo(142, 104);
      c.closePath();
      c.fillStyle = lg(c, 100, 10, 160, 246, [[0, '#fff59d'], [1, '#ffab00']]); c.fill();
      c.lineJoin = 'round'; c.lineWidth = 5; c.strokeStyle = '#ffd54f'; c.stroke();
    }
  };

  function getSprite(v) {
    var key = v.kind + ':' + v.ref;
    if (sprites[key]) return sprites[key];
    if (v.kind === 'userImage') {
      var rec = loadImage(v.ref);
      if (!rec || rec.state !== 'ok') return null;
      var o = document.createElement('canvas'); o.width = SPR; o.height = SPR;
      var oc = o.getContext('2d'), iw = rec.img.naturalWidth, ih = rec.img.naturalHeight, s = Math.min(SPR / iw, SPR / ih);
      oc.drawImage(rec.img, (SPR - iw * s) / 2, (SPR - ih * s) / 2, iw * s, ih * s);
      sprites[key] = o; return o;
    }
    var cvs = document.createElement('canvas'); cvs.width = SPR; cvs.height = SPR;
    var c = cvs.getContext('2d');
    if (v.kind === 'vector') {
      var f = VECTORS[v.ref];
      if (f) f(c); else { c.font = '180px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle'; c.fillText('?', 128, 132); }
    } else { // emoji
      c.font = '200px sans-serif'; c.textAlign = 'center'; c.textBaseline = 'middle';
      c.fillText(v.ref, 128, 132);
    }
    sprites[key] = cvs;
    return cvs;
  }

  // ---------- lớp ảnh nền ----------
  function fitImage(rec, p, iw, ih) {
    var maxZoom = 1 + (p.kenBurns ? 0.08 : 0) + (p.reactZoom ? Math.abs(p.reactZoom) : 0);
    var key = W + 'x' + H + '|' + maxZoom;
    if (rec.fitKey === key) return rec.fit;
    var ts = Math.max(W / iw, H / ih) * maxZoom * 1.05;
    rec.fitKey = key;
    if (ts >= 0.8) { rec.fit = rec.img; return rec.fit; }
    var c = document.createElement('canvas');
    c.width = Math.max(1, Math.ceil(iw * ts)); c.height = Math.max(1, Math.ceil(ih * ts));
    var cx = c.getContext('2d');
    cx.imageSmoothingEnabled = true; cx.imageSmoothingQuality = 'high';
    cx.drawImage(rec.img, 0, 0, c.width, c.height);
    rec.fit = c;
    return c;
  }

  function drawImageLayer(L, t) {
    var rec = images[L.source];
    if (!rec || rec.state !== 'ok') return;
    var p = L.params || {};
    var fade = p.fadeMs || 0, a = 1;
    if (fade > 0) {
      var fin = L.fromMs <= 0 ? 1 : clamp01((t - L.fromMs) / fade);
      var fout = L.toMs >= scene.durationMs ? 1 : clamp01((L.toMs - t) / fade);
      a = Math.min(fin, fout);
    }
    var iw = rec.img.naturalWidth, ih = rec.img.naturalHeight;
    var prog = clamp01((t - L.fromMs) / Math.max(1, L.toMs - L.fromMs));
    var zoom = 1 + (p.kenBurns ? 0.08 * prog : 0) + (p.reactZoom ? p.reactZoom * bassAt(t) : 0);
    var s = Math.max(W / iw, H / ih) * zoom;
    var dir = (hashStr(L.id) & 1) ? 1 : -1;
    var dx = p.kenBurns ? dir * (prog - 0.5) * 0.04 * W : 0;
    ctx.globalAlpha = a;
    // Ảnh chụp thường lớn hơn khung rất nhiều: thu nhỏ một lần về cỡ cần dùng rồi vẽ bản đó mỗi khung.
    ctx.drawImage(fitImage(rec, p, iw, ih), W / 2 - iw * s / 2 + dx, H / 2 - ih * s / 2, iw * s, ih * s);
    ctx.globalAlpha = 1;
  }

  // ---------- lớp phủ ----------
  function drawOverlay(L, t) {
    var p = L.params || {}, k = L.source;
    if (k === 'gradient') {
      var ang = t / 20000;
      var cx = W / 2, cy = H / 2, r = Math.hypot(W, H) / 2;
      var g = ctx.createLinearGradient(cx - Math.cos(ang) * r, cy - Math.sin(ang) * r, cx + Math.cos(ang) * r, cy + Math.sin(ang) * r);
      g.addColorStop(0, p.c1 || '#1b1f3b'); g.addColorStop(1, p.c2 || '#7b4aa8');
      ctx.fillStyle = g; ctx.fillRect(0, 0, W, H);
    } else if (k === 'vignette') {
      var vg = ctx.createRadialGradient(W / 2, H / 2, Math.min(W, H) * 0.3, W / 2, H / 2, Math.hypot(W, H) * 0.55);
      vg.addColorStop(0, 'rgba(0,0,0,0)'); vg.addColorStop(1, 'rgba(0,0,0,' + (p.strength == null ? 0.5 : p.strength) + ')');
      ctx.fillStyle = vg; ctx.fillRect(0, 0, W, H);
    } else if (k === 'tint') {
      ctx.globalAlpha = p.alpha == null ? 0.2 : p.alpha; ctx.fillStyle = p.color || '#7a5cff'; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1;
    } else if (k === 'darken') {
      ctx.fillStyle = 'rgba(0,0,0,' + (p.alpha == null ? 0.3 : p.alpha) + ')'; ctx.fillRect(0, 0, W, H);
    } else if (k === 'letterbox') {
      var bh = H * (p.frac || 0.1) * easeOut(t / 1200);
      ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, bh); ctx.fillRect(0, H - bh, W, bh);
    }
  }

  // ---------- hạt nền ----------
  function drawParticles(L, t) {
    var p = L.params || {}, n = Math.min(p.count || 40, 120), kind = L.source, ts = t / 1000;
    for (var i = 0; i < n; i++) {
      var rx = pseudo(i * 5 + 1) * 0.5 + 0.5, ry = pseudo(i * 5 + 2) * 0.5 + 0.5;
      var sp = 0.02 + 0.05 * (pseudo(i * 5 + 3) * 0.5 + 0.5), sz = (0.004 + 0.008 * (pseudo(i * 5 + 4) * 0.5 + 0.5)) * H;
      var x, y, a;
      if (kind === 'snow') {
        y = ((ry + ts * sp * 2) % 1) * H; x = rx * W + Math.sin(ts * 0.8 + i) * 18; a = 0.8;
        sz *= 1.3;
      } else if (kind === 'embers') {
        y = H - ((ry + ts * sp * 2) % 1) * H; x = rx * W + Math.sin(ts + i) * 22; a = 0.8 * (y / H);
      } else { // dust
        y = ((ry + ts * sp) % 1) * H; x = (rx * W + Math.sin(ts * 0.5 + i) * 30 + W) % W;
        a = 0.25 + 0.5 * (0.5 + 0.5 * Math.sin(ts * 1.5 + i * 1.7));
      }
      ctx.globalAlpha = a;
      ctx.fillStyle = kind === 'embers' ? '#ffb74d' : '#ffffff';
      ctx.beginPath(); ctx.arc(x, y, sz, 0, Math.PI * 2); ctx.fill();
    }
    ctx.globalAlpha = 1;
  }

  // ---------- phản ứng theo nhạc ----------
  function drawReactive(L, t) {
    var p = L.params || {}, k = L.source, I = p.intensity == null ? 0.6 : p.intensity;
    if (k === 'beatPulse') {
      var b = beatInfo(t), ph = clamp01(b.since / Math.min(b.interval, 600));
      ctx.strokeStyle = p.color || '#ffffff'; ctx.lineWidth = H * 0.006;
      ctx.globalAlpha = (1 - ph) * I;
      ctx.beginPath(); ctx.arc(W / 2, H / 2, H * (0.16 + 0.3 * ph), 0, Math.PI * 2); ctx.stroke();
      ctx.globalAlpha = 1;
    } else if (k === 'bassReaction') {
      ctx.fillStyle = 'rgba(255,255,255,' + (bassAt(t) * 0.14 * I).toFixed(3) + ')'; ctx.fillRect(0, 0, W, H);
    } else if (k === 'backgroundReaction') {
      ctx.globalAlpha = clamp01(bassAt(t) * 0.3 * I); ctx.fillStyle = p.color || '#ff80ab'; ctx.fillRect(0, 0, W, H); ctx.globalAlpha = 1;
    } else if (k === 'bars') {
      var n = 40, bw = W * 0.8 / n, x0 = W * 0.1, base = H * 0.96;
      ctx.fillStyle = p.color || 'rgba(255,255,255,0.7)';
      for (var i = 0; i < n; i++) {
        var v = Math.max(bassAt(t - i * 55), bassAt(t - i * 55 - 30) * 0.8);
        var h = H * 0.12 * (0.12 + v) * (0.75 + 0.25 * Math.abs(pseudo(i + 3)));
        ctx.fillRect(x0 + i * bw + bw * 0.15, base - h, bw * 0.7, h);
      }
    }
  }

  // ---------- chữ ----------
  function famOf(f) {
    f = f || 'sans-serif';
    return (/\s/.test(f) && !/["',]/.test(f)) ? '"' + f + '"' : f;
  }
  function fontOf(s) { return (s.weight || 'normal') + ' ' + s.fontSize + 'px ' + famOf(s.font); }

  // Bố cục "cắt dán": mỗi từ một cỡ, một góc nghiêng, xếp lệch nhau. Sinh ngẫu nhiên có hạt giống theo id câu,
  // nên cùng một câu luôn ra cùng một bố cục (xem trước và xuất video giống nhau).
  function layoutCollage(L, s, fs, toks, hidden) {
    var seed = hashStr(L.id) + ((L.params && L.params.seed) || 0) * 977, n = 0;
    function rnd() { n++; return (pseudo(seed + n * 13) + 1) / 2; }
    var accent = {};
    ((L.params && L.params.accent) || []).forEach(function (i) { accent[i] = true; });
    var longest = -1;
    toks.forEach(function (tk, i) { if (!hidden[i] && (longest < 0 || tk.length > toks[longest].length)) longest = i; });
    var maxW = Math.min(s.maxWidth || W * 0.86, W * 0.5), sizes = [], widths = [];
    toks.forEach(function (tk, i) {
      var m = 1.0 + 0.3 * rnd();
      if (accent[i] || i === longest) m = 1.7 + 0.3 * rnd();
      sizes[i] = fs * m;
      ctx.font = fontOf({ weight: s.weight, fontSize: sizes[i], font: s.font });
      widths[i] = hidden[i] ? fs * 1.9 : ctx.measureText(tk).width;
    });
    var gap = fs * 0.25, rows = [], cur = { idx: [], w: 0 };
    toks.forEach(function (tk, i) {
      var add = widths[i] + (cur.idx.length ? gap : 0);
      if (cur.idx.length && cur.w + add > maxW) { rows.push(cur); cur = { idx: [], w: 0 }; add = widths[i]; }
      cur.idx.push(i); cur.w += add;
    });
    if (cur.idx.length) rows.push(cur);
    var pitch = fs * 1.05, rects = [], tilt = 0;
    rows.forEach(function (row, ri) {
      var x = -row.w / 2 + (rnd() - 0.5) * fs * 0.8;
      var cy = (ri - (rows.length - 1) / 2) * pitch;
      row.idx.forEach(function (ti) {
        rects[ti] = { x: x, y: cy + (rnd() - 0.5) * fs * 0.3, w: widths[ti], h: sizes[ti] * 1.2,
                      px: sizes[ti], rot: (rnd() - 0.5) * 0.24, accent: !!accent[ti] };
        x += widths[ti] + gap;
      });
    });
    var res = { toks: toks, rects: rects, hidden: hidden, fs: fs, totalH: rows.length * pitch, nonHiddenChars: 0, collage: true };
    toks.forEach(function (tk, i) { if (!hidden[i]) res.nonHiddenChars += tk.length; });
    return res;
  }

  // Chữ xếp vòng tròn quanh một từ nhấn ở giữa (kiểu "tình yêu" trong bản Lyric Kara gốc).
  function layoutRing(L, s, fs, toks, hidden) {
    var accent = {};
    ((L.params && L.params.accent) || []).forEach(function (i) { accent[i] = true; });
    var rfs = fs * 0.62, widths = [], total = 0, gap = fs * 0.62 * 0.35, rim = [];
    ctx.font = fontOf({ weight: s.weight, fontSize: rfs, font: s.font });
    toks.forEach(function (tk, i) {
      if (accent[i] || hidden[i]) { widths[i] = 0; return; }
      widths[i] = ctx.measureText(tk).width; rim.push(i); total += widths[i] + gap;
    });
    // chữ ngắn thì phóng to cho vừa một vòng, chữ dài thì mở rộng bán kính
    var R0 = H * 0.24, k = total > 0 ? Math.max(0.75, Math.min(1.6, (Math.PI * 2 * R0 * 0.95) / total)) : 1;
    rfs *= k; gap *= k; total *= k;
    for (var wi = 0; wi < widths.length; wi++) widths[wi] *= k;
    var R = Math.max(R0, total / (Math.PI * 2) * 1.04);
    var rects = [], ang = -Math.PI / 2 - Math.PI * 0.5; // bắt đầu từ vị trí 9 giờ, đi theo chiều kim đồng hồ
    rim.forEach(function (i) {
      var chars = [], a = ang;
      ctx.font = fontOf({ weight: s.weight, fontSize: rfs, font: s.font });
      for (var k = 0; k < toks[i].length; k++) {
        var cw = ctx.measureText(toks[i][k]).width;
        chars.push({ ch: toks[i][k], a: a + (cw / 2) / R });
        a += cw / R;
      }
      var mid = (ang + a) / 2;
      rects[i] = { x: R * Math.cos(mid) - widths[i] / 2, y: R * Math.sin(mid), w: widths[i], h: rfs * 1.2, chars: chars, ring: true };
      ang = a + gap / R;
    });
    toks.forEach(function (tk, i) {
      if (!accent[i] || hidden[i]) return;
      var px = fs * 1.5; ctx.font = fontOf({ weight: s.weight, fontSize: px, font: s.font });
      var w = ctx.measureText(tk).width;
      rects[i] = { x: -w / 2, y: 0, w: w, h: px * 1.2, px: px, center: true, accent: true };
    });
    var res = { toks: toks, rects: rects, hidden: hidden, fs: fs, rfs: rfs, R: R, totalH: R * 2, nonHiddenChars: 0, ring: true };
    toks.forEach(function (tk, i) { if (!hidden[i]) res.nonHiddenChars += tk.length; });
    return res;
  }

  function layoutText(L) {
    var key = L.id + '|' + W + 'x' + H;
    if (layouts[key]) return layouts[key];
    var s = L.style || {}, fs = s.fontSize || 48;
    ctx.font = fontOf({ weight: s.weight, fontSize: fs, font: s.font });
    var toks = L.words ? L.words.map(function (w) { return w.text; }) : (L.text || '').split(/\s+/).filter(Boolean);
    var hidden = {};
    (L.hideText || []).forEach(function (i) { hidden[i] = true; });
    if (L.params && L.params.layout === 'ring') {
      var rl = layoutRing(L, s, fs, toks, hidden);
      layouts[key] = rl;
      return rl;
    }
    if (L.params && L.params.layout === 'collage') {
      var cl = layoutCollage(L, s, fs, toks, hidden);
      layouts[key] = cl;
      return cl;
    }
    var space = ctx.measureText(' ').width, maxW = s.maxWidth || W * 0.86;
    var widths = toks.map(function (tk, i) { return hidden[i] ? fs * 1.9 : ctx.measureText(tk).width; });
    var lines = [], cur = { idx: [], w: 0 };
    for (var i = 0; i < toks.length; i++) {
      var add = widths[i] + (cur.idx.length ? space : 0);
      if (cur.idx.length && cur.w + add > maxW) { lines.push(cur); cur = { idx: [], w: 0 }; add = widths[i]; }
      cur.idx.push(i); cur.w += add;
    }
    if (cur.idx.length) lines.push(cur);
    var lh = fs * (s.lineHeight || 1.25), totalH = lines.length * lh, rects = [];
    lines.forEach(function (ln, li) {
      var x = -ln.w / 2, cy = li * lh - totalH / 2 + lh / 2;
      ln.idx.forEach(function (ti) {
        rects[ti] = { x: x, y: cy, w: widths[ti], h: lh };
        x += widths[ti] + space;
      });
    });
    var res = { toks: toks, rects: rects, hidden: hidden, fs: fs, totalH: totalH, nonHiddenChars: 0 };
    toks.forEach(function (tk, i) { if (!hidden[i]) res.nonHiddenChars += tk.length; });
    layouts[key] = res;
    return res;
  }

  function findAnim(L, phase) {
    var a = L.animations || [];
    for (var i = 0; i < a.length; i++) if (a[i].phase === phase) return a[i];
    return null;
  }

  var off = null, offCtx = null;

  // Vẽ thân chữ (mọi từ) bằng ctx hiện hành, đã tịnh tiến tới tâm khối chữ.
  function paintWords(L, lay, t, st, shadowBlur) {
    var s = L.style || {}, fs = lay.fs;
    var total = lay.nonHiddenChars, shown = 0, limit = st.reveal >= 1 ? 1e9 : Math.floor(total * st.reveal);
    var karaoke = L.params && L.params.karaoke, bounce = L.params && L.params.bounce;
    var emphSet = {};
    if (!lay.collage && !lay.ring) ((L.params && L.params.accent) || []).forEach(function (i) { emphSet[i] = true; });
    ctx.font = fontOf({ weight: s.weight, fontSize: fs, font: s.font });
    ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    ctx.shadowColor = s.shadowColor || 'rgba(0,0,0,0.6)'; ctx.shadowBlur = shadowBlur;
    for (var i = 0; i < lay.toks.length; i++) {
      if (lay.hidden[i]) continue;
      var r = lay.rects[i], txt = lay.toks[i];
      if (shown >= limit) break;
      if (shown + txt.length > limit) txt = txt.substring(0, limit - shown);
      shown += lay.toks[i].length;
      if (lay.ring) {
        var wr = L.words && L.words[i], pr = 1;
        if (wr) pr = clamp01((t - (wr.startMs - 150)) / 300);
        if (pr <= 0) continue;
        var er = easeOut(pr), spin = (t - L.fromMs) / 9000 * 0.35;
        ctx.save();
        ctx.globalAlpha *= er;
        if (r.center) {
          ctx.font = fontOf({ weight: s.weight, fontSize: r.px, font: s.font });
          ctx.fillStyle = s.highlightColor || '#ff4b5c'; ctx.shadowColor = s.highlightColor || '#ff4b5c';
          ctx.textAlign = 'center'; ctx.translate(0, 0); var cs = 0.8 + 0.2 * er; ctx.scale(cs, cs);
          ctx.fillText(txt, 0, 0);
        } else {
          ctx.rotate(spin);
          ctx.font = fontOf({ weight: s.weight, fontSize: lay.rfs, font: s.font });
          ctx.textAlign = 'center'; ctx.fillStyle = s.color || '#fff';
          var shownChars = Math.min(r.chars.length, txt.length);
          for (var ci = 0; ci < shownChars; ci++) {
            var ch = r.chars[ci];
            ctx.save();
            ctx.translate(lay.R * Math.cos(ch.a), lay.R * Math.sin(ch.a));
            ctx.rotate(ch.a + Math.PI / 2);
            ctx.fillText(ch.ch, 0, 0);
            ctx.restore();
          }
        }
        ctx.restore();
        continue;
      }
      if (lay.collage) {
        // mỗi từ hiện đúng lúc được hát: trượt nhẹ lên và rõ dần
        var w = L.words && L.words[i], p = 1;
        if (w) p = clamp01((t - (w.startMs - 150)) / 300);
        if (p <= 0) continue;
        var ep = easeOut(p);
        ctx.save();
        ctx.globalAlpha *= ep;
        ctx.translate(r.x + r.w / 2, r.y + (1 - ep) * fs * 0.25);
        ctx.rotate(r.rot);
        var sc = 0.85 + 0.15 * ep;
        ctx.scale(sc, sc);
        ctx.font = fontOf({ weight: s.weight, fontSize: r.px, font: s.font });
        ctx.textAlign = 'center';
        ctx.fillStyle = r.accent ? (s.highlightColor || '#ff4b5c') : (s.color || '#fff');
        if (r.accent) ctx.shadowColor = s.highlightColor || '#ff4b5c';
        ctx.fillText(txt, 0, 0);
        ctx.restore();
        continue;
      }
      // Kiểu karaoke / sub kara: viền chữ, từ đang hát nảy lên, tô màu theo tiến độ hát.
      var w2 = L.words && L.words[i], p2 = -1, hop = 0, gs = 1;
      if (w2) p2 = (t - w2.startMs) / Math.max(1, w2.endMs - w2.startMs);
      if (bounce && p2 > 0 && p2 < 1) {
        var bs = Math.sin(Math.PI * p2);
        hop = -bs * fs * 0.2; gs = 1 + 0.14 * bs;
      }
      ctx.save();
      if (hop || gs !== 1) { ctx.translate(r.x + r.w / 2, r.y + hop); ctx.scale(gs, gs); ctx.translate(-(r.x + r.w / 2), -r.y); }
      var isEmph = !!emphSet[i], ecol = s.emphasisColor || s.highlightColor || '#ffd54f';
      if (isEmph) { // từ người dùng chọn nhấn: to hơn một chút, màu riêng, có quầng sáng
        var ec = r.x + r.w / 2; ctx.translate(ec, r.y); ctx.scale(1.12, 1.12); ctx.translate(-ec, -r.y);
      }
      if (s.strokeColor) {
        ctx.save(); ctx.shadowBlur = 0; ctx.lineJoin = 'round'; ctx.miterLimit = 2;
        ctx.lineWidth = fs * 0.16; ctx.strokeStyle = s.strokeColor; ctx.strokeText(txt, r.x, r.y);
        ctx.restore();
      }
      if (isEmph) { ctx.shadowColor = ecol; ctx.shadowBlur = fs * 0.35; }
      ctx.fillStyle = isEmph ? ecol : (s.color || '#fff');
      ctx.fillText(txt, r.x, r.y);
      if (karaoke && w2 && !isEmph) {
        var pc = clamp01(p2);
        if (pc > 0) {
          ctx.save(); ctx.beginPath(); ctx.rect(r.x - 2, r.y - r.h, r.w * pc + 2, r.h * 2); ctx.clip();
          ctx.shadowBlur = fs * 0.25; ctx.shadowColor = s.highlightColor || '#ffd54f';
          ctx.fillStyle = s.highlightColor || '#ffd54f'; ctx.fillText(txt, r.x, r.y);
          ctx.restore();
        }
      }
      ctx.restore();
    }
    // Biểu tượng hai bên câu (karaoke): hiện nảy khi câu bắt đầu, lơ lửng nhẹ.
    var ic = L.params && L.params.icons;
    if (ic && ic.length && !lay.collage && !lay.ring) {
      var x0 = 1e9, x1 = -1e9, yTop = 1e9, yBot = -1e9;
      for (var q = 0; q < lay.rects.length; q++) {
        var rq = lay.rects[q]; if (!rq || lay.hidden[q]) continue;
        x0 = Math.min(x0, rq.x); x1 = Math.max(x1, rq.x + rq.w); yTop = Math.min(yTop, rq.y); yBot = Math.max(yBot, rq.y);
      }
      if (x0 <= x1) {
        var ip = clamp01((t - L.fromMs) / 450), ie = easeBack(ip), isz = fs * 0.95, yc = (yTop + yBot) / 2;
        var fl = Math.sin((t - L.fromMs) / 420) * fs * 0.08;
        ctx.save();
        ctx.shadowBlur = 0; ctx.globalAlpha *= ip; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
        ctx.font = Math.round(isz) + 'px sans-serif';
        var gap = isz * 0.75;
        ctx.save(); ctx.translate(x0 - gap, yc + fl); ctx.scale(ie, ie); ctx.fillText(ic[0], 0, 0); ctx.restore();
        ctx.save(); ctx.translate(x1 + gap, yc - fl); ctx.scale(ie, ie); ctx.fillText(ic[ic.length > 1 ? 1 : 0], 0, 0); ctx.restore();
        ctx.restore();
      }
    }
  }

  // ---------- trang trí hoa lá quanh lời ----------
  function orn5(cx, cy, r, petal, core, rot) {
    ctx.save(); ctx.translate(cx, cy); ctx.rotate(rot);
    ctx.fillStyle = petal;
    for (var i = 0; i < 5; i++) {
      ctx.save(); ctx.rotate(i * Math.PI * 2 / 5);
      ctx.beginPath(); ctx.ellipse(0, -r * 0.62, r * 0.42, r * 0.62, 0, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    }
    ctx.fillStyle = core; ctx.beginPath(); ctx.arc(0, 0, r * 0.3, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
  function ornLeaf(x, y, len, ang, col) {
    ctx.save(); ctx.translate(x, y); ctx.rotate(ang); ctx.fillStyle = col;
    ctx.beginPath(); ctx.moveTo(0, 0);
    ctx.quadraticCurveTo(len * 0.5, -len * 0.38, len, 0);
    ctx.quadraticCurveTo(len * 0.5, len * 0.38, 0, 0); ctx.fill();
    ctx.strokeStyle = 'rgba(255,255,255,0.35)'; ctx.lineWidth = Math.max(1, len * 0.04);
    ctx.beginPath(); ctx.moveTo(len * 0.1, 0); ctx.lineTo(len * 0.9, 0); ctx.stroke();
    ctx.restore();
  }
  function ornStar(x, y, r, col, a) {
    if (r <= 0.5 || a <= 0) return;
    ctx.save(); ctx.globalAlpha *= a; ctx.fillStyle = col; ctx.translate(x, y);
    ctx.beginPath();
    for (var i = 0; i < 8; i++) { var rr = (i % 2 ? r * 0.28 : r), an = i * Math.PI / 4 - Math.PI / 2; ctx.lineTo(Math.cos(an) * rr, Math.sin(an) * rr); }
    ctx.closePath(); ctx.fill(); ctx.restore();
  }
  // Đường uốn từ giữa ra hai bên: nét mảnh có đuôi cuộn nhỏ.
  function ornVine(hw, amp, col, lw) {
    ctx.strokeStyle = col; ctx.lineWidth = lw; ctx.lineCap = 'round';
    for (var sgn = -1; sgn <= 1; sgn += 2) {
      ctx.beginPath(); ctx.moveTo(sgn * hw * 0.08, 0);
      ctx.bezierCurveTo(sgn * hw * 0.3, -amp, sgn * hw * 0.5, amp, sgn * hw * 0.72, 0);
      ctx.bezierCurveTo(sgn * hw * 0.86, -amp * 0.8, sgn * hw * 0.98, -amp * 0.2, sgn * hw * 0.93, amp * 0.5);
      ctx.stroke();
    }
  }
  function drawOrnament(L, lay, t, s, cyAbs) {
    var orn = L.params && L.params.ornament;
    if (!orn || lay.collage || lay.ring) return;
    var fs = lay.fs, x0 = 1e9, x1 = -1e9;
    for (var q = 0; q < lay.rects.length; q++) { var rq = lay.rects[q]; if (!rq || lay.hidden[q]) continue; x0 = Math.min(x0, rq.x); x1 = Math.max(x1, rq.x + rq.w); }
    if (x0 > x1) return;
    var hw = Math.min(Math.max(x1 - x0, fs * 4) * 0.55, W * 0.42);
    var p = easeOut(clamp01((t - L.fromMs) / 700));
    if (p <= 0) return;
    var below = lay.totalH / 2 + fs * 0.62, up = (cyAbs + below + fs * 0.4) > H * 0.95;
    var y = up ? -lay.totalH / 2 - fs * 0.62 : below;
    var hl = s.highlightColor || '#ff8fb1', line = s.color || '#ffffff';
    var tt = t / 1000;
    ctx.save();
    ctx.translate(0, y); ctx.scale(p, p);
    ctx.globalAlpha *= p; ctx.shadowBlur = 0; ctx.shadowColor = 'transparent';
    if (orn === 'hoa') {
      ornVine(hw, fs * 0.22, 'rgba(255,255,255,0.85)', Math.max(2, fs * 0.05));
      for (var sg = -1; sg <= 1; sg += 2) {
        ornLeaf(sg * hw * 0.3, -fs * 0.03, fs * 0.5, sg > 0 ? -0.6 : Math.PI + 0.6, '#7ed957');
        ornLeaf(sg * hw * 0.55, fs * 0.05, fs * 0.45, sg > 0 ? 0.7 : Math.PI - 0.7, '#4caf50');
        orn5(sg * hw * 0.93, fs * 0.1, fs * 0.3, hl, '#ffd54f', tt * 0.3 * sg);
      }
      orn5(0, 0, fs * 0.5, hl, '#ffd54f', Math.sin(tt * 1.4) * 0.15);
    } else if (orn === 'la') {
      ornVine(hw, fs * 0.16, '#9be58f', Math.max(2, fs * 0.06));
      for (var k = 0; k < 5; k++) for (var sg2 = -1; sg2 <= 1; sg2 += 2) {
        var lx = sg2 * hw * (0.14 + k * 0.17), up2 = (k % 2) ? 1 : -1;
        ornLeaf(lx, 0, fs * (0.62 - k * 0.07), (sg2 > 0 ? 0 : Math.PI) + up2 * 0.85 * sg2, k % 2 ? '#4caf50' : '#7ed957');
      }
      ornLeaf(0, 0, fs * 0.55, -Math.PI / 2, '#a5ee98');
    } else if (orn === 'sao') {
      ctx.strokeStyle = 'rgba(255,255,255,0.7)'; ctx.lineWidth = Math.max(1.5, fs * 0.03); ctx.lineCap = 'round';
      ctx.beginPath(); ctx.moveTo(-hw * 0.9, 0); ctx.lineTo(-hw * 0.12, 0); ctx.moveTo(hw * 0.12, 0); ctx.lineTo(hw * 0.9, 0); ctx.stroke();
      ornStar(0, 0, fs * 0.5 * (0.85 + 0.15 * Math.sin(tt * 3)), '#ffffff', 1);
      for (var m = 0; m < 6; m++) {
        var sx = (m - 2.5) * hw * 0.36, sy = ((m * 37) % 5 - 2) * fs * 0.16 + (m % 2 ? -fs * 0.3 : fs * 0.3);
        ornStar(sx, sy, fs * (0.2 + (m % 3) * 0.08), m % 2 ? hl : '#ffd54f', 0.55 + 0.45 * Math.sin(tt * 2.5 + m * 1.7));
      }
    }
    ctx.restore();
  }

  // Cắt dải: chữ bị xé thành các dải ngang lệch nhau rồi ghép lại (hoặc ngược lại khi ra),
  // kèm bóng mờ chuyển động. p = 0 là tan hoàn toàn, 1 là nguyên vẹn.
  function blitSlices(src, p, t, seed) {
    var n = 22, h = H / n, e = easeOut(p), inv = 1 - e;
    var frame = Math.floor(t / 50);
    for (var i = 0; i < n; i++) {
      var r = pseudo(seed + i * 7 + frame * 0.37);
      var dx = r * inv * W * 0.45;
      var a = clamp01(p * 1.7 - 0.35 * (pseudo(seed + i * 3) + 1) / 2);
      if (a <= 0) continue;
      ctx.globalAlpha = a;
      ctx.drawImage(src, 0, i * h, W, h, dx, i * h, W, h);
      if (inv > 0.05) {
        ctx.globalAlpha = a * 0.3 * inv;
        ctx.drawImage(src, 0, i * h, W, h, dx * 1.6, i * h, W, h);
      }
    }
    ctx.globalAlpha = 1;
  }

  function drawText(L, t) {
    var s = L.style || {}, lay = layoutText(L);
    if (!lay.toks.length) return;
    var fs = lay.fs;
    var enter = findAnim(L, 'enter'), exit = findAnim(L, 'exit'), hold = findAnim(L, 'hold');
    var dIn = enter ? (enter.durationMs || 500) : 1, dOut = exit ? (exit.durationMs || 400) : 1;
    var pIn = clamp01((t - L.fromMs) / dIn), pOut = clamp01((L.toMs - t) / dOut);
    var e = easeOut(pIn), inv = 1 - e;
    var st = { a: 1, sx: 1, sy: 1, dx: 0, dy: 0, rot: 0, blur: 0, reveal: 1, ghost: 0 };
    var slice = -1; // tiến độ cắt dải (0..1) nếu đang dùng

    if (enter) switch (enter.effect) {
      case 'fade': st.a = e; break;
      case 'zoomIn': st.a = e; st.sx = st.sy = 0.6 + 0.4 * e; break;
      case 'slideUp': st.a = e; st.dy = inv * H * 0.06; break;
      case 'slideLeft': st.a = e; st.dx = inv * W * 0.12; break;
      case 'blurIn': st.a = e; st.blur = inv * 14; break;
      case 'typewriter': st.reveal = pIn; break;
      case 'rotateIn': st.a = e; st.rot = -inv * 0.25; st.sx = st.sy = 0.8 + 0.2 * e; break;
      case 'glitchIn': st.a = Math.min(1, pIn * 3); st.dx = inv * W * 0.04 * pseudo(Math.floor(t / 45)); break;
      case 'sliceIn': if (pIn < 1) slice = pIn; break;
      case 'swipeIn': st.a = Math.min(1, pIn * 3); st.dx = inv * inv * W * 0.55; st.blur = inv * 6; st.ghost = 1; break;
      case 'swipeInUp': st.a = Math.min(1, pIn * 3); st.dy = inv * inv * H * 0.35; st.blur = inv * 6; st.ghost = 1; break;
    }
    if (exit) {
      var x = 1 - pOut, e2 = easeOut(x);
      switch (exit.effect) {
        case 'fade': st.a *= pOut; break;
        case 'zoomOut': st.a *= pOut; st.sx *= 1 + 0.25 * e2; st.sy *= 1 + 0.25 * e2; break;
        case 'slideUp': st.a *= pOut; st.dy -= e2 * H * 0.05; break;
        case 'blurOut': st.a *= pOut; st.blur += e2 * 14; break;
        case 'dissolve': st.a *= pOut; st.blur += e2 * 6; break;
        case 'sliceOut': if (pOut < 1) slice = pOut; break;
        case 'swipeOut': st.a *= Math.min(1, pOut * 3); st.dx -= x * x * W * 0.55; st.blur += x * x * 6; st.ghost = 1; break;
        case 'swipeOutUp': st.a *= Math.min(1, pOut * 3); st.dy -= x * x * H * 0.35; st.blur += x * x * 6; st.ghost = 1; break;
      }
    }
    var shadowBlur = fs * 0.15;
    if (hold) switch (hold.effect) {
      case 'glow': shadowBlur = fs * (0.3 + 0.12 * Math.sin(t / 400)); break;
      case 'float': st.dy += Math.sin(t / 900) * fs * 0.12; break;
      case 'pulse': st.sx *= 1 + 0.05 * beatEnv(t); st.sy *= 1 + 0.05 * beatEnv(t); break;
      case 'glitch': if (Math.floor(t / 130) % 11 === 0) st.dx += pseudo(Math.floor(t / 130)) * fs * 0.12; break;
    }
    if (st.a <= 0.003) return;

    var cx = (s.x == null ? 0.5 : s.x) * W, cy = (s.y == null ? 0.5 : s.y) * H;
    if (yOverride != null && !lay.ring && !lay.collage) cy = yOverride * H;
    function place() {
      ctx.translate(cx + st.dx, cy + st.dy);
      if (st.rot) ctx.rotate(st.rot);
      ctx.scale(st.sx, st.sy);
    }

    if (slice >= 0) {
      // vẽ cả khối chữ vào khung phụ rồi cắt dải lên khung chính
      if (!off || off.width !== W || off.height !== H) {
        off = document.createElement('canvas'); off.width = W; off.height = H; offCtx = off.getContext('2d');
      }
      var main = ctx;
      ctx = offCtx;
      ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1; ctx.filter = 'none';
      ctx.clearRect(0, 0, W, H);
      ctx.save(); place(); paintWords(L, lay, t, st, shadowBlur); ctx.restore();
      ctx = main;
      blitSlices(off, slice, t, hashStr(L.id));
      return;
    }

    var doBlur = st.blur > 0.3 && 'filter' in ctx;
    var gx = Math.abs(st.dx) * 0.07, gy = Math.abs(st.dy) * 0.07, doGhost = st.ghost && gx + gy > 1.5;
    if (doBlur || doGhost) {
      // Làm mờ / vệt mờ: vẽ cả khối chữ một lần vào khung phụ rồi chép ra (lọc mờ từng từ rất chậm với câu dài).
      if (!off || off.width !== W || off.height !== H) {
        off = document.createElement('canvas'); off.width = W; off.height = H; offCtx = off.getContext('2d');
      }
      var mainCtx = ctx;
      ctx = offCtx;
      ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.globalAlpha = 1; ctx.filter = 'none';
      ctx.clearRect(0, 0, W, H);
      ctx.save(); place(); paintWords(L, lay, t, st, shadowBlur); drawOrnament(L, lay, t, s, cy); ctx.restore();
      ctx = mainCtx;
      ctx.save();
      if (doBlur) ctx.filter = 'blur(' + st.blur.toFixed(1) + 'px)';
      if (doGhost) for (var g = 3; g >= 1; g--) { // chữ đi sang trái/lên trên thì vệt ở bên phải/phía dưới
        ctx.globalAlpha = st.a * 0.16 * (4 - g) / 3; ctx.drawImage(off, gx * g, gy * g);
      }
      ctx.globalAlpha = st.a; ctx.drawImage(off, 0, 0);
      ctx.restore();
      return;
    }
    ctx.save();
    place();
    ctx.globalAlpha = st.a;
    paintWords(L, lay, t, st, shadowBlur);
    drawOrnament(L, lay, t, s, cy);
    ctx.restore();
  }

  // Thẻ tiêu đề gọn cho karaoke / lyric lướt: tên bài lớn ở giữa, dòng ghi công (ca sĩ, sáng tác) bên dưới.
  function drawPlainTitle(L, s, a, local) {
    var maxW = W * 0.86, tfs = Math.round(H * 0.13);
    ctx.font = (s.weight || 'bold') + ' ' + tfs + 'px ' + famOf(s.font);
    var tw = ctx.measureText(L.title || '').width;
    if (tw > maxW) tfs = Math.floor(tfs * maxW / tw);
    var pop = easeBack(clamp01(local / 600)), cfs = Math.round(H * 0.05), credits = L.credits || [];
    var blockH = tfs + credits.length * cfs * 1.5, top = H * 0.46 - blockH / 2;
    ctx.save();
    ctx.globalAlpha = a; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    ctx.translate(W / 2, top + tfs / 2); ctx.scale(0.88 + 0.12 * pop, 0.88 + 0.12 * pop);
    ctx.font = (s.weight || 'bold') + ' ' + tfs + 'px ' + famOf(s.font);
    if (s.strokeColor) { ctx.lineJoin = 'round'; ctx.lineWidth = tfs * 0.14; ctx.strokeStyle = s.strokeColor; ctx.strokeText(L.title || '', 0, 0); }
    ctx.shadowColor = s.highlightColor || 'rgba(255,255,255,0.8)'; ctx.shadowBlur = tfs * 0.2;
    ctx.fillStyle = '#ffffff'; ctx.fillText(L.title || '', 0, 0);
    ctx.shadowBlur = 0;
    ctx.font = (s.weight || 'bold') + ' ' + cfs + 'px ' + famOf(s.font);
    credits.forEach(function (c, i) {
      var y = tfs / 2 + cfs * 1.5 * (i + 1) - cfs * 0.2;
      if (s.strokeColor) { ctx.lineWidth = cfs * 0.16; ctx.strokeStyle = s.strokeColor; ctx.strokeText(c, 0, y); }
      ctx.fillStyle = s.highlightColor || '#ffd54f'; ctx.fillText(c, 0, y);
    });
    ctx.restore();
  }

  // ---------- thẻ tiêu đề (tên bài + nghệ sĩ xếp khối) ----------
  function drawTitle(L, t) {
    var s = L.style || {}, local = t - L.fromMs, left = L.toMs - t;
    var a = clamp01(local / 600) * clamp01(left / 600);
    if (a <= 0.003) return;
    var tfs = Math.round(H * 0.2), bfs = Math.round(H * 0.075);
    var title = L.title || '', lines = L.lines || [];
    if (s.plain) { drawPlainTitle(L, s, a, local); return; }
    var pop = easeBack(clamp01(local / 700));
    ctx.save();
    ctx.globalAlpha = a;
    ctx.translate(W * 0.5, H * 0.46);
    ctx.scale(0.9 + 0.1 * pop, 0.9 + 0.1 * pop);
    var parts = title.split(/\s+/).filter(Boolean), tl = parts.length > 2 ? [parts.slice(0, Math.ceil(parts.length / 2)).join(' '), parts.slice(Math.ceil(parts.length / 2)).join(' ')] : parts;
    ctx.font = (s.weight || 'normal') + ' ' + tfs + 'px ' + famOf(s.font);
    var tw = 0; tl.forEach(function (p) { tw = Math.max(tw, ctx.measureText(p).width); });
    ctx.font = '700 ' + bfs + 'px ' + famOf(s.blockFont || 'Oswald');
    var bw = 0; lines.forEach(function (ln) { bw = Math.max(bw, ctx.measureText(ln.toUpperCase()).width); });
    var total = tw + bw * 0.85, ox = -total / 2;           // tên bài bên trái, khối chữ chồng nhẹ lên mép phải
    var bx = ox + tw - bw * 0.15, by = -(lines.length - 1) / 2 * bfs * 0.92;
    ctx.textAlign = 'left'; ctx.textBaseline = 'middle';
    ctx.shadowColor = 'rgba(70,160,255,0.9)'; ctx.shadowBlur = bfs * 0.5;
    lines.forEach(function (ln, i) {
      ctx.fillStyle = /^x$/i.test(ln) ? '#ffffff' : (s.blockColor || '#3fa0ff');
      ctx.fillText(ln.toUpperCase(), bx, by + i * bfs * 0.92);
    });
    ctx.font = (s.weight || 'normal') + ' ' + tfs + 'px ' + famOf(s.font);
    ctx.textAlign = 'center';
    ctx.fillStyle = '#ffffff'; ctx.shadowColor = s.shadowColor || 'rgba(110,180,255,0.95)'; ctx.shadowBlur = tfs * 0.18;
    tl.forEach(function (p, i) { ctx.fillText(p, ox + tw / 2 + i * tfs * 0.18, (i - (tl.length - 1) / 2) * tfs * 0.78); });
    ctx.restore();
  }

  // ---------- hình ngữ nghĩa ----------
  var DEFAULT_DUR = { popIn: 350, fadeIn: 500, floatIn: 600, shatter: 900, fadeOut: 400 };

  function anchorOf(it, size) {
    var an = it.anchor || { mode: 'hero' };
    if (an.mode === 'hero' || !textLayers[an.layer]) {
      var st = it.style || {};
      return { cx: (st.x == null ? 0.5 : st.x) * W, cy: (st.y == null ? 0.4 : st.y) * H, size: (st.size == null ? 0.3 : st.size) * H };
    }
    var L = textLayers[an.layer], lay = layoutText(L), s = L.style || {};
    var x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
    for (var i = an.tokenStart; i < an.tokenEnd; i++) {
      var r = lay.rects[i]; if (!r) continue;
      x0 = Math.min(x0, r.x); x1 = Math.max(x1, r.x + r.w); y0 = Math.min(y0, r.y - r.h / 2); y1 = Math.max(y1, r.y + r.h / 2);
    }
    if (x0 > x1) return { cx: W / 2, cy: H * 0.4, size: H * 0.2 };
    var baseX = (s.x == null ? 0.5 : s.x) * W, baseY = (s.y == null ? 0.5 : s.y) * H;
    if (an.mode === 'above') return { cx: baseX + (x0 + x1) / 2, cy: baseY + y0 - lay.fs * 0.75, size: lay.fs * 1.7 };
    return { cx: baseX + (x0 + x1) / 2, cy: baseY + (y0 + y1) / 2, size: lay.fs * 1.9 }; // inline
  }

  function drawParticlePreset(name, ph, local, A, seed) {
    var n = Math.min((ph.params && ph.params.count) || 26, 60), ts = local / 1000, size = A.size;
    var fadeIn = clamp01(local / 500);
    ctx.save(); ctx.lineCap = 'round';
    if (name === 'rainFall') {
      ctx.strokeStyle = '#a9d4ff'; ctx.lineWidth = Math.max(1.5, size * 0.018);
      var fall = size * 1.5;
      for (var i = 0; i < n; i++) {
        var rx = pseudo(i * 3 + seed), rs = pseudo(i * 3 + 1 + seed), rp = pseudo(i * 3 + 2 + seed);
        var sp = size * (1.6 + 0.5 * rs), y = (ts * sp + (rp + 1) * 0.5 * fall) % fall;
        var x = A.cx + rx * size * 0.42 + y * 0.1;
        ctx.globalAlpha = fadeIn * (1 - y / fall) * 0.9;
        ctx.beginPath(); ctx.moveTo(x, A.cy + size * 0.3 + y); ctx.lineTo(x + size * 0.02, A.cy + size * 0.3 + y + size * 0.14); ctx.stroke();
      }
    } else if (name === 'windStreak') {
      ctx.strokeStyle = '#ffffff'; ctx.lineWidth = Math.max(2, size * 0.025);
      var range = size * 3;
      for (var j = 0; j < n; j++) {
        var ry = pseudo(j * 3 + seed), rq = pseudo(j * 3 + 1 + seed), rl = pseudo(j * 3 + 2 + seed);
        var sp2 = size * (1.2 + 0.6 * rq), px = ((ts * sp2 + (rl + 1) * 0.5 * range) % range);
        var env = Math.sin(px / range * Math.PI);
        var xx = A.cx - range / 2 + px, yy = A.cy + ry * size * 0.7;
        ctx.globalAlpha = fadeIn * env * 0.6;
        ctx.beginPath(); ctx.moveTo(xx, yy); ctx.quadraticCurveTo(xx + size * 0.25, yy - size * 0.05, xx + size * 0.5, yy); ctx.stroke();
      }
    } else if (name === 'snowFall') {
      ctx.fillStyle = '#ffffff';
      var f2 = size * 1.6;
      for (var k = 0; k < n; k++) {
        var qx = pseudo(k * 3 + seed), qs = pseudo(k * 3 + 1 + seed), qp = pseudo(k * 3 + 2 + seed);
        var y2 = (ts * size * (0.35 + 0.2 * qs) + (qp + 1) * 0.5 * f2) % f2;
        var x2 = A.cx + qx * size * 0.6 + Math.sin(ts * 1.2 + k) * size * 0.05;
        ctx.globalAlpha = fadeIn * (1 - y2 / f2) * 0.9;
        ctx.beginPath(); ctx.arc(x2, A.cy - size * 0.2 + y2, Math.max(1.5, size * 0.025), 0, Math.PI * 2); ctx.fill();
      }
    }
    ctx.restore();
  }

  // Khung phim / khung ảnh chứa ảnh của người dùng. size = chiều rộng khung.
  function drawFrame(variant, url, w) {
    var h = w * 0.75, rec = url ? loadImage(url) : null;
    function inner(x, y, iw, ih) {
      ctx.save(); ctx.beginPath(); ctx.rect(x, y, iw, ih); ctx.clip();
      if (rec && rec.state === 'ok') {
        var nw = rec.img.naturalWidth, nh = rec.img.naturalHeight, k = Math.max(iw / nw, ih / nh);
        ctx.drawImage(rec.img, x + (iw - nw * k) / 2, y + (ih - nh * k) / 2, nw * k, nh * k);
      } else { ctx.fillStyle = '#3a4252'; ctx.fillRect(x, y, iw, ih); }
      ctx.restore();
    }
    ctx.save();
    ctx.shadowColor = 'rgba(0,0,0,0.45)'; ctx.shadowBlur = w * 0.05;
    if (variant === 'film') {
      ctx.fillStyle = '#111'; ctx.fillRect(-w / 2, -h / 2, w, h);
      ctx.shadowBlur = 0;
      var bar = h * 0.15, holes = 9, hw = w / holes;
      ctx.fillStyle = '#e8e4da';
      for (var i = 0; i < holes; i++) {
        var hx = -w / 2 + hw * (i + 0.28);
        ctx.fillRect(hx, -h / 2 + bar * 0.28, hw * 0.44, bar * 0.44);
        ctx.fillRect(hx, h / 2 - bar * 0.72, hw * 0.44, bar * 0.44);
      }
      inner(-w / 2 + w * 0.03, -h / 2 + bar, w * 0.94, h - bar * 2);
    } else { // polaroid
      ctx.fillStyle = '#f7f5ef'; ctx.fillRect(-w / 2, -h / 2, w, h);
      ctx.shadowBlur = 0;
      var m = w * 0.055;
      inner(-w / 2 + m, -h / 2 + m, w - m * 2, h - m - h * 0.2);
    }
    ctx.restore();
  }

  // Khung ngắm máy ảnh: bốn góc quanh khối chữ (kiểu "ống kính"). box = {cx, cy, w, h}.
  function drawViewfinder(box, a, s, local) {
    var w = box.w, h = box.h, len = Math.min(w, h) * 0.22, lw = Math.max(2, H * 0.006);
    ctx.save();
    ctx.translate(box.cx, box.cy); ctx.scale(s, s);
    ctx.globalAlpha = a; ctx.strokeStyle = '#f4f8ff'; ctx.lineWidth = lw; ctx.lineCap = 'square';
    ctx.shadowColor = 'rgba(160,200,255,0.9)'; ctx.shadowBlur = lw * 3;
    ctx.beginPath();
    [[-1, -1], [1, -1], [1, 1], [-1, 1]].forEach(function (c) {
      var x = c[0] * w / 2, y = c[1] * h / 2;
      ctx.moveTo(x - c[0] * len, y); ctx.lineTo(x, y); ctx.lineTo(x, y - c[1] * len);
    });
    ctx.stroke();
    if (local % 1000 < 520) { // chấm "đang quay" nhấp nháy ở góc trên trái
      ctx.shadowBlur = 0; ctx.fillStyle = '#ff4b5c';
      ctx.beginPath(); ctx.arc(-w / 2 + len * 0.45, -h / 2 + len * 0.45, lw * 1.6, 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
  }

  // Hộp bao quanh toàn bộ chữ của một câu (toạ độ khung hình).
  function layerBox(L) {
    var lay = layoutText(L), s = L.style || {};
    var x0 = 1e9, x1 = -1e9, y0 = 1e9, y1 = -1e9;
    for (var i = 0; i < lay.rects.length; i++) {
      var r = lay.rects[i]; if (!r || lay.hidden[i]) continue;
      x0 = Math.min(x0, r.x); x1 = Math.max(x1, r.x + r.w); y0 = Math.min(y0, r.y - r.h / 2); y1 = Math.max(y1, r.y + r.h / 2);
    }
    var bx = (s.x == null ? 0.5 : s.x) * W, by = (s.y == null ? 0.5 : s.y) * H;
    if (x0 > x1) return { cx: bx, cy: by, w: W * 0.4, h: H * 0.3 };
    var pad = lay.fs * 0.5;
    return { cx: bx + (x0 + x1) / 2, cy: by + (y0 + y1) / 2, w: Math.max(x1 - x0 + pad * 2, H * 0.3), h: Math.max(y1 - y0 + pad * 2, H * 0.2) };
  }

  // Đám mây lớn mềm: nhiều khối tròn có chuyển sắc, sáng ở trên và xám xanh ở đáy.
  function drawSoftCloud(cx, cy, w) {
    var puffs = [[-0.30, 0.06, 0.20], [-0.12, -0.06, 0.26], [0.10, -0.10, 0.28], [0.30, 0.0, 0.22], [0.0, 0.08, 0.26], [-0.22, 0.14, 0.18], [0.22, 0.13, 0.18]];
    ctx.save();
    for (var i = 0; i < puffs.length; i++) {
      var p = puffs[i], x = cx + p[0] * w, y = cy + p[1] * w * 0.7, r = p[2] * w * 0.5 * 1.6;
      var g = ctx.createRadialGradient(x - r * 0.25, y - r * 0.35, r * 0.1, x, y, r);
      g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.55, 'rgba(236,243,252,0.97)'); g.addColorStop(1, 'rgba(160,185,220,0)');
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill();
    }
    ctx.restore();
  }

  // Vật bay ngang màn hình (chim). 'flyAcross' = một con, 'flock' = cả đàn xuất phát lệch nhau.
  function drawFlyers(name, ph, local, A, seed, spr, alpha) {
    if (name === 'passBy') { // vật lớn trôi chậm ngang màn hình, nằm phía sau chữ (đám mây)
      var pd = ph.durationMs || 6000, pp = local / pd;
      if (pp <= 0 || pp >= 1) return;
      var ps = Math.max(W * 0.5, A.size * 2.2), px = -ps * 0.5 + (W + ps) * pp;
      ctx.save();
      ctx.globalAlpha = alpha * 0.93 * Math.min(1, pp * 6, (1 - pp) * 6);
      ctx.shadowColor = 'rgba(190,220,255,0.55)'; ctx.shadowBlur = ps * 0.08;
      drawSoftCloud(px, A.cy + Math.sin(pp * 5) * ps * 0.03, ps);
      ctx.restore();
      return;
    }
    var flock = name === 'flock', dur = ph.durationMs || (flock ? 4200 : 3200);
    var n = flock ? Math.min((ph.params && ph.params.count) || 9, 20) : 1;
    var travel = flock ? dur * 0.65 : dur;
    for (var i = 0; i < n; i++) {
      var r1 = (pseudo(i * 5 + seed) + 1) / 2, r2 = (pseudo(i * 5 + 1 + seed) + 1) / 2, r3 = (pseudo(i * 5 + 2 + seed) + 1) / 2;
      var delay = flock ? r1 * dur * 0.35 : 0, p = (local - delay) / travel;
      if (p <= 0 || p >= 1) continue;
      var bs = flock ? Math.max(A.size * 0.36, H * 0.075) * (0.6 + 0.4 * r2) : Math.max(A.size * 0.8, H * 0.16);
      var x = -bs + (W + 2 * bs) * p;
      var yb = A.cy + (flock ? (r3 - 0.5) * H * 0.22 : 0);
      var y = yb - Math.sin(p * Math.PI) * H * 0.07 + Math.sin(local / 170 + i * 2) * bs * 0.05;
      var flap = 0.55 + 0.45 * Math.abs(Math.sin(local / (flock ? 95 : 120) + i * 1.7));
      ctx.save();
      ctx.globalAlpha = alpha * Math.min(1, p * 8, (1 - p) * 8);
      ctx.translate(x, y); ctx.rotate(-0.18 * Math.cos(p * Math.PI)); ctx.scale(1, flap);
      ctx.shadowColor = 'rgba(0,0,0,0.3)'; ctx.shadowBlur = bs * 0.08;
      ctx.drawImage(spr, -bs / 2, -bs / 2, bs, bs);
      ctx.restore();
    }
  }

  function drawItem(it, t) {
    var spr = it.visual.kind === 'frame' ? null : getSprite(it.visual);
    var probe = anchorOf(it);
    var A = probe;
    // Khung phim/ảnh là hình lớn ở phần trên khung hình, không đè lên chữ.
    if (it.visual.kind === 'frame' && (it.anchor || {}).mode !== 'hero') A = { cx: probe.cx, cy: Math.min(probe.cy, H * 0.38), size: Math.max(probe.size, H * 0.3) };
    var st = { a: 1, s: 1, dx: 0, dy: 0, rot: 0, shatter: -1 };
    var seed = it.seed || 0, particles = [], flyers = [];
    var phases = it.phases.slice().sort(function (a, b) { return a.atMs - b.atMs; });
    var size = A.size;
    for (var i = 0; i < phases.length; i++) {
      var ph = phases[i];
      if (t < ph.atMs) continue;
      var local = t - ph.atMs, dur = ph.durationMs || DEFAULT_DUR[ph.preset] || 500, p = clamp01(local / dur);
      switch (ph.preset) {
        case 'popIn': st.s *= Math.max(0.001, easeBack(p)); st.a *= clamp01(p * 2.5); break;
        case 'fadeIn': st.a *= p; break;
        case 'floatIn': st.a *= p; st.dy += (1 - easeOut(p)) * size * 0.5; break;
        case 'drift': st.dx += Math.sin(local / 1200) * size * 0.12; st.dy += Math.sin(local / 1700) * size * 0.04; break;
        case 'pulse': st.s *= 1 + 0.06 * Math.sin(local / 260); break;
        case 'sway': st.rot += Math.sin(local / 500) * 0.12; break;
        case 'twinkle': st.a *= 0.65 + 0.35 * Math.sin(local / 150); break;
        case 'flicker': st.s *= 1 + 0.05 * pseudo(Math.floor(local / 60) + seed); st.a *= 0.8 + 0.2 * (pseudo(Math.floor(local / 45) + 7) * 0.5 + 0.5); break;
        case 'shatter': st.shatter = p; break;
        case 'fadeOut': st.a *= 1 - p; break;
        case 'rainFall': case 'windStreak': case 'snowFall': particles.push([ph.preset, ph, local]); break;
        case 'flyAcross': case 'flock': case 'passBy': flyers.push([ph.preset, ph, local]); break;
      }
    }
    st.a *= clamp01((it.toMs - t) / 300);
    if (st.a <= 0.003) return;

    ctx.save();
    for (var q = 0; q < particles.length; q++) drawParticlePreset(particles[q][0], particles[q][1], particles[q][2], A, seed);
    if (flyers.length) {
      if (spr) for (var fq = 0; fq < flyers.length; fq++) drawFlyers(flyers[fq][0], flyers[fq][1], flyers[fq][2], A, seed, spr, st.a);
      ctx.restore();
      return;
    }
    if (it.visual.kind === 'frame' && it.visual.variant === 'viewfinder') {
      var box = (textLayers[(it.anchor || {}).layer]) ? layerBox(textLayers[it.anchor.layer]) : { cx: A.cx, cy: A.cy, w: size * 1.6, h: size * 1.1 };
      drawViewfinder(box, st.a, st.s * (1 + 0.04 * Math.sin((t - it.fromMs) / 500)), t - it.fromMs);
    } else if (it.visual.kind === 'frame') {
      ctx.translate(A.cx + st.dx, A.cy + st.dy);
      ctx.rotate(st.rot + (it.visual.variant === 'polaroid' ? -0.05 : 0)); ctx.scale(st.s, st.s);
      ctx.globalAlpha = st.a;
      drawFrame(it.visual.variant, it.visual.ref, size * 1.25);
    } else if (spr) {
      ctx.translate(A.cx + st.dx, A.cy + st.dy);
      ctx.rotate(st.rot); ctx.scale(st.s, st.s);
      if (st.shatter >= 0) {
        var p2 = st.shatter, n = 4, cell = SPR / n, piece = size / n;
        ctx.globalAlpha = st.a * (1 - p2);
        for (var gy = 0; gy < n; gy++) for (var gx = 0; gx < n; gx++) {
          var idx = gy * n + gx, ox = ((gx + 0.5) / n - 0.5) * size, oy = ((gy + 0.5) / n - 0.5) * size;
          var rx = pseudo(idx * 2 + seed), ry = pseudo(idx * 2 + 1 + seed);
          var dx = (ox * 2.2 + rx * 0.6 * size) * p2, dy = (oy * 2.2 + ry * 0.6 * size) * p2 + 1.2 * size * p2 * p2;
          ctx.save(); ctx.translate(ox + dx, oy + dy); ctx.rotate(rx * 2.1 * p2);
          ctx.drawImage(spr, gx * cell, gy * cell, cell, cell, -piece / 2, -piece / 2, piece, piece);
          ctx.restore();
        }
      } else {
        ctx.globalAlpha = st.a;
        ctx.shadowColor = 'rgba(0,0,0,0.35)'; ctx.shadowBlur = size * 0.06;
        ctx.drawImage(spr, -size / 2, -size / 2, size, size);
      }
    }
    ctx.restore();
  }

  function drawSemantic(L, t) {
    var items = L.items || [];
    for (var i = 0; i < items.length; i++) {
      var it = items[i];
      if (t >= it.fromMs && t < it.toMs) drawItem(it, t);
    }
  }

  // ---------- điều khiển ----------
  function setScene(s) {
    scene = s; W = s.width; H = s.height;
    cv.width = W; cv.height = H;
    layouts = {}; textLayers = {}; sprites = {};
    var fontsSeen = {};
    s.layers.forEach(function (L) {
      if (L.type === 'text') {
        textLayers[L.id] = L;
        var f = L.style && L.style.font;
        if (f && !fontsSeen[f] && document.fonts && document.fonts.load) {
          fontsSeen[f] = true;
          pending++;
          var done = function () { pending--; layouts = {}; };
          document.fonts.load((L.style.weight || 'normal') + ' 40px ' + famOf(f), 'Aáàảãạăâđêôơư').then(done, done);
        }
      }
      if (L.type === 'image') loadImage(L.source);
      if (L.type === 'semantic') (L.items || []).forEach(function (it) { if (it.visual.kind === 'userImage' || (it.visual.kind === 'frame' && it.visual.ref)) loadImage(it.visual.ref); });
    });
  }

  function draw(t) {
    if (!scene) return;
    ctx.globalAlpha = 1; ctx.filter = 'none'; ctx.shadowBlur = 0;
    ctx.fillStyle = '#000'; ctx.fillRect(0, 0, W, H);
    var Ls = scene.layers;
    for (var i = 0; i < Ls.length; i++) {
      var L = Ls[i];
      if (t < L.fromMs || t >= L.toMs) continue;
      switch (L.type) {
        case 'image': drawImageLayer(L, t); break;
        case 'overlay': drawOverlay(L, t); break;
        case 'particles': drawParticles(L, t); break;
        case 'reactive': drawReactive(L, t); break;
        case 'text': drawText(L, t); break;
        case 'semantic': drawSemantic(L, t); break;
        case 'title': drawTitle(L, t); break;
      }
    }
    if (yOverride != null) { // đang kéo lời: vẽ đường dóng ngang cho dễ canh
      ctx.save(); ctx.setLineDash([12, 10]); ctx.lineWidth = 2; ctx.strokeStyle = 'rgba(255,255,255,0.55)';
      ctx.beginPath(); ctx.moveTo(0, yOverride * H); ctx.lineTo(W, yOverride * H); ctx.stroke(); ctx.restore();
    }
  }

  return {
    init: function (canvas) { cv = canvas; ctx = canvas.getContext('2d'); },
    setScene: setScene,
    ready: function () { return !!scene && pending === 0; },
    draw: draw,
    setTextY: function (y) { yOverride = (y == null) ? null : y; }
  };
})();
