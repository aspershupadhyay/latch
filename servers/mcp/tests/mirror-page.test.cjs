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

test('codes, links, and latency colours', () => {
  assert.equal(m.normalizeCode(' 123 456 '), '123456');
  assert.equal(m.normalizeCode('123-456'), '123456');
  assert.equal(m.normalizeCode('12345'), null);
  assert.equal(m.normalizeCode('abcdef'), null);
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
