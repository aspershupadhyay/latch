// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// The mirror page's pure helpers (R30): node --test servers/mcp/tests/
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const m = require('../src/page/mirror.js');

function frame(kind, flags, ts, payload) {
  const buf = new ArrayBuffer(10 + payload.length);
  const view = new DataView(buf);
  view.setUint8(0, kind);
  view.setUint8(1, flags);
  view.setBigUint64(2, BigInt(ts));
  new Uint8Array(buf, 10).set(payload);
  return buf;
}

test('frames are split into header and payload, and malformed ones refused', () => {
  const f = m.parseFrame(frame(1, 1, 123456, [0, 0, 1, 0x65]));
  assert.equal(f.kind, 1);
  assert.equal(f.key, true);
  assert.equal(f.ts, 123456);
  assert.deepEqual(Array.from(f.data), [0, 0, 1, 0x65]);
  assert.equal(m.parseFrame(frame(1, 0, 1, [9])).key, false);
  assert.equal(m.parseFrame(frame(3, 0, 1, [9])).kind, 3, 'sound (ADR-055)');
  assert.equal(m.parseFrame(frame(4, 1, 1, [0x89])).kind, 4, 'screenshot (ADR-057)');
  assert.equal(m.parseFrame(frame(9, 0, 1, [9])), null, 'unknown kind');
  assert.equal(m.parseFrame(new ArrayBuffer(10)), null, 'header only');
  assert.equal(m.parseFrame('text'), null);
});

test('the SPS of a key frame names the decoder', () => {
  // 4- and 3-byte start codes, SPS (Baseline, constrained, level 4.0), PPS, IDR.
  const key = new Uint8Array([0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x28, 0xda, 0, 0, 0, 1, 0x68, 0xce, 0, 0, 1, 0x65, 0x88]);
  const units = m.nalUnits(key);
  assert.deepEqual(units.map((u) => u[0] & 0x1f), [7, 8, 5]);
  const sps = m.findSps(key);
  assert.deepEqual(Array.from(sps), [0x67, 0x42, 0xc0, 0x28, 0xda]);
  assert.equal(m.codecFromSps(sps), 'avc1.42C028');
  assert.equal(m.findSps(new Uint8Array([0, 0, 1, 0x41, 1])), null, 'a delta frame has none');
});

test('a key frame as Android encodes it parses too', () => {
  // A real 64x64 Baseline key frame from ffmpeg/libx264, if the fixture exists.
  const fixture = path.join(__dirname, '..', '..', '..', 'packages', 'schemas', 'v1', 'mirror', 'key-frame.h264');
  if (!fs.existsSync(fixture)) return;
  const bytes = new Uint8Array(fs.readFileSync(fixture));
  const sps = m.findSps(bytes);
  assert.ok(sps, 'has an SPS');
  assert.match(m.codecFromSps(sps), /^avc1\.42[0-9A-F]{4}$/);
});

test('clicks map to phone pixels and outside the picture to nothing', () => {
  const rect = { left: 100, top: 50, width: 360, height: 800 };
  const screen = { w: 1080, h: 2400 };
  assert.deepEqual(m.mapPoint(100, 50, rect, screen), { x: 0, y: 0 });
  assert.deepEqual(m.mapPoint(280, 450, rect, screen), { x: 540, y: 1200 });
  assert.deepEqual(m.mapPoint(460, 850, rect, screen), { x: 1079, y: 2399 });
  assert.equal(m.mapPoint(99, 60, rect, screen), null);
  assert.equal(m.mapPoint(200, 200, rect, null), null, 'no screen size yet');
});

test('the wheel scrolls as far on the phone as on the page', () => {
  const rect = { width: 360, height: 800 };
  const screen = { w: 1080, h: 2400 };
  assert.deepEqual(m.wheelPixels({ deltaMode: 0, deltaX: 0, deltaY: 100 }, rect, screen), { dx: 0, dy: 300 });
  assert.deepEqual(m.wheelPixels({ deltaMode: 1, deltaX: 1, deltaY: -3 }, rect, screen), { dx: 120, dy: -360 });
});

test('keys become text, keys, or Back, and shortcuts stay with the browser', () => {
  const k = (key, extra = {}) => m.keyMessage({ key, ctrlKey: false, metaKey: false, altKey: false, ...extra });
  assert.deepEqual(k('a'), { t: 'text', text: 'a' });
  assert.deepEqual(k('é'), { t: 'text', text: 'é' });
  assert.deepEqual(k('😀'), { t: 'text', text: '😀' });
  assert.deepEqual(k('Backspace'), { t: 'key', key: 'backspace' });
  assert.deepEqual(k('Enter'), { t: 'key', key: 'enter' });
  assert.deepEqual(k('ArrowUp'), { t: 'key', key: 'up' });
  assert.deepEqual(k('Escape'), { t: 'button', name: 'back' });
  assert.equal(k('c', { ctrlKey: true }), null);
  assert.equal(k('r', { metaKey: true }), null);
  assert.equal(k('F5'), null);
  assert.equal(k('Shift'), null);
});

test('editing shortcuts act on words, lines, and the whole field (ADR-057)', () => {
  const k = (key, extra = {}) => m.keyMessage({ key, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...extra });
  assert.deepEqual(k('a', { metaKey: true }), { t: 'key', key: 'select_all' });
  assert.deepEqual(k('a', { ctrlKey: true }), { t: 'key', key: 'select_all' });
  assert.deepEqual(k('Backspace', { altKey: true }), { t: 'key', key: 'word_backspace' }, 'Option+Delete on a Mac');
  assert.deepEqual(k('Backspace', { ctrlKey: true }), { t: 'key', key: 'word_backspace' });
  assert.deepEqual(k('ArrowLeft', { altKey: true }), { t: 'key', key: 'word_left' });
  assert.deepEqual(k('ArrowRight', { ctrlKey: true }), { t: 'key', key: 'word_right' });
  assert.deepEqual(k('ArrowLeft', { metaKey: true }), { t: 'key', key: 'home' });
  assert.deepEqual(k('ArrowDown', { metaKey: true }), { t: 'key', key: 'down' });
  assert.deepEqual(k('Home'), { t: 'key', key: 'home' });
  assert.deepEqual(k('Enter', { shiftKey: true }), { t: 'key', key: 'newline' });
  assert.deepEqual(k('™', { altKey: true }), { t: 'text', text: '™' }, 'Option+2 types its character');
  assert.equal(k('ArrowUp', { altKey: true }), null, 'that one is a page shortcut');
});

test('Alt shortcuts drive the page, and Ctrl/Cmd+C and X copy from the phone', () => {
  const s = (code, extra = {}) => m.shortcut({ code, altKey: true, ctrlKey: false, metaKey: false, shiftKey: false, ...extra });
  assert.equal(s('KeyH'), 'home');
  assert.equal(s('KeyB'), 'back');
  assert.equal(s('KeyN'), 'notifications');
  assert.equal(s('KeyN', { shiftKey: true }), 'quick_settings');
  assert.equal(s('KeyP'), 'shot');
  assert.equal(s('KeyP', { shiftKey: true }), 'record');
  assert.equal(s('ArrowUp'), 'volume_up');
  assert.equal(s('KeyQ'), null);
  assert.equal(s('KeyH', { altKey: false }), null, 'no Alt, no shortcut');
  assert.equal(s('KeyH', { ctrlKey: true }), null, 'Ctrl+Alt is AltGr on Windows: typing');
  assert.equal(s('KeyH', { getModifierState: (k) => k === 'AltGraph' }), null);
  const c = (key, extra = {}) => m.clipboardAction({ key, ctrlKey: false, metaKey: false, altKey: false, shiftKey: false, ...extra });
  assert.equal(c('c', { metaKey: true }), 'copy');
  assert.equal(c('x', { ctrlKey: true }), 'cut');
  assert.equal(c('C', { ctrlKey: true, shiftKey: true }), null, 'Ctrl+Shift+C is the browser’s');
  assert.equal(c('c'), null);
  assert.equal(c('v', { metaKey: true }), null, 'paste comes as a paste event');
});

test('a paste keeps its lines and fits one message the gateway accepts', () => {
  assert.deepEqual(m.pasteMessage('a\r\nb\tc\u0007'), { t: 'paste', text: 'a\nb\tc' });
  assert.equal(m.pasteMessage('x'.repeat(5000)).text.length, 4096);
  const emoji = m.pasteMessage('😀'.repeat(4000));
  assert.ok(new TextEncoder().encode(JSON.stringify(emoji)).length <= 15000);
  assert.equal(m.pasteMessage(''), null);
  assert.equal(m.pasteMessage('\u0000'), null);
});

test('screenshots get a dated name, and the status line reads well', () => {
  assert.equal(m.stamp(new Date(2026, 9, 9, 7, 5, 3)), '2026-10-09 07-05-03');
  assert.deepEqual(m.statusText({ battery: 85, charging: true, network: 'wifi', signal: 3 }),
    { net: 'Wi-Fi', battery: '85%', charging: true, bars: 3 });
  assert.deepEqual(m.statusText({ network: 'none' }), { net: 'Offline', battery: '', charging: false, bars: null });
  assert.equal(m.statusText({ signal: 9 }).bars, 4);
});

test('keys, links, sizes, and latency colours', () => {
  // ADR-069: the page's own key has the shape the phone and gateway accept.
  const made = m.base64url(new Uint8Array(32).fill(251));
  assert.equal(made.length, 43);
  assert.match(made, /^[A-Za-z0-9_-]{43}$/);
  assert.equal(m.keyFromHash(`#k=${made}&pin=abc`), made);
  assert.equal(m.sizeText(512), '512 B');
  assert.equal(m.sizeText(1536), '1.5 KB');
  assert.equal(m.sizeText(25 * 1024 * 1024), '25 MB');
  const key = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO-_';
  assert.equal(m.keyFromHash(`#k=${key}`), key);
  assert.equal(m.keyFromHash('#k=short'), null);
  assert.equal(m.keyFromHash(''), null);
  assert.equal(m.latencyClass(42), 'good');
  assert.equal(m.latencyClass(120), 'slow');
  assert.equal(m.latencyClass(400), 'bad');
});

test('a letterboxed or turned picture maps to the right phone pixels', () => {
  const screen = { w: 1000, h: 500 };
  // A 1000x500 picture in a 400x400 box: bars of 100 above and below.
  const rect = m.containedRect({ left: 0, top: 0, width: 400, height: 400 }, 1000, 500);
  assert.deepEqual(rect, { left: 0, top: 100, width: 400, height: 200 });
  assert.equal(m.mapPoint(200, 50, rect, screen), null, 'on the bar');
  assert.deepEqual(m.mapPoint(200, 200, rect, screen), { x: 500, y: 250 });
  // Turned a quarter clockwise: the shown top-right corner is the phone's top-left.
  const turnedRect = { left: 0, top: 0, width: 500, height: 1000 };
  assert.deepEqual(m.mapPoint(500, 0, turnedRect, screen, true), { x: 0, y: 0 });
  assert.deepEqual(m.mapPoint(0, 1000, turnedRect, screen, true), { x: 999, y: 499 });
});

test('stream stats: clocks line up by the fastest ping, and the overlay reads plainly (R35)', () => {
  // Phone clock = page clock + 5000 ms. The 10 ms round trip is trusted over the 80 ms one.
  const offset = m.clockOffset([
    { sent: 1000, rtt: 80, phoneUs: (1000 + 70 + 5000) * 1000 },
    { sent: 3000, rtt: 10, phoneUs: (3000 + 5 + 5000) * 1000 },
  ]);
  assert.equal(offset, 5000);
  assert.equal(m.clockOffset([]), null);
  assert.equal(m.clockOffset([{ sent: 1, rtt: 2 }]), null, 'an older phone sends no clock');

  assert.equal(m.summarize([]), null);
  const s = m.summarize([4, 1, 3, 2, 10]);
  assert.equal(s.avg, 4);
  assert.equal(s.max, 10);

  const lines = m.statsLines(
    { codec: 'h264', w: 1080, h: 2400, frames: 60, kbps: 8200, target_kbps: 8000, encode_ms: 6.1, encode_max_ms: 12, key_frames: 0, drops: 1, gateway_drops: 2 },
    { synced: true, rtt: 9, frames: 59, skipped: 0, transit: s, decode: s, draw: s, total: s },
  );
  assert.match(lines[0], /h264 1080×2400 {2}60 fps {2}8\.2 Mbit\/s \(aim 8\.0\)/);
  assert.match(lines[1], /encode 6\.1 ms/);
  assert.match(lines[2], /dropped 2/);
  assert.match(lines[5], /glass-to-glass ≈ 4\.0 ms/);
  const udp = m.statsLines({ codec: 'h264', w: 1, h: 1, frames: 1, gateway_drops: 0, transport: 'udp' }, { synced: false, frames: 0, skipped: 0 });
  assert.match(udp[2], /over UDP/, 'R56: the overlay says which way frames come');
  const unsynced = m.statsLines(null, { synced: false, rtt: null, frames: 0, skipped: 0, transit: null, decode: null, draw: null, total: null });
  assert.match(unsynced.join('\n'), /syncing clocks/);
  assert.equal(m.shortcut({ altKey: true, code: 'KeyD' }), 'stats');
});

test('sound plays from a ring buffer with a 40 ms cushion and jumps forward when late (R47)', () => {
  let Processor = null;
  const vm = require('node:vm');
  const context = {
    AudioWorkletProcessor: class { constructor() { this.port = {}; } },
    registerProcessor: (name, cls) => { assert.equal(name, 'latch-sound'); Processor = cls; },
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../src/page/mirror-audio.js'), 'utf8'), context);
  const p = new Processor();
  const quantum = () => { const out = [[new Float32Array(128), new Float32Array(128)]]; p.process([], out); return out[0]; };
  const frames = (n, value) => { const s = new Int16Array(n * 2); s.fill(value); return s; };

  // Less than the cushion: silence while it fills.
  p.push(frames(960, 16384));
  assert.equal(quantum()[0][0], 0);
  p.push(frames(960, 16384));
  const [left, right] = quantum();
  assert.equal(left[0], 0.5);
  assert.equal(right[127], 0.5);
  // Far behind (more than 150 ms queued): only the newest 40 ms is kept.
  p.push(frames(9600, 8192));
  assert.equal(p.queued, 1920);
  assert.equal(quantum()[0][0], 0.25);
  // Run dry: silence, then wait for the cushion again.
  for (let i = 0; i < 20; i++) quantum();
  assert.equal(p.queued, 0);
  assert.equal(p.started, false);
});

test('the video worker parses with the shared helpers (R36)', () => {
  const vm = require('node:vm');
  const page = fs.readFileSync(path.join(__dirname, '../src/page/mirror.js'), 'utf8');
  const worker = fs.readFileSync(path.join(__dirname, '../src/page/mirror-worker.js'), 'utf8');
  const posted = [];
  const context = { performance, postMessage: (m) => posted.push(m), Blob, Uint8Array, DataView, ArrayBuffer, Number };
  context.importScripts = () => vm.runInContext(page, context);
  vm.createContext(context);
  vm.runInContext(worker, context);
  // A frame before the decoder exists is ignored without an error; reset is harmless.
  context.onmessage({ data: { t: 'reset' } });
  context.onmessage({ data: { t: 'frame', buffer: new ArrayBuffer(4) } });
  assert.deepEqual(posted, []);
});

test('an H.265 SPS gives the WebCodecs codec string (R40)', () => {
  // NAL header (type 33), ids, profile 1 (Main), compatibility 0x60000000,
  // constraints 0x90 00 00 [03] 00 00 00 with an emulation-prevention byte, level 93.
  const sps = Uint8Array.from([0x42, 0x01, 0x01, 0x01, 0x60, 0, 0, 0, 0x90, 0, 0, 3, 0, 0, 0, 0x5d, 0xa0]);
  assert.equal(m.hevcCodecFromSps(sps), 'hev1.1.6.L93.90');
  const unit = Uint8Array.from([0, 0, 0, 1, 0x40, 0x01, 0x0c, 0, 0, 0, 1, ...sps]);
  assert.deepEqual(Array.from(m.findHevcSps(unit)), Array.from(sps));
  assert.equal(m.findHevcSps(Uint8Array.from([0, 0, 1, 0x67, 0x42])), null, 'an H.264 SPS is not one');
  assert.equal(m.hevcCodecFromSps(Uint8Array.from([0x42, 0x01, 0x01])), null);
});

test('a trackpad pinch becomes two fingers on the phone (R42)', () => {
  assert.deepEqual(m.pinchMessage('move', { x: 10, y: 20 }, 1.23456, -30.04), { t: 'pinch', phase: 'move', x: 10, y: 20, scale: 1.235, angle: -30 });
  assert.equal(m.pinchMessage('move', { x: 1, y: 1 }, 99, 0).scale, 20, 'never wider than the gateway takes');
  assert.equal(m.pinchMessage('move', { x: 1, y: 1 }, NaN, NaN).scale, 1);
  assert.ok(m.pinchScale(1, -50) > 1, 'pinching out spreads the fingers');
  assert.ok(m.pinchScale(1, 50) < 1);
  assert.equal(m.pinchScale(0.06, 10000), 0.05);
});

test('the instant touch dot sits under the pointer inside its frame (R54)', () => {
  const frame = { left: 100.4, top: 50, width: 400, height: 800 };
  assert.equal(m.dotTransform(300, 450, frame), 'translate(200px, 400px)');
  assert.equal(m.dotTransform(100.4, 50, frame), 'translate(0px, 0px)');
});
