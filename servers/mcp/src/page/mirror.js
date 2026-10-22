// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Latch live mirror (R30, ADR-051): shows the phone's screen and sends the
// owner's clicks, drags, scrolling, and typing back. Browser APIs only:
// WebSocket, WebCodecs VideoDecoder, canvas, Pointer Events. The protocol is
// in docs/protocol/mirror.md.
'use strict';

// ---- Pure helpers (unit-tested in Node: servers/mcp/tests/mirror-page.test.cjs) ----

const KIND_H264 = 1;
const KIND_JPEG = 2;
const KIND_PCM = 3;
const KIND_SHOT = 4;
// R39: one Opus packet of the phone's sound.
const KIND_OPUS = 5;
// R40: an H.265 access unit, from phones with a hardware encoder.
const KIND_H265 = 6;
const FLAG_KEY = 1;
const HEADER_BYTES = 10;
/** How long a page keeps asking for the phone before it waits for a click (ADR-058, ADR-069). */
const WAIT_FOR_PHONE_MS = 30 * 60 * 1000;

/** Splits a binary frame into its header fields and payload; null when malformed. */
function parseFrame(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength <= HEADER_BYTES) return null;
  const view = new DataView(buffer);
  const kind = view.getUint8(0);
  if (kind !== KIND_H264 && kind !== KIND_JPEG && kind !== KIND_PCM && kind !== KIND_SHOT && kind !== KIND_OPUS && kind !== KIND_H265) return null;
  const flags = view.getUint8(1);
  const ts = Number(view.getBigUint64(2));
  return { kind, key: (flags & FLAG_KEY) !== 0, ts, data: new Uint8Array(buffer, HEADER_BYTES) };
}

/** The NAL units of an H.264 Annex B access unit, without their start codes. */
function nalUnits(bytes) {
  const units = [];
  let start = -1;
  let i = 0;
  while (i + 2 < bytes.length) {
    if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1) {
      if (start >= 0) units.push(bytes.subarray(start, i > 0 && bytes[i - 1] === 0 ? i - 1 : i));
      i += 3;
      start = i;
    } else {
      i += 1;
    }
  }
  if (start >= 0 && start < bytes.length) units.push(bytes.subarray(start));
  return units;
}

/** The sequence parameter set (NAL type 7) of a key frame, or null. */
function findSps(bytes) {
  return nalUnits(bytes).find((n) => n.length >= 4 && (n[0] & 0x1f) === 7) || null;
}

/** The WebCodecs codec string for an SPS: avc1.PPCCLL (profile, constraints, level). */
function codecFromSps(sps) {
  const hex = (b) => b.toString(16).padStart(2, '0').toUpperCase();
  return `avc1.${hex(sps[1])}${hex(sps[2])}${hex(sps[3])}`;
}

/** R40: the SPS (NAL type 33) of an H.265 access unit, or null. */
function findHevcSps(bytes) {
  for (const nal of nalUnits(bytes)) {
    if (nal.length > 14 && ((nal[0] >> 1) & 0x3f) === 33) return nal;
  }
  return null;
}

/** A NAL unit without its emulation-prevention bytes (00 00 03 → 00 00). */
function unescapeNal(nal) {
  const out = [];
  let zeros = 0;
  for (const b of nal) {
    if (zeros >= 2 && b === 3) { zeros = 0; continue; }
    out.push(b);
    zeros = b === 0 ? zeros + 1 : 0;
  }
  return Uint8Array.from(out);
}

/**
 * The WebCodecs codec string of an H.265 SPS (ISO/IEC 14496-15 annex E):
 * hev1.[space]profile.compatibility.tier+level[.constraints], e.g. hev1.1.6.L93.B0.
 */
function hevcCodecFromSps(sps) {
  const b = unescapeNal(sps);
  // 2-byte NAL header, then one byte of ids before profile_tier_level.
  const p = 3;
  if (b.length < p + 12) return null;
  const space = b[p] >> 6;
  const tier = (b[p] >> 5) & 1;
  const profile = b[p] & 0x1f;
  let flags = ((b[p + 1] << 24) | (b[p + 2] << 16) | (b[p + 3] << 8) | b[p + 4]) >>> 0;
  let reversed = 0;
  for (let i = 0; i < 32; i++) {
    reversed = ((reversed << 1) | (flags & 1)) >>> 0;
    flags >>>= 1;
  }
  const constraints = Array.from(b.subarray(p + 5, p + 11));
  while (constraints.length && constraints[constraints.length - 1] === 0) constraints.pop();
  const level = b[p + 11];
  return `hev1.${['', 'A', 'B', 'C'][space]}${profile}.${reversed.toString(16).toUpperCase()}.${tier ? 'H' : 'L'}${level}`
    + constraints.map((c) => `.${c.toString(16).toUpperCase()}`).join('');
}

/**
 * Where the picture is drawn inside an element showing it with object-fit:
 * contain: the element's rect less the bars around a picture of another shape.
 */
function containedRect(rect, pictureW, pictureH) {
  if (!(pictureW > 0 && pictureH > 0) || rect.width <= 0 || rect.height <= 0) return rect;
  const scale = Math.min(rect.width / pictureW, rect.height / pictureH);
  const width = pictureW * scale;
  const height = pictureH * scale;
  return { left: rect.left + (rect.width - width) / 2, top: rect.top + (rect.height - height) / 2, width, height };
}

/**
 * A point on the drawn picture as phone pixels; null outside it. With
 * `turned`, the picture is shown a quarter turn clockwise.
 */
// R54: where the instant touch dot goes inside its frame (whose box is `frame`), as a CSS transform.
function dotTransform(clientX, clientY, frame) {
  return `translate(${Math.round(clientX - frame.left)}px, ${Math.round(clientY - frame.top)}px)`;
}

function mapPoint(clientX, clientY, rect, screen, turned = false) {
  if (!screen || rect.width <= 0 || rect.height <= 0) return null;
  let fx = (clientX - rect.left) / rect.width;
  let fy = (clientY - rect.top) / rect.height;
  if (fx < 0 || fy < 0 || fx > 1 || fy > 1) return null;
  if (turned) [fx, fy] = [fy, 1 - fx];
  return {
    x: Math.min(screen.w - 1, Math.max(0, Math.round(fx * screen.w))),
    y: Math.min(screen.h - 1, Math.max(0, Math.round(fy * screen.h))),
  };
}

/** Wheel movement in phone pixels, so a scroll moves the picture as far as on the page. */
function wheelPixels(event, rect, screen, turned = false) {
  const unit = event.deltaMode === 1 ? 40 : event.deltaMode === 2 ? rect.height : 1;
  if (turned) {
    // Shown a quarter turn clockwise: the page's down is the phone's left.
    const sx = screen.w / Math.max(1, rect.height);
    const sy = screen.h / Math.max(1, rect.width);
    return { dx: Math.round(event.deltaY * unit * sx), dy: Math.round(-event.deltaX * unit * sy) };
  }
  const sx = screen.w / Math.max(1, rect.width);
  const sy = screen.h / Math.max(1, rect.height);
  return { dx: Math.round(event.deltaX * unit * sx), dy: Math.round(event.deltaY * unit * sy) };
}

/**
 * What a key press becomes on the phone: text, a key, a button, or nothing.
 * Editing shortcuts work as on the computer: Ctrl or Option with Backspace
 * and the arrows act on words, Cmd with the arrows on lines, Ctrl/Cmd+A
 * selects everything, Shift+Enter starts a new line.
 */
function keyMessage(event) {
  const mod = event.ctrlKey || event.metaKey;
  const key = (name) => ({ t: 'key', key: name });
  if (mod && !event.altKey && (event.key === 'a' || event.key === 'A')) return key('select_all');
  if (event.key === 'Backspace') return key(event.altKey || event.ctrlKey ? 'word_backspace' : 'backspace');
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    const left = event.key === 'ArrowLeft';
    if (event.metaKey) return key(left ? 'home' : 'end');
    if (event.altKey || event.ctrlKey) return key(left ? 'word_left' : 'word_right');
    return key(left ? 'left' : 'right');
  }
  if (mod || event.altKey) {
    // Option/Alt with a character types it (é, ™ on a Mac); anything else stays with the browser.
    if (!mod && Array.from(event.key).length === 1 && !/\p{Cc}/u.test(event.key)) return { t: 'text', text: event.key };
    if (event.metaKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown')) return key(event.key === 'ArrowUp' ? 'up' : 'down');
    return null;
  }
  const keys = {
    Delete: 'delete', Tab: 'tab', ArrowUp: 'up', ArrowDown: 'down', Home: 'home', End: 'end',
  };
  if (keys[event.key]) return key(keys[event.key]);
  if (event.key === 'Enter') return key(event.shiftKey ? 'newline' : 'enter');
  if (event.key === 'Escape') return { t: 'button', name: 'back' };
  // One character (one code point, so emoji count); named keys such as F5 are longer.
  if (Array.from(event.key).length === 1 && !/\p{Cc}/u.test(event.key)) {
    return { t: 'text', text: event.key };
  }
  return null;
}

/**
 * The page's own shortcuts: Alt (Option on a Mac) with a letter, so they
 * never clash with typing or with the browser's Ctrl/Cmd shortcuts.
 */
const SHORTCUTS = {
  KeyB: 'back', KeyH: 'home', KeyS: 'recents', KeyN: 'notifications', 'Shift+KeyN': 'quick_settings',
  KeyO: 'screen_light', KeyF: 'full', KeyR: 'rotate', KeyM: 'sound',
  ArrowUp: 'volume_up', ArrowDown: 'volume_down',
  KeyP: 'shot', 'Shift+KeyP': 'record', KeyU: 'send_file', KeyW: 'pop_out', KeyI: 'show_notifications',
  KeyD: 'stats',
  Slash: 'help', 'Shift+Slash': 'help',
};

/** The page action for a key press, or null. */
function shortcut(event) {
  if (!event.altKey || event.ctrlKey || event.metaKey) return null;
  if (event.getModifierState && event.getModifierState('AltGraph')) return null;
  return SHORTCUTS[(event.shiftKey ? 'Shift+' : '') + event.code] || null;
}

/** What Ctrl/Cmd with C or X asks the phone for: copy, cut, or nothing. */
function clipboardAction(event) {
  if (!(event.ctrlKey || event.metaKey) || event.altKey || event.shiftKey) return null;
  const k = String(event.key).toLowerCase();
  return k === 'c' ? 'copy' : k === 'x' ? 'cut' : null;
}

/**
 * Pasted text as one message the phone takes: line breaks and tabs kept,
 * other control characters dropped, at most 4096 characters and small
 * enough for the gateway (15 KB). Null when nothing is left.
 */
function pasteMessage(text) {
  let chars = Array.from(String(text).replace(/\r\n?/g, '\n')).filter((c) => c === '\n' || c === '\t' || !/\p{Cc}/u.test(c));
  if (chars.length > 4096) chars = chars.slice(0, 4096);
  const size = (cs) => new TextEncoder().encode(JSON.stringify({ t: 'paste', text: cs.join('') })).length;
  while (chars.length && size(chars) > 15000) chars = chars.slice(0, Math.floor(chars.length * 0.9));
  return chars.length ? { t: 'paste', text: chars.join('') } : null;
}

/** A file-name-safe local time, "2026-10-19 16-30-05". */
function stamp(date) {
  const p = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${p(date.getMonth() + 1)}-${p(date.getDate())} ${p(date.getHours())}-${p(date.getMinutes())}-${p(date.getSeconds())}`;
}

/** The phone's status line: "Wi-Fi", "Mobile", or "Offline", and the battery. */
function statusText(m) {
  const net = { wifi: 'Wi-Fi', cellular: 'Mobile', ethernet: 'Ethernet', none: 'Offline' }[m.network] || '';
  const battery = Number.isInteger(m.battery) ? `${m.battery}%` : '';
  return { net, battery, charging: m.charging === true, bars: Number.isInteger(m.signal) ? Math.max(0, Math.min(4, m.signal)) : null };
}

/** Round trip in ms to the colour of the Live dot. */
function latencyClass(ms) {
  return ms < 80 ? 'good' : ms < 200 ? 'slow' : 'bad';
}

/** 32 bytes as unpadded base64url: the 43-character viewer key the phone accepts. */
function base64url(bytes) {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** A size in bytes as people read it. */
function sizeText(bytes) {
  const n = Number(bytes) || 0;
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  return `${v < 10 ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}

// ---- Stream stats (R35, Alt+D) ----

/**
 * The phone's frame clock minus this page's `performance.now()`, in ms, from
 * ping/pong samples `{sent, rtt, phoneUs}`: the sample with the shortest round
 * trip, assuming the pong was stamped halfway. Null without a sample.
 */
function clockOffset(samples) {
  let best = null;
  for (const s of samples) {
    if (!Number.isFinite(s.phoneUs) || !Number.isFinite(s.rtt) || !Number.isFinite(s.sent)) continue;
    if (!best || s.rtt < best.rtt) best = s;
  }
  return best ? best.phoneUs / 1000 - (best.sent + best.rtt / 2) : null;
}

/** Average, 95th percentile, and maximum of a list of numbers; null when empty. */
function summarize(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const sum = sorted.reduce((a, b) => a + b, 0);
  return { avg: sum / sorted.length, p95: sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * 0.95))], max: sorted[sorted.length - 1] };
}

/** The overlay's lines from the phone's last `stats` message and this page's last second. */
function statsLines(phone, page) {
  const ms = (v) => (v == null ? '–' : `${v.toFixed(1)} ms`);
  const sum = (s) => (s ? `${ms(s.avg)} (p95 ${ms(s.p95)})` : '–');
  const lines = [];
  if (phone) {
    const rate = Number.isFinite(phone.kbps) ? `${(phone.kbps / 1000).toFixed(1)} Mbit/s` : '–';
    const target = Number.isFinite(phone.target_kbps) ? ` (aim ${(phone.target_kbps / 1000).toFixed(1)})` : '';
    lines.push(`Stream   ${phone.codec || '?'} ${phone.w}×${phone.h}  ${phone.frames} fps  ${rate}${target}`);
    lines.push(`Phone    encode ${ms(phone.encode_ms)} (max ${ms(phone.encode_max_ms)})  key frames ${phone.key_frames}  dropped ${phone.drops}`);
    const via = phone.transport === 'udp' ? '  over UDP (R56)' : phone.transport ? '  over WebSocket' : '';
    lines.push(`Gateway  dropped ${Number.isFinite(phone.gateway_drops) ? phone.gateway_drops : '–'}${via}`);
  } else {
    lines.push('Stream   waiting for the phone\'s numbers…');
  }
  lines.push(`Network  capture→page ${page.synced ? sum(page.transit) : 'syncing clocks…'}  ping ${page.rtt == null ? '–' : `${Math.round(page.rtt)} ms`}`);
  lines.push(`Page     ${page.frames} fps  decode ${sum(page.decode)}  draw ${sum(page.draw)}  skipped ${page.skipped}`);
  lines.push(`Total    glass-to-glass ≈ ${page.synced ? sum(page.total) : '–'} + the screen's own delay`);
  return lines;
}

/**
 * R42: a pinch message for the phone: two fingers around point p, `scale`
 * times their first distance apart, turned `angle` degrees; kept in range.
 */
function pinchMessage(phase, p, scale, angle) {
  const s = Math.min(20, Math.max(0.05, Number.isFinite(scale) ? scale : 1));
  const a = Math.min(720, Math.max(-720, Number.isFinite(angle) ? angle : 0));
  return { t: 'pinch', phase, x: p.x, y: p.y, scale: Math.round(s * 1000) / 1000, angle: Math.round(a * 10) / 10 };
}

/** A trackpad pinch arrives as Ctrl+wheel: the new scale after one event's deltaY. */
function pinchScale(scale, deltaY) {
  return Math.min(20, Math.max(0.05, scale * Math.exp(-deltaY / 100)));
}

/** The viewer key from a link's fragment (#k=...), or null. */
function keyFromHash(hash) {
  const m = /(?:^#|&)k=([A-Za-z0-9_-]{43})(?:&|$)/.exec(hash || '');
  return m ? m[1] : null;
}

if (typeof module !== 'undefined') {
  module.exports = {
    parseFrame, nalUnits, findSps, codecFromSps, containedRect, mapPoint, dotTransform, wheelPixels, keyMessage, shortcut,
    clipboardAction, pasteMessage, stamp, statusText, latencyClass, base64url, sizeText, keyFromHash,
    clockOffset, summarize, statsLines, findHevcSps, hevcCodecFromSps, pinchMessage, pinchScale,
  };
}

// ---- The page ----

if (typeof document !== 'undefined') {
  const $ = (id) => document.getElementById(id);
  const KEY_STORE = 'latch-mirror-key';
  // ADR-058: the phone keeps its key across mirrors until the owner forgets this
  // computer there, so the page keeps it too and comes back without a new code.
  const store = {
    get() { try { return localStorage.getItem(KEY_STORE) || sessionStorage.getItem(KEY_STORE); } catch { return null; } },
    set(v) {
      try { localStorage.setItem(KEY_STORE, v); } catch {
        try { sessionStorage.setItem(KEY_STORE, v); } catch { /* private mode: reconnects need the code again */ }
      }
    },
    clear() {
      try { localStorage.removeItem(KEY_STORE); } catch { /* nothing kept */ }
      try { sessionStorage.removeItem(KEY_STORE); } catch { /* nothing kept */ }
    },
  };

  const canvas = $('screen');
  // R36: a Worker decodes and draws, where the browser can hand it the canvas;
  // otherwise this page does, as before.
  const video = typeof Worker === 'function' && typeof OffscreenCanvas === 'function' && typeof canvas.transferControlToOffscreen === 'function'
    ? new Worker('/mirror-worker.js')
    : null;
  let ctx = null;
  if (video) {
    const offscreen = canvas.transferControlToOffscreen();
    video.postMessage({ t: 'init', canvas: offscreen }, [offscreen]);
  } else {
    ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
  }
  // The picture's size, as drawn (the canvas's own size is the worker's once handed over).
  const picture = { w: 0, h: 0 };
  let socket = null;
  let key = null;
  let screen = null;
  let decoder = null;
  let decoderSps = '';
  let waitKey = true;
  let h264 = false;
  let usingH264 = false;
  // R40: this browser decodes H.265 in the worker.
  let h265 = false;
  let lastFrameAt = 0;
  let retry = 0;
  // ADR-058: while the phone is not mirroring, the page asks again now and then.
  let waitingSince = 0;
  let waitTimer = 0;
  let phoneOnline = true;
  let session = 'off';
  let ownerControl = false;
  let toastTimer = 0;
  // How the phone takes input: 'keys' through the Full ADB helper (live drags, real keys), or 'basic'.
  let inputMode = 'basic';

  function show(view) {
    for (const id of ['join', 'over', 'app']) $(id).hidden = id !== view;
  }

  function toast(text, action) {
    const el = $('toast');
    el.replaceChildren(document.createTextNode(text));
    if (action) {
      const b = document.createElement('button');
      b.type = 'button';
      b.textContent = action.label;
      b.addEventListener('click', () => { el.hidden = true; action.run(); });
      el.append(' ', b);
    }
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, action ? 10000 : 4000);
  }

  /** Saves a file the page made (a screenshot, a recording) to the computer's downloads. */
  function save(blob, name) {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 30000);
  }

  function send(message) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  }

  async function probeH265() {
    try {
      return Boolean((await VideoDecoder.isConfigSupported({ codec: 'hev1.1.6.L120.90', optimizeForLatency: true })).supported);
    } catch {
      return false;
    }
  }

  async function probeH264() {
    if (typeof VideoDecoder !== 'function' || !window.isSecureContext) return false;
    for (const codec of ['avc1.42E01F', 'avc1.42E033', 'avc1.42C033']) {
      try {
        const r = await VideoDecoder.isConfigSupported({ codec, optimizeForLatency: true });
        if (r.supported) return true;
      } catch { /* try the next */ }
    }
    return false;
  }

  function modeNote() {
    if (h264) return '';
    if (!window.isSecureContext) {
      return 'Live video needs this page over https, or at http://127.0.0.1 on the computer running the gateway. Until then you get still pictures a few times a second; every control works.';
    }
    return "This browser can't decode live video here, so you get still pictures a few times a second; every control works.";
  }

  // ---- Connection ----

  function connect(join) {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${scheme}://${location.host}/v1/mirror/view`);
    ws.binaryType = 'arraybuffer';
    socket = ws;
    $('live-text').textContent = 'Connecting';
    // R40: H.265 first when this browser decodes it (in the worker); the phone sends it only from a hardware encoder.
    const codecs = [...(h265 ? ['h265'] : []), ...(h264 ? ['h264'] : []), 'jpeg'];
    ws.onopen = () => ws.send(JSON.stringify({ t: 'join', ...join, codecs }));
    ws.onmessage = (event) => {
      if (socket !== ws) return;
      if (typeof event.data === 'string') onText(event.data);
      else onFrame(event.data);
    };
    ws.onclose = (event) => {
      if (socket !== ws) return;
      socket = null;
      resetDecoder();
      onClosed(event.code, event.reason);
    };
  }

  function onClosed(code, reason) {
    if (code === 4401) {
      // A key the gateway refuses: forget it and offer a fresh one in the QR code.
      store.clear();
      key = null;
      waitingSince = 0;
      waitForPhone();
      $('join-error').textContent = reason ? `${reason[0].toUpperCase()}${reason.slice(1)}. Scan the new code.` : '';
      return;
    }
    if (code === 4410 || code === 4404 || !key) {
      waitForPhone();
      return;
    }
    if (code === 4409) {
      showOver('Watching in another window', 'This phone is shown in another browser window or tab now.', true);
      return;
    }
    // Anything else is the network: come back on our own, quickly at first.
    const wait = Math.min(2000, 250 * 2 ** retry);
    retry += 1;
    $('live').className = 'live bad';
    $('live-text').textContent = 'Reconnecting';
    setTimeout(() => { if (!socket && key) connect({ key }); }, wait);
  }

  function showOver(title, text, canRetry) {
    $('over-title').textContent = title;
    $('over-text').textContent = text;
    $('over-again').hidden = !canRetry;
    show('over');
  }

  /**
   * ADR-069: the "Connect your phone" screen. The page makes its own viewer key
   * and shows it, with this gateway's address, as a QR code; Latch scans it and
   * starts mirroring with that key, and the page joins by itself.
   */
  function showConnect() {
    if (!key) {
      const bytes = new Uint8Array(32);
      crypto.getRandomValues(bytes);
      key = base64url(bytes);
      store.set(key);
    }
    const src = `/v1/mirror/qr.svg?k=${encodeURIComponent(key)}`;
    if ($('join-qr').getAttribute('src') !== src) $('join-qr').src = src;
    show('join');
  }

  /**
   * The phone is not mirroring to this key (yet). Show the QR code and ask
   * again every few seconds, so the page joins the moment the owner scans it or
   * starts mirroring (a phone that knows this computer, ADR-058). After
   * WAIT_FOR_PHONE_MS it waits for a click instead.
   */
  function waitForPhone() {
    const now = Date.now();
    if (!waitingSince) waitingSince = now;
    showConnect();
    $('join-error').textContent = '';
    clearTimeout(waitTimer);
    if (now - waitingSince > WAIT_FOR_PHONE_MS) {
      waitingSince = 0;
      $('join-status').className = 'join-status';
      $('join-status-text').textContent = 'Stopped waiting';
      $('join-retry').hidden = false;
      return;
    }
    $('join-retry').hidden = true;
    $('join-status-text').textContent = 'Waiting for your phone';
    $('live').className = 'live bad';
    $('live-text').textContent = 'Waiting';
    const elapsed = now - waitingSince;
    waitTimer = setTimeout(() => { if (!socket && key) connect({ key }); }, elapsed < 60000 ? 1500 : 5000);
  }

  /** ADR-069: a file the owner sent from the phone; the browser saves it from the gateway, once. */
  function takeFile(m) {
    if (typeof m.url !== 'string' || !m.url.startsWith('/v1/mirror/outbox/')) return;
    const name = typeof m.name === 'string' ? m.name : 'file';
    const a = document.createElement('a');
    a.href = m.url;
    a.download = name;
    document.body.append(a);
    a.click();
    a.remove();
    toast(`From the phone: ${name} (${sizeText(m.size)}), saved to your downloads.`);
  }

  function onText(raw) {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    switch (m.t) {
      case 'joined':
        retry = 0;
        waitingSince = 0;
        clearTimeout(waitTimer);
        if (m.key) { key = m.key; }
        store.set(key);
        if (location.hash) history.replaceState(null, '', location.pathname);
        $('device').textContent = m.device || 'Phone';
        // Either video codec: the frames say which one arrives.
        usingH264 = m.codec === 'h264' || m.codec === 'h265';
        inputMode = 'basic';
        $('smooth').hidden = true;
        saving = false;
        showMode();
        // R39: about 96 kbit/s of Opus instead of 1.5 Mbit/s of raw sound, when this browser decodes it.
        if (opusOk) send({ t: 'audio_codec', codec: 'opus' });
        if (followCopies) send({ t: 'clipboard_follow', on: true });
        show('app');
        cover('Waiting for the picture…');
        canvas.focus();
        break;
      case 'phone':
        phoneOnline = Boolean(m.online);
        if (!phoneOnline) cover('The phone lost its connection. Waiting for it to come back…');
        else {
          cover('Waiting for the picture…');
          // A phone that came back has its speaker on again: tell it once more.
          phoneTold = null;
          tellPhoneSound();
        }
        break;
      case 'screen':
        if (Number.isInteger(m.w) && Number.isInteger(m.h) && m.w > 0 && m.h > 0) screen = { w: m.w, h: m.h };
        break;
      case 'ai':
        onAi(m);
        break;
      case 'pong': {
        const rtt = Math.max(0, Math.round(performance.now() - m.at));
        $('live').className = `live ${latencyClass(rtt)}`;
        $('live-text').textContent = `Live ${rtt} ms`;
        perf.rtt = rtt;
        if (Number.isFinite(m.phone_us)) {
          clockSamples.push({ sent: m.at, rtt, phoneUs: m.phone_us });
          if (clockSamples.length > 12) clockSamples.shift();
          perf.offset = clockOffset(clockSamples);
        }
        break;
      }
      case 'stats':
        perf.phone = m;
        break;
      case 'quality':
        // R38: the phone made the picture smaller because the network is slow.
        saving = Boolean(m.saving);
        showMode();
        if (saving) toast(`Saving bandwidth: ${m.w}×${m.h} at ${m.fps} frames a second while the network is slow.`);
        break;
      case 'notice':
        if (typeof m.text === 'string') toast(m.text);
        break;
      case 'clipboard':
        if (typeof m.text === 'string') copyToComputer(m.text);
        break;
      case 'file_ready':
        takeFile(m);
        break;
      case 'status':
        showStatus(m);
        break;
      case 'input':
        inputMode = m.mode === 'keys' ? 'keys' : 'basic';
        $('smooth').hidden = inputMode !== 'keys';
        break;
      case 'volume':
        // An AI app set the computer's volume (phone_control mirror_volume).
        if (Number.isInteger(m.percent) && m.percent >= 0 && m.percent <= 100) {
          $('volume').value = String(m.percent);
          $('volume').dispatchEvent(new Event('input'));
        }
        break;
      default:
        break;
    }
  }

  // ---- Video ----

  function cover(text) {
    $('cover-text').textContent = text;
    $('cover').hidden = false;
  }

  function uncover() {
    $('cover').hidden = true;
  }

  // R35: per-frame timings for the stats overlay, kept only while it is open.
  const clockSamples = [];
  const perf = {
    on: false, offset: null, rtt: null, phone: null,
    frames: 0, skipped: 0, transit: [], decode: [], draw: [], total: [], pending: new Map(),
  };

  function toggleStats() {
    perf.on = !perf.on;
    if (video) video.postMessage({ t: 'timings', on: perf.on });
    $('stats').hidden = !perf.on;
    send({ t: 'stats', on: perf.on });
    if (perf.on) {
      // Ask for the clock now instead of waiting for the next ping.
      send({ t: 'ping', at: performance.now() });
      $('stats').textContent = 'Measuring…';
    }
  }

  /** A frame arrived: when the phone captured it, on this page's clock (null before clocks are synced). */
  function captured(ts) {
    return perf.offset == null ? null : ts / 1000 - perf.offset;
  }

  function frameArrived(ts, arrived) {
    if (!perf.on) return;
    perf.frames += 1;
    if (perf.pending.size > 240) perf.pending.clear();
    perf.pending.set(ts, arrived);
    const at = captured(ts);
    if (at != null) perf.transit.push(arrived - at);
  }

  function frameShown(ts, decoded, drawn) {
    if (!perf.on) return;
    const arrived = perf.pending.get(ts);
    perf.pending.delete(ts);
    if (arrived != null) perf.decode.push(decoded - arrived);
    perf.draw.push(drawn - decoded);
    const at = captured(ts);
    if (at != null) perf.total.push(drawn - at);
  }

  setInterval(() => {
    if (!perf.on) return;
    const page = {
      synced: perf.offset != null, rtt: perf.rtt, frames: perf.frames, skipped: perf.skipped,
      transit: summarize(perf.transit), decode: summarize(perf.decode), draw: summarize(perf.draw), total: summarize(perf.total),
    };
    $('stats').textContent = statsLines(perf.phone, page).join('\n');
    perf.frames = 0;
    perf.skipped = 0;
    perf.transit = [];
    perf.decode = [];
    perf.draw = [];
    perf.total = [];
  }, 1000);

  if (video) {
    video.onmessage = (event) => {
      const m = event.data;
      switch (m.t) {
        case 'size': picture.w = m.w; picture.h = m.h; break;
        case 'drawn': lastFrameAt = performance.now(); uncover(); break;
        case 'shown': frameShown(m.ts, m.decoded - performance.timeOrigin, m.drawn - performance.timeOrigin); break;
        case 'skipped': perf.skipped += 1; break;
        case 'sync': send({ t: 'sync' }); break;
        case 'error':
          if (m.hevc) {
            // R40: this H.265 stream did not decode after all; H.264 from now on.
            h265 = false;
            send({ t: 'codec', codec: 'h264' });
            video.postMessage({ t: 'reset' });
          } else {
            decodeFailed();
          }
          break;
        case 'unsupported': fallBackToStills("This browser can't decode the phone's video; showing still pictures instead."); break;
        default: break;
      }
    };
  }

  function resetDecoder() {
    if (video) video.postMessage({ t: 'reset' });
    if (decoder && decoder.state !== 'closed') {
      try { decoder.close(); } catch { /* already closed */ }
    }
    decoder = null;
    decoderSps = '';
    waitKey = true;
  }

  // Rotate view: the picture a quarter turn clockwise, for a landscape video on a
  // phone whose rotation is locked.
  let turned = false;

  // ---- Sound (ADR-055): 48 kHz stereo 16-bit PCM, played as it comes ----

  let audio = null;
  let gain = null;
  // Every sound goes into `mix`; the volume slider sits after it, so a recording keeps full volume.
  let mix = null;
  let audioAt = 0;
  let volume = 1;
  try { const v = parseFloat(localStorage.getItem('latch-volume')); if (v >= 0 && v <= 1) volume = v; } catch { /* no storage */ }
  let soundOn = true;

  /** Browsers start sound only after a click or key: called from both. */
  function wakeAudio() {
    if (!soundOn) return;
    if (!audio && typeof AudioContext === 'function') {
      try {
        audio = new AudioContext({ latencyHint: 'interactive', sampleRate: 48000 });
        gain = audio.createGain();
        gain.gain.value = volume;
        gain.connect(audio.destination);
        mix = audio.createGain();
        mix.connect(gain);
        // R47: the ring buffer on the audio thread; until it loads (or without it), the old way.
        if (audio.audioWorklet && typeof AudioWorkletNode === 'function') {
          audio.audioWorklet.addModule('/mirror-audio.js')
            .then(() => {
              sound = new AudioWorkletNode(audio, 'latch-sound', { numberOfInputs: 0, outputChannelCount: [2] });
              sound.connect(mix);
            })
            .catch(() => { sound = null; });
        }
      } catch { audio = null; }
    }
    if (audio && audio.state === 'suspended') audio.resume().then(tellPhoneSound).catch(() => {});
    else tellPhoneSound();
  }

  // The phone's speaker goes quiet only while this page really plays its sound.
  let phoneTold = null;
  function tellPhoneSound() {
    const playing = soundOn && !!audio && audio.state === 'running';
    if (playing === phoneTold) return;
    phoneTold = playing;
    send({ t: 'sound', on: playing });
  }

  let sound = null;

  function playPcm(bytes) {
    if (!soundOn || !audio || audio.state !== 'running') return;
    // The header is 10 bytes, so the samples stay 2-byte aligned in the frame's own buffer.
    const samples = new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength >> 1);
    playSamples(samples, true);
  }

  /** Interleaved 16-bit stereo; [own] when its buffer may move to the audio thread. */
  function playSamples(samples, own) {
    if (sound) {
      // R47: to the audio thread's ring; nothing is converted here.
      if (own) sound.port.postMessage(samples, [samples.buffer]);
      else sound.port.postMessage(samples);
      return;
    }
    const frames = samples.length >> 1;
    if (!frames) return;
    const buffer = audio.createBuffer(2, frames, 48000);
    const left = buffer.getChannelData(0);
    const right = buffer.getChannelData(1);
    for (let i = 0; i < frames; i++) {
      left[i] = samples[i * 2] / 32768;
      right[i] = samples[i * 2 + 1] / 32768;
    }
    const now = audio.currentTime;
    // Keep about 40 ms queued: enough to ride out Wi-Fi jitter, and when the
    // queue grows past 150 ms, jump forward instead of playing late.
    if (audioAt < now + 0.01 || audioAt > now + 0.15) audioAt = now + 0.04;
    const source = audio.createBufferSource();
    source.buffer = buffer;
    source.connect(mix || audio.destination);
    source.start(audioAt);
    audioAt += buffer.duration;
  }

  // ---- R39: Opus from the phone, decoded with WebCodecs ----

  let opusOk = false;
  let opusDecoder = null;

  async function probeOpus() {
    if (typeof AudioDecoder !== 'function' || !window.isSecureContext) return false;
    try {
      return Boolean((await AudioDecoder.isConfigSupported({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 })).supported);
    } catch {
      return false;
    }
  }

  function playOpus(frame) {
    if (!soundOn || !audio || audio.state !== 'running') return;
    if (!opusDecoder || opusDecoder.state === 'closed') {
      try {
        opusDecoder = new AudioDecoder({
          output: (data) => {
            const frames = data.numberOfFrames;
            const samples = new Int16Array(frames * 2);
            try {
              data.copyTo(samples, { planeIndex: 0, format: 's16' });
            } catch {
              // A browser that converts only to float: two planes, then 16-bit here.
              const l = new Float32Array(frames);
              const r = new Float32Array(frames);
              data.copyTo(l, { planeIndex: 0, format: 'f32-planar' });
              data.copyTo(r, { planeIndex: Math.min(1, data.numberOfChannels - 1), format: 'f32-planar' });
              for (let i = 0; i < frames; i++) {
                samples[i * 2] = Math.max(-32768, Math.min(32767, Math.round(l[i] * 32768)));
                samples[i * 2 + 1] = Math.max(-32768, Math.min(32767, Math.round(r[i] * 32768)));
              }
            }
            data.close();
            playSamples(samples, true);
          },
          // Can't decode after all: ask the phone for raw sound instead.
          error: () => { opusOk = false; opusDecoder = null; send({ t: 'audio_codec', codec: 'pcm' }); },
        });
        opusDecoder.configure({ codec: 'opus', sampleRate: 48000, numberOfChannels: 2 });
      } catch {
        opusOk = false;
        opusDecoder = null;
        send({ t: 'audio_codec', codec: 'pcm' });
        return;
      }
    }
    try {
      opusDecoder.decode(new EncodedAudioChunk({ type: 'key', timestamp: frame.ts, data: frame.data }));
    } catch { /* one lost packet is 20 ms of silence */ }
  }

  function draw(source, width, height) {
    const cw = turned ? height : width;
    const ch = turned ? width : height;
    if (canvas.width !== cw || canvas.height !== ch) {
      canvas.width = cw;
      canvas.height = ch;
    }
    picture.w = cw;
    picture.h = ch;
    if (turned) {
      ctx.setTransform(0, 1, -1, 0, height, 0);
      ctx.drawImage(source, 0, 0, width, height);
      ctx.setTransform(1, 0, 0, 1, 0, 0);
    } else {
      ctx.drawImage(source, 0, 0, width, height);
    }
    lastFrameAt = performance.now();
    uncover();
  }

  let saving = false;
  function showMode() {
    $('mode').textContent = !usingH264 ? 'Still pictures' : saving ? 'Saving bandwidth' : '';
  }

  // R37: a decoder error asks for a key frame first; only repeated errors mean this browser can't decode.
  let decodeErrors = [];
  function decodeFailed() {
    const now = performance.now();
    decodeErrors = decodeErrors.filter((t) => now - t < 10000);
    decodeErrors.push(now);
    if (decodeErrors.length >= 3) {
      fallBackToStills("This browser couldn't decode the phone's video; showing still pictures instead.");
      return;
    }
    resetDecoder();
    send({ t: 'sync' });
  }

  function fallBackToStills(why) {
    resetDecoder();
    h264 = false;
    usingH264 = false;
    $('mode').textContent = 'Still pictures';
    toast(why);
    send({ t: 'codec', codec: 'jpeg' });
  }

  function configure(sps) {
    const spsHex = Array.from(sps, (b) => b.toString(16).padStart(2, '0')).join('');
    if (decoder && decoder.state === 'configured' && spsHex === decoderSps) return true;
    resetDecoder();
    try {
      decoder = new VideoDecoder({
        output: (frame) => {
          const decoded = performance.now();
          draw(frame, frame.displayWidth, frame.displayHeight);
          frameShown(frame.timestamp, decoded, performance.now());
          frame.close();
        },
        error: () => decodeFailed(),
      });
      decoder.configure({ codec: codecFromSps(sps), optimizeForLatency: true });
      decoderSps = spsHex;
      return true;
    } catch {
      fallBackToStills("This browser can't decode the phone's video; showing still pictures instead.");
      return false;
    }
  }

  function onFrame(buffer) {
    const frame = parseFrame(buffer);
    if (!frame) return;
    if (frame.kind === KIND_PCM) {
      playPcm(frame.data);
      return;
    }
    if (frame.kind === KIND_OPUS) {
      playOpus(frame);
      return;
    }
    if (frame.kind === KIND_SHOT) {
      save(new Blob([frame.data], { type: 'image/png' }), `Latch screenshot ${stamp(new Date())}.png`);
      toast('Screenshot saved to your downloads.');
      return;
    }
    const arrived = performance.now();
    if (video) {
      // R36: the worker decodes and draws; this page only times the arrival.
      if ((frame.kind === KIND_H264 || frame.kind === KIND_H265) && !usingH264) return;
      frameArrived(frame.ts, arrived);
      video.postMessage({ t: 'frame', buffer }, [buffer]);
      return;
    }
    if (frame.kind === KIND_JPEG) {
      frameArrived(frame.ts, arrived);
      createImageBitmap(new Blob([frame.data], { type: 'image/jpeg' }))
        .then((bitmap) => {
          const decoded = performance.now();
          draw(bitmap, bitmap.width, bitmap.height);
          frameShown(frame.ts, decoded, performance.now());
          bitmap.close();
        })
        .catch(() => {});
      return;
    }
    if (!usingH264) return;
    if (frame.key) {
      const sps = findSps(frame.data);
      if (!sps || !configure(sps)) return;
    }
    if (!decoder || (waitKey && !frame.key)) return;
    if (!frame.key && decoder.decodeQueueSize > 2) {
      // Behind: skip to the next key frame instead of showing the past late.
      perf.skipped += 1;
      waitKey = true;
      send({ t: 'sync' });
      return;
    }
    frameArrived(frame.ts, arrived);
    try {
      decoder.decode(new EncodedVideoChunk({ type: frame.key ? 'key' : 'delta', timestamp: frame.ts, data: frame.data }));
      if (frame.key) waitKey = false;
    } catch {
      waitKey = true;
      send({ t: 'sync' });
    }
  }

  // A black picture for a while: Android shows protected screens (FLAG_SECURE) as black.
  const probe = document.createElement('canvas');
  probe.width = 8;
  probe.height = 8;
  const probeCtx = probe.getContext('2d', { willReadFrequently: true });
  let blackChecks = 0;
  setInterval(() => {
    if (!socket || $('app').hidden) return;
    send({ t: 'ping', at: performance.now() });
    if (!phoneOnline || !lastFrameAt) return;
    probeCtx.drawImage(canvas, 0, 0, 8, 8);
    const px = probeCtx.getImageData(0, 0, 8, 8).data;
    let max = 0;
    for (let i = 0; i < px.length; i += 4) max = Math.max(max, px[i], px[i + 1], px[i + 2]);
    blackChecks = max < 12 ? blackChecks + 1 : 0;
    if (blackChecks >= 2) cover('This screen is protected by the app, or the phone screen is off.');
    else if (blackChecks === 0 && $('cover-text').textContent.startsWith('This screen is protected')) uncover();
  }, 2000);

  // ---- The AI ----

  function onAi(m) {
    session = m.session || 'off';
    ownerControl = Boolean(m.owner);
    const live = session !== 'off';
    $('ai').hidden = !live;
    $('pause').hidden = !live;
    $('stop').hidden = !live;
    $('pause').textContent = session === 'paused' ? 'Let AI continue' : 'Pause AI';
    $('ai-title').textContent = session === 'paused'
      ? (ownerControl ? "You're in control" : 'AI is paused')
      : (m.working ? 'AI is working' : 'AI is waiting');
    $('ai-now').textContent = session === 'paused'
      ? 'The AI waits until you let it continue.'
      : (m.working && m.steps && m.steps[0] ? m.steps[0].text : '');
    $('takeover').hidden = session === 'paused';
    $('continue').hidden = session !== 'paused';
    const list = $('steps');
    list.replaceChildren();
    for (const step of (m.steps || []).slice(0, 8)) {
      const li = document.createElement('li');
      const who = document.createElement('span');
      who.className = 'who';
      who.textContent = `${step.by === 'owner' ? 'You' : step.by === 'ai' ? 'AI' : 'Latch'}${step.app ? ` · ${step.app}` : ''} — `;
      li.append(who, document.createTextNode(String(step.text || '')));
      list.append(li);
    }
  }

  // ---- Input ----

  let pointerId = null;
  let lastWheelAt = -Infinity;
  const WHEEL_CLICK_GUARD_MS = 150;
  let lastMoveAt = 0;
  let pendingMove = null;
  let pendingMoveTimer = 0;
  let lastPoint = null;

  /** The picture's rect on the page: object-fit: contain may add bars, e.g. in full screen. */
  function pictureRect() {
    return containedRect(canvas.getBoundingClientRect(), picture.w || canvas.width, picture.h || canvas.height);
  }

  function point(event) {
    return mapPoint(event.clientX, event.clientY, pictureRect(), screen, turned);
  }

  // R54: an instant touch dot. The phone's own picture of a press or drag comes back a
  // round trip later; the dot shows where the finger is at once, as Android's "Show taps".
  const dot = document.createElement('div');
  dot.className = 'touch-dot';
  dot.setAttribute('aria-hidden', 'true');
  canvas.parentElement.append(dot);
  function showDot(event) {
    dot.style.transform = dotTransform(event.clientX, event.clientY, canvas.parentElement.getBoundingClientRect());
    dot.classList.add('on');
  }
  function hideDot() {
    dot.classList.remove('on');
  }

  canvas.addEventListener('pointerdown', (event) => {
    wakeAudio();
    if (pointerId !== null || event.button !== 0) return;
    // A trackpad can report a tap as the fingers lift from a scroll; that is not a click.
    if (event.pointerType === 'mouse' && performance.now() - lastWheelAt < WHEEL_CLICK_GUARD_MS) {
      event.preventDefault();
      return;
    }
    const p = point(event);
    if (!p) return;
    pointerId = event.pointerId;
    canvas.setPointerCapture(pointerId);
    canvas.focus();
    lastPoint = p;
    send({ t: 'touch', phase: 'down', ...p });
    showDot(event);
    event.preventDefault();
  });

  canvas.addEventListener('pointermove', (event) => {
    const p = point(event);
    if (p) lastPoint = p;
    if (event.pointerId !== pointerId || !p) return;
    showDot(event);
    const now = performance.now();
    // With real touch events the phone keeps up with every pointer move up to 120 a second.
    const gap = inputMode === 'keys' ? 8 : 12;
    if (now - lastMoveAt >= gap) {
      lastMoveAt = now;
      pendingMove = null;
      send({ t: 'touch', phase: 'move', ...p });
    } else {
      pendingMove = p;
      // A drag that pauses right after a skipped move still reaches where the pointer stopped.
      if (!pendingMoveTimer) {
        pendingMoveTimer = setTimeout(() => {
          pendingMoveTimer = 0;
          if (!pendingMove || pointerId === null) return;
          lastMoveAt = performance.now();
          send({ t: 'touch', phase: 'move', ...pendingMove });
          pendingMove = null;
        }, Math.max(1, gap - (now - lastMoveAt)));
      }
    }
  });

  function lift(event, phase) {
    if (event.pointerId !== pointerId) return;
    hideDot();
    const p = point(event) || lastPoint;
    if (pendingMove) send({ t: 'touch', phase: 'move', ...pendingMove });
    pendingMove = null;
    clearTimeout(pendingMoveTimer);
    pendingMoveTimer = 0;
    if (p) send({ t: 'touch', phase, ...p });
    pointerId = null;
  }
  canvas.addEventListener('pointerup', (event) => lift(event, 'up'));
  canvas.addEventListener('pointercancel', (event) => lift(event, 'cancel'));
  canvas.addEventListener('contextmenu', (event) => event.preventDefault());

  let wheel = { dx: 0, dy: 0 };
  let wheelTimer = 0;
  // R42: two fingers from the computer: a trackpad pinch (Ctrl+wheel in Chrome, Edge,
  // and Firefox; gesture events in Safari, which also turn).
  let pinch = null;
  let pinchEndTimer = 0;
  let pinchSentAt = 0;
  function pinchStep(phase, p, scale, angle) {
    if (!screen) return;
    if (phase === 'start') {
      if (pinch) return;
      pinch = { p, scale: 1, angle: 0 };
      send(pinchMessage('start', p, 1, 0));
      return;
    }
    if (!pinch) return;
    pinch.scale = scale;
    pinch.angle = angle;
    if (phase === 'end') {
      send(pinchMessage('end', pinch.p, scale, angle));
      pinch = null;
      return;
    }
    const now = performance.now();
    if (now - pinchSentAt >= 16) {
      pinchSentAt = now;
      send(pinchMessage('move', pinch.p, scale, angle));
    }
  }
  canvas.addEventListener('gesturestart', (event) => {
    event.preventDefault();
    const p = point(event) || lastPoint;
    if (p) pinchStep('start', p, 1, 0);
  });
  canvas.addEventListener('gesturechange', (event) => {
    event.preventDefault();
    pinchStep('move', null, event.scale, event.rotation);
  });
  canvas.addEventListener('gestureend', (event) => {
    event.preventDefault();
    pinchStep('end', null, event.scale, event.rotation);
  });

  canvas.addEventListener('wheel', (event) => {
    event.preventDefault();
    if (!screen) return;
    if (event.ctrlKey) {
      if (!pinch) {
        const p = point(event) || lastPoint;
        if (!p) return;
        pinchStep('start', p, 1, 0);
      }
      if (!pinch) return;
      pinchStep('move', null, pinchScale(pinch.scale, event.deltaY), 0);
      clearTimeout(pinchEndTimer);
      // A trackpad sends no "end": a short pause ends the pinch.
      pinchEndTimer = setTimeout(() => { if (pinch) pinchStep('end', null, pinch.scale, pinch.angle); }, 180);
      return;
    }
    const rect = pictureRect();
    const p = point(event) || lastPoint;
    if (!p) return;
    lastWheelAt = performance.now();
    const d = wheelPixels(event, rect, screen, turned);
    wheel.dx += d.dx;
    wheel.dy += d.dy;
    lastPoint = p;
    if (!wheelTimer) {
      wheelTimer = setTimeout(() => {
        wheelTimer = 0;
        const clamp = (v) => Math.max(-16384, Math.min(16384, v));
        if (wheel.dx || wheel.dy) send({ t: 'wheel', ...lastPoint, dx: clamp(wheel.dx), dy: clamp(wheel.dy) });
        wheel = { dx: 0, dy: 0 };
      }, inputMode === 'keys' ? 16 : 60);
    }
  }, { passive: false });

  let typed = '';
  let typedTimer = 0;
  function flushTyped() {
    typedTimer = 0;
    if (typed) send({ t: 'text', text: typed });
    typed = '';
  }

  function onKeyDown(event) {
    wakeAudio();
    if ($('app').hidden || $('help').open) return;
    const action = shortcut(event);
    if (action) {
      event.preventDefault();
      runAction(action);
      return;
    }
    if (event.target instanceof HTMLInputElement) return;
    if (event.target instanceof HTMLButtonElement && (event.key === 'Enter' || event.key === ' ')) return;
    const clip = clipboardAction(event);
    if (clip) {
      // Text selected on this page itself is the browser's to copy.
      const selection = (event.target.ownerDocument || document).getSelection();
      if (selection && String(selection).length) return;
      event.preventDefault();
      flushTyped();
      send({ t: clip });
      return;
    }
    const m = keyMessage(event);
    if (!m) return;
    event.preventDefault();
    if (m.t === 'text') {
      typed += m.text;
      if (Array.from(typed).length >= 64) flushTyped();
      else if (!typedTimer) typedTimer = setTimeout(flushTyped, 16);
      return;
    }
    flushTyped();
    send(m);
  }
  document.addEventListener('keydown', onKeyDown);

  function onPaste(event) {
    if ($('app').hidden) return;
    const data = event.clipboardData;
    if (!data) return;
    const files = Array.from(data.files || []);
    if (files.length) {
      // A copied picture or file goes to the phone's Downloads, as a dropped one does.
      event.preventDefault();
      sendFiles(files);
      return;
    }
    const text = data.getData('text/plain');
    if (!text) return;
    event.preventDefault();
    flushTyped();
    const m = pasteMessage(text);
    if (m) send(m);
    if (m && m.text.length < text.length) toast('Only the first part of that text fits in one paste.');
  }
  document.addEventListener('paste', onPaste);

  // ---- Clipboard from the phone ----

  async function copyToComputer(text) {
    const n = Array.from(text).length;
    const done = () => toast(`Copied ${n} character${n === 1 ? '' : 's'} from the phone.`);
    try {
      await navigator.clipboard.writeText(text);
      done();
      return;
    } catch { /* not allowed without a click, or not over https: ask for one */ }
    toast('The phone sent text for your clipboard.', {
      label: 'Copy',
      run: () => {
        const area = document.createElement('textarea');
        area.value = text;
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.append(area);
        area.select();
        const ok = document.execCommand('copy');
        area.remove();
        if (ok) done();
        else toast("This browser didn't allow copying.");
      },
    });
  }

  // ---- The phone's status ----

  function showStatus(m) {
    const st = statusText(m);
    $('status').hidden = !(st.net || st.battery);
    $('net').textContent = st.net;
    $('battery').textContent = st.battery;
    $('status').classList.toggle('charging', st.charging);
    const bars = $('bars');
    bars.hidden = st.bars === null || !st.net || st.net === 'Offline';
    bars.dataset.level = String(st.bars ?? 0);
    $('status').title = `${st.net}${st.bars !== null ? `, signal ${st.bars} of 4` : ''}${st.battery ? `, battery ${st.battery}${st.charging ? ', charging' : ''}` : ''}`;
  }

  // ---- Files to the phone (dropped, picked, or pasted) ----

  function sendFiles(files) {
    if (!key) {
      toast('Sending files needs the link from the phone; reconnect from Latch.');
      return;
    }
    let chain = Promise.resolve();
    for (const file of files) chain = chain.then(() => sendFile(file));
  }

  function sendFile(file) {
    return new Promise((resolve) => {
      const panel = $('transfer');
      const label = $('transfer-text');
      const bar = $('transfer-bar');
      panel.hidden = false;
      label.textContent = `Sending ${file.name}…`;
      bar.value = 0;
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `/v1/mirror/files?name=${encodeURIComponent(file.name || 'file')}`);
      xhr.setRequestHeader('x-latch-mirror-key', key);
      xhr.upload.onprogress = (e) => {
        if (!e.lengthComputable) return;
        bar.value = e.loaded / e.total;
        if (e.loaded === e.total) label.textContent = `Saving ${file.name} on the phone…`;
      };
      const finish = (text) => {
        panel.hidden = true;
        toast(text);
        resolve();
      };
      xhr.onload = () => {
        let body = {};
        try { body = JSON.parse(xhr.responseText); } catch { /* not JSON */ }
        if (xhr.status === 200 && body.ok) finish(`${file.name}: ${body.text || 'saved on the phone.'}`);
        else finish(`${file.name} was not sent: ${body.error || `the gateway answered ${xhr.status}`}.`);
      };
      xhr.onerror = () => finish(`${file.name} was not sent: the connection to the gateway broke.`);
      xhr.send(file);
    });
  }

  let dragDepth = 0;
  const hasFiles = (event) => event.dataTransfer && Array.from(event.dataTransfer.types || []).includes('Files');
  document.addEventListener('dragenter', (event) => {
    if ($('app').hidden || !hasFiles(event)) return;
    dragDepth += 1;
    $('drop').hidden = false;
  });
  document.addEventListener('dragleave', () => {
    dragDepth = Math.max(0, dragDepth - 1);
    if (!dragDepth) $('drop').hidden = true;
  });
  document.addEventListener('dragover', (event) => {
    if (hasFiles(event)) event.preventDefault();
  });
  document.addEventListener('drop', (event) => {
    if (!hasFiles(event)) return;
    event.preventDefault();
    dragDepth = 0;
    $('drop').hidden = true;
    if (!$('app').hidden) sendFiles(Array.from(event.dataTransfer.files));
  });
  $('file-pick').addEventListener('change', () => {
    sendFiles(Array.from($('file-pick').files || []));
    $('file-pick').value = '';
  });

  // ---- Recording ----

  let recorder = null;
  function toggleRecording() {
    if (recorder) {
      recorder.stop();
      return;
    }
    if (typeof MediaRecorder !== 'function' || typeof canvas.captureStream !== 'function') {
      toast("This browser can't record the screen.");
      return;
    }
    const stream = canvas.captureStream(60);
    wakeAudio();
    let tap = null;
    if (audio && mix) {
      tap = audio.createMediaStreamDestination();
      mix.connect(tap);
      for (const track of tap.stream.getAudioTracks()) stream.addTrack(track);
    }
    const type = ['video/mp4;codecs=avc1,mp4a.40.2', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm']
      .find((t) => MediaRecorder.isTypeSupported(t)) || '';
    let rec;
    try {
      rec = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 12_000_000 });
    } catch {
      toast("This browser can't record the screen.");
      return;
    }
    const chunks = [];
    rec.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
    rec.onstop = () => {
      recorder = null;
      if (tap && mix) { try { mix.disconnect(tap); } catch { /* already gone */ } }
      for (const track of stream.getTracks()) track.stop();
      $('record').setAttribute('aria-pressed', 'false');
      $('record').setAttribute('aria-label', 'Record the screen');
      const kind = rec.mimeType.startsWith('video/mp4') ? 'mp4' : 'webm';
      save(new Blob(chunks, { type: rec.mimeType }), `Latch recording ${stamp(new Date())}.${kind}`);
      toast('Recording saved to your downloads.');
    };
    rec.start(1000);
    recorder = rec;
    $('record').setAttribute('aria-pressed', 'true');
    $('record').setAttribute('aria-label', 'Stop recording');
    toast('Recording. Press the button again (or Alt+Shift+P) to stop and save.');
  }

  // ---- Pop-out window (Document Picture-in-Picture, Chrome and Edge) ----

  let pip = null;
  async function popOut() {
    if (pip) {
      pip.close();
      return;
    }
    if (!('documentPictureInPicture' in window)) {
      toast('A pop-out window needs Chrome or Edge.');
      return;
    }
    const phone = $('phone');
    const box = canvas.getBoundingClientRect();
    const height = Math.min(900, Math.max(480, Math.round(box.height + 80)));
    const width = Math.round(height * (picture.w && picture.h ? picture.w / picture.h : 0.5)) + 24;
    try {
      pip = await window.documentPictureInPicture.requestWindow({ width, height });
    } catch {
      toast("The browser didn't open the pop-out window.");
      return;
    }
    for (const sheet of document.styleSheets) {
      try {
        const style = document.createElement('style');
        style.textContent = Array.from(sheet.cssRules, (r) => r.cssText).join('\n');
        pip.document.head.append(style);
      } catch { /* a sheet from elsewhere; this page has none */ }
    }
    pip.document.body.className = 'pip';
    const spot = document.createElement('div');
    spot.className = 'pip-spot';
    spot.textContent = 'The phone is in its own window.';
    phone.before(spot);
    pip.document.body.append(phone);
    pip.document.addEventListener('keydown', onKeyDown);
    pip.document.addEventListener('paste', onPaste);
    $('pop-out').setAttribute('aria-pressed', 'true');
    canvas.focus();
    pip.addEventListener('pagehide', () => {
      spot.replaceWith(phone);
      pip = null;
      $('pop-out').setAttribute('aria-pressed', 'false');
    });
  }

  // ---- Every action, from a button or a shortcut ----

  let notificationsShown = false;
  try { notificationsShown = localStorage.getItem('latch-notifications') === 'shown'; } catch { /* no storage */ }

  function setVolume(percent) {
    $('volume').value = String(Math.max(0, Math.min(100, percent)));
    $('volume').dispatchEvent(new Event('input'));
  }

  function runAction(action) {
    switch (action) {
      case 'back': case 'home': case 'recents': case 'notifications': case 'quick_settings':
        send({ t: 'button', name: action });
        break;
      case 'screen_light': $('screen-light').click(); break;
      case 'full': $('full').click(); break;
      case 'rotate': $('rotate').click(); break;
      case 'sound': $('sound').click(); break;
      case 'volume_up': setVolume(Number($('volume').value) + 10); toast(`Volume ${$('volume').value}%`); break;
      case 'volume_down': setVolume(Number($('volume').value) - 10); toast(`Volume ${$('volume').value}%`); break;
      case 'shot':
        send({ t: 'shot' });
        toast('Taking a screenshot…');
        break;
      case 'record': toggleRecording(); break;
      case 'send_file': $('file-pick').click(); break;
      case 'pop_out': popOut(); break;
      case 'show_notifications': $('show-notifications').click(); break;
      case 'help': $('help').showModal(); break;
      case 'stats': toggleStats(); break;
      default: break;
    }
  }

  for (const button of document.querySelectorAll('[data-button]')) {
    button.addEventListener('click', () => send({ t: 'button', name: button.dataset.button }));
  }
  $('pause').addEventListener('click', () => send({ t: 'control', action: session === 'paused' ? 'resume' : 'pause' }));
  $('takeover').addEventListener('click', () => send({ t: 'control', action: 'pause' }));
  $('continue').addEventListener('click', () => send({ t: 'control', action: 'resume' }));
  $('stop').addEventListener('click', () => send({ t: 'control', action: 'stop' }));
  $('end').addEventListener('click', () => send({ t: 'control', action: 'end' }));
  $('full').addEventListener('click', () => {
    if (document.fullscreenElement) document.exitFullscreen();
    // The phone and its buttons together, so Back, Home and Recents stay at hand.
    else {
      $('phone').requestFullscreen({ navigationUI: 'hide' })
        // Escape goes to the phone as Back; holding it leaves full screen (Chrome, Edge).
        .then(() => navigator.keyboard && navigator.keyboard.lock && navigator.keyboard.lock(['Escape']).catch(() => {}))
        .catch(() => toast('Full screen is not available here.'));
    }
  });
  document.addEventListener('fullscreenchange', () => {
    if (!document.fullscreenElement && navigator.keyboard && navigator.keyboard.unlock) navigator.keyboard.unlock();
  });
  for (const el of document.querySelectorAll('[data-action]')) {
    el.addEventListener('click', () => runAction(el.dataset.action));
  }
  $('show-notifications').setAttribute('aria-pressed', String(notificationsShown));
  // R44: copies on the phone come here by themselves; off until the owner turns it on, remembered here.
  let followCopies = false;
  try { followCopies = localStorage.getItem('latch-follow-copies') === '1'; } catch { /* no storage */ }
  $('follow-copies').setAttribute('aria-pressed', String(followCopies));
  $('follow-copies').addEventListener('click', () => {
    followCopies = !followCopies;
    try { localStorage.setItem('latch-follow-copies', followCopies ? '1' : '0'); } catch { /* no storage */ }
    $('follow-copies').setAttribute('aria-pressed', String(followCopies));
    send({ t: 'clipboard_follow', on: followCopies });
    toast(followCopies ? 'Text you copy on the phone now comes to this computer (with Full ADB on).' : 'Copies stay on the phone; Ctrl/Cmd+C still copies.');
  });

  $('show-notifications').addEventListener('click', () => {
    notificationsShown = !notificationsShown;
    $('show-notifications').setAttribute('aria-pressed', String(notificationsShown));
    try { localStorage.setItem('latch-notifications', notificationsShown ? 'shown' : 'hidden'); } catch { /* no storage */ }
    send({ t: 'notifications', show: notificationsShown });
    toast(notificationsShown ? 'Asking the phone to show notifications in the mirror…' : 'Asking the phone to hide notifications again…');
  });
  $('help-close').addEventListener('click', () => $('help').close());
  $('volume').value = String(Math.round(volume * 100));
  $('volume').addEventListener('input', () => {
    volume = Math.max(0, Math.min(1, Number($('volume').value) / 100));
    if (gain) gain.gain.setTargetAtTime(volume, audio.currentTime, 0.02);
    try { localStorage.setItem('latch-volume', String(volume)); } catch { /* no storage */ }
  });
  $('sound').addEventListener('click', () => {
    soundOn = !soundOn;
    $('sound').setAttribute('aria-pressed', String(soundOn));
    $('sound').setAttribute('aria-label', soundOn ? 'Sound on' : 'Sound off');
    if (soundOn) wakeAudio();
    else {
      tellPhoneSound();
      if (audio) audio.suspend().catch(() => {});
    }
  });
  document.addEventListener('click', wakeAudio);
  let screenDark = false;
  $('screen-light').addEventListener('click', () => {
    screenDark = !screenDark;
    $('screen-light').setAttribute('aria-pressed', String(screenDark));
    $('screen-light').setAttribute('aria-label', screenDark ? 'Light the phone screen' : 'Turn the phone screen off');
    send({ t: 'screen_light', on: !screenDark });
    toast(screenDark ? 'Turning the phone screen off; the mirror keeps going.' : 'Lighting the phone screen.');
  });
  $('rotate').addEventListener('click', () => {
    turned = !turned;
    if (video) video.postMessage({ t: 'turned', on: turned });
    $('rotate').setAttribute('aria-pressed', String(turned));
    // Ask for a fresh key frame so the turned picture appears at once.
    send({ t: 'sync' });
  });
  $('over-again').addEventListener('click', () => {
    if (key) { show('app'); connect({ key }); } else waitForPhone();
  });
  $('join-retry').addEventListener('click', () => {
    waitingSince = 0;
    waitForPhone();
  });
  // Back on the tab while waiting: ask at once instead of on the next tick.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && waitingSince && !socket && key) {
      clearTimeout(waitTimer);
      connect({ key });
    }
  });

  (async () => {
    h264 = await probeH264();
    h265 = h264 && Boolean(video) && await probeH265();
    opusOk = await probeOpus();
    $('mode-note').textContent = modeNote();
    key = keyFromHash(location.hash) || store.get();
    if (key) {
      show('app');
      cover('Connecting…');
      connect({ key });
    } else {
      waitForPhone();
    }
  })();
}
