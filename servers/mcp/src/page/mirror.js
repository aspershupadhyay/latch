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
const FLAG_KEY = 1;
const HEADER_BYTES = 10;

/** Splits a binary frame into its header fields and payload; null when malformed. */
function parseFrame(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength <= HEADER_BYTES) return null;
  const view = new DataView(buffer);
  const kind = view.getUint8(0);
  if (kind !== KIND_H264 && kind !== KIND_JPEG) return null;
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

/** A point on the drawn picture as phone pixels; null outside it. */
function mapPoint(clientX, clientY, rect, screen) {
  if (!screen || rect.width <= 0 || rect.height <= 0) return null;
  const fx = (clientX - rect.left) / rect.width;
  const fy = (clientY - rect.top) / rect.height;
  if (fx < 0 || fy < 0 || fx > 1 || fy > 1) return null;
  return {
    x: Math.min(screen.w - 1, Math.max(0, Math.round(fx * screen.w))),
    y: Math.min(screen.h - 1, Math.max(0, Math.round(fy * screen.h))),
  };
}

/** Wheel movement in phone pixels, so a scroll moves the picture as far as on the page. */
function wheelPixels(event, rect, screen) {
  const unit = event.deltaMode === 1 ? 40 : event.deltaMode === 2 ? rect.height : 1;
  const sx = screen.w / Math.max(1, rect.width);
  const sy = screen.h / Math.max(1, rect.height);
  return { dx: Math.round(event.deltaX * unit * sx), dy: Math.round(event.deltaY * unit * sy) };
}

/** What a key press becomes on the phone: text, a key, a button, or nothing. */
function keyMessage(event) {
  if (event.ctrlKey || event.metaKey || event.altKey) return null;
  const keys = {
    Backspace: 'backspace', Delete: 'delete', Enter: 'enter', Tab: 'tab',
    ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down',
  };
  if (keys[event.key]) return { t: 'key', key: keys[event.key] };
  if (event.key === 'Escape') return { t: 'button', name: 'back' };
  // One character (one code point, so emoji count); named keys such as F5 are longer.
  if (Array.from(event.key).length === 1 && !/\p{Cc}/u.test(event.key)) {
    return { t: 'text', text: event.key };
  }
  return null;
}

/** Pasted text as messages the phone takes: no control characters, at most 256 characters each. */
function pasteMessages(text) {
  const clean = Array.from(text.replace(/\r\n?|\n/g, ' ')).filter((c) => !/\p{Cc}/u.test(c));
  const out = [];
  for (let i = 0; i < clean.length; i += 256) out.push({ t: 'text', text: clean.slice(i, i + 256).join('') });
  return out;
}

/** Round trip in ms to the colour of the Live dot. */
function latencyClass(ms) {
  return ms < 80 ? 'good' : ms < 200 ? 'slow' : 'bad';
}

/** Six digits from whatever was typed or pasted; null when it is not a code. */
function normalizeCode(text) {
  const digits = String(text).replace(/[\s-]/g, '');
  return /^\d{6}$/.test(digits) ? digits : null;
}

/** The viewer key from a link's fragment (#k=...), or null. */
function keyFromHash(hash) {
  const m = /(?:^#|&)k=([A-Za-z0-9_-]{43})(?:&|$)/.exec(hash || '');
  return m ? m[1] : null;
}

if (typeof module !== 'undefined') {
  module.exports = {
    parseFrame, nalUnits, findSps, codecFromSps, mapPoint, wheelPixels, keyMessage, pasteMessages,
    latencyClass, normalizeCode, keyFromHash,
  };
}

// ---- The page ----

if (typeof document !== 'undefined') {
  const $ = (id) => document.getElementById(id);
  const KEY_STORE = 'latch-mirror-key';
  const store = {
    get() { try { return sessionStorage.getItem(KEY_STORE); } catch { return null; } },
    set(v) { try { sessionStorage.setItem(KEY_STORE, v); } catch { /* private mode: reconnects need the code again */ } },
    clear() { try { sessionStorage.removeItem(KEY_STORE); } catch { /* nothing kept */ } },
  };

  const canvas = $('screen');
  const ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
  let socket = null;
  let key = null;
  let screen = null;
  let decoder = null;
  let decoderSps = '';
  let waitKey = true;
  let h264 = false;
  let usingH264 = false;
  let lastFrameAt = 0;
  let retry = 0;
  let phoneOnline = true;
  let session = 'off';
  let ownerControl = false;
  let toastTimer = 0;

  function show(view) {
    for (const id of ['join', 'over', 'app']) $(id).hidden = id !== view;
  }

  function toast(text) {
    const el = $('toast');
    el.textContent = text;
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 4000);
  }

  function send(message) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
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
    ws.onopen = () => ws.send(JSON.stringify({ t: 'join', ...join, codecs: h264 ? ['h264', 'jpeg'] : ['jpeg'] }));
    ws.onmessage = (event) => {
      if (socket !== ws) return;
      if (typeof event.data === 'string') onText(event.data);
      else onFrame(event.data);
    };
    ws.onclose = (event) => {
      if (socket !== ws) return;
      socket = null;
      resetDecoder();
      onClosed(event.code, event.reason, Boolean(join.code));
    };
  }

  function onClosed(code, reason, byCode) {
    if (code === 4401) {
      if (!byCode) store.clear();
      key = null;
      show('join');
      $('join-error').textContent = reason || 'The phone did not accept this.';
      return;
    }
    if (code === 4410) {
      store.clear();
      key = null;
      showOver('Mirroring ended', `${reason ? reason[0].toUpperCase() + reason.slice(1) + '. ' : ''}Start it again from Latch on the phone.`, false);
      return;
    }
    if (code === 4409) {
      showOver('Watching in another window', 'This phone is shown in another browser window or tab now.', true);
      return;
    }
    if (!key) {
      show('join');
      $('join-error').textContent = 'Lost the connection to the gateway.';
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

  function onText(raw) {
    let m;
    try { m = JSON.parse(raw); } catch { return; }
    switch (m.t) {
      case 'joined':
        retry = 0;
        if (m.key) { key = m.key; }
        store.set(key);
        if (location.hash) history.replaceState(null, '', location.pathname);
        $('device').textContent = m.device || 'Phone';
        usingH264 = m.codec === 'h264';
        $('mode').textContent = usingH264 ? '' : 'Still pictures';
        show('app');
        cover('Waiting for the picture…');
        canvas.focus();
        break;
      case 'phone':
        phoneOnline = Boolean(m.online);
        if (!phoneOnline) cover('The phone lost its connection. Waiting for it to come back…');
        else cover('Waiting for the picture…');
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
        break;
      }
      case 'notice':
        if (typeof m.text === 'string') toast(m.text);
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

  function resetDecoder() {
    if (decoder && decoder.state !== 'closed') {
      try { decoder.close(); } catch { /* already closed */ }
    }
    decoder = null;
    decoderSps = '';
    waitKey = true;
  }

  function draw(source, width, height) {
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    ctx.drawImage(source, 0, 0, width, height);
    lastFrameAt = performance.now();
    uncover();
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
          draw(frame, frame.displayWidth, frame.displayHeight);
          frame.close();
        },
        error: () => fallBackToStills("This browser couldn't decode the phone's video; showing still pictures instead."),
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
    if (frame.kind === KIND_JPEG) {
      createImageBitmap(new Blob([frame.data], { type: 'image/jpeg' }))
        .then((bitmap) => { draw(bitmap, bitmap.width, bitmap.height); bitmap.close(); })
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
      waitKey = true;
      send({ t: 'sync' });
      return;
    }
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
  let lastMoveAt = 0;
  let pendingMove = null;
  let lastPoint = null;

  function point(event) {
    return mapPoint(event.clientX, event.clientY, canvas.getBoundingClientRect(), screen);
  }

  canvas.addEventListener('pointerdown', (event) => {
    if (pointerId !== null || event.button !== 0) return;
    const p = point(event);
    if (!p) return;
    pointerId = event.pointerId;
    canvas.setPointerCapture(pointerId);
    canvas.focus();
    lastPoint = p;
    send({ t: 'touch', phase: 'down', ...p });
    event.preventDefault();
  });

  canvas.addEventListener('pointermove', (event) => {
    const p = point(event);
    if (p) lastPoint = p;
    if (event.pointerId !== pointerId || !p) return;
    const now = performance.now();
    if (now - lastMoveAt >= 12) {
      lastMoveAt = now;
      pendingMove = null;
      send({ t: 'touch', phase: 'move', ...p });
    } else {
      pendingMove = p;
    }
  });

  function lift(event, phase) {
    if (event.pointerId !== pointerId) return;
    const p = point(event) || lastPoint;
    if (pendingMove) send({ t: 'touch', phase: 'move', ...pendingMove });
    pendingMove = null;
    if (p) send({ t: 'touch', phase, ...p });
    pointerId = null;
  }
  canvas.addEventListener('pointerup', (event) => lift(event, 'up'));
  canvas.addEventListener('pointercancel', (event) => lift(event, 'cancel'));
  canvas.addEventListener('contextmenu', (event) => event.preventDefault());

  let wheel = { dx: 0, dy: 0 };
  let wheelTimer = 0;
  canvas.addEventListener('wheel', (event) => {
    event.preventDefault();
    if (!screen) return;
    const rect = canvas.getBoundingClientRect();
    const p = point(event) || lastPoint;
    if (!p) return;
    const d = wheelPixels(event, rect, screen);
    wheel.dx += d.dx;
    wheel.dy += d.dy;
    lastPoint = p;
    if (!wheelTimer) {
      wheelTimer = setTimeout(() => {
        wheelTimer = 0;
        const clamp = (v) => Math.max(-16384, Math.min(16384, v));
        if (wheel.dx || wheel.dy) send({ t: 'wheel', ...lastPoint, dx: clamp(wheel.dx), dy: clamp(wheel.dy) });
        wheel = { dx: 0, dy: 0 };
      }, 60);
    }
  }, { passive: false });

  let typed = '';
  let typedTimer = 0;
  function flushTyped() {
    typedTimer = 0;
    if (typed) send({ t: 'text', text: typed });
    typed = '';
  }

  document.addEventListener('keydown', (event) => {
    if ($('app').hidden || event.target instanceof HTMLInputElement) return;
    if (event.target instanceof HTMLButtonElement && (event.key === 'Enter' || event.key === ' ')) return;
    const m = keyMessage(event);
    if (!m) return;
    event.preventDefault();
    if (m.t === 'text') {
      typed += m.text;
      if (Array.from(typed).length >= 64) flushTyped();
      else if (!typedTimer) typedTimer = setTimeout(flushTyped, 40);
      return;
    }
    flushTyped();
    send(m);
  });

  document.addEventListener('paste', (event) => {
    if ($('app').hidden) return;
    const text = event.clipboardData && event.clipboardData.getData('text/plain');
    if (!text) return;
    event.preventDefault();
    flushTyped();
    for (const m of pasteMessages(text)) send(m);
  });

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
    else $('frame').requestFullscreen().catch(() => toast('Full screen is not available here.'));
  });
  $('over-again').addEventListener('click', () => {
    if (key) { show('app'); connect({ key }); } else show('join');
  });

  $('join-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const code = normalizeCode($('code').value);
    if (!code) {
      $('join-error').textContent = 'The code has 6 digits.';
      return;
    }
    $('join-error').textContent = '';
    connect({ code });
  });

  (async () => {
    h264 = await probeH264();
    $('mode-note').textContent = modeNote();
    key = keyFromHash(location.hash) || store.get();
    if (key) {
      show('app');
      cover('Connecting…');
      connect({ key });
    } else {
      show('join');
      $('code').focus();
    }
  })();
}
