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

test('pasted text is cleaned and split as the gateway accepts it', () => {
  assert.deepEqual(m.pasteMessages('a\r\nb\tc'), [{ t: 'text', text: 'a bc' }]);
  const long = m.pasteMessages('x'.repeat(600));
  assert.deepEqual(long.map((x) => x.text.length), [256, 256, 88]);
  assert.deepEqual(m.pasteMessages(''), []);
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
