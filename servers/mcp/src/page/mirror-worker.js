// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//
// R36: decodes and draws the phone's video off the page's main thread, so
// typing, scrolling, and the AI panel never hold a frame back. The page
// hands over its canvas (OffscreenCanvas) and every video frame as it
// arrives; this worker answers with what the page needs to know.
'use strict';

// The frame parsing and H.264 helpers are shared with the page.
importScripts('/mirror.js');

let ctx = null;
let canvas = null;
let decoder = null;
let decoderSps = '';
let waitKey = true;
let turned = false;
let timings = false;
let size = '';
let lastDrawnPost = 0;
let reported = false;

// Times go to the page on the shared wall clock: a worker's performance.now() starts elsewhere.
const now = () => performance.timeOrigin + performance.now();

onmessage = (event) => {
  const m = event.data;
  switch (m.t) {
    case 'init':
      canvas = m.canvas;
      ctx = canvas.getContext('2d', { alpha: false, desynchronized: true });
      break;
    case 'frame':
      onFrame(m.buffer, m.arrived);
      break;
    case 'turned':
      turned = Boolean(m.on);
      break;
    case 'timings':
      timings = Boolean(m.on);
      break;
    case 'reset':
      reset();
      break;
    default:
      break;
  }
};

function reset() {
  if (decoder && decoder.state !== 'closed') {
    try { decoder.close(); } catch { /* already closed */ }
  }
  decoder = null;
  decoderSps = '';
  waitKey = true;
  reported = false;
}

function draw(source, width, height) {
  if (!ctx) return;
  const cw = turned ? height : width;
  const ch = turned ? width : height;
  if (canvas.width !== cw || canvas.height !== ch) {
    canvas.width = cw;
    canvas.height = ch;
  }
  if (turned) {
    ctx.setTransform(0, 1, -1, 0, height, 0);
    ctx.drawImage(source, 0, 0, width, height);
    ctx.setTransform(1, 0, 0, 1, 0, 0);
  } else {
    ctx.drawImage(source, 0, 0, width, height);
  }
  const shape = `${cw}x${ch}`;
  if (shape !== size) {
    size = shape;
    postMessage({ t: 'size', w: cw, h: ch });
  }
  // The page only needs to know pictures keep coming (its cover and black-screen check).
  const t = performance.now();
  if (!reported || t - lastDrawnPost > 500) {
    reported = true;
    lastDrawnPost = t;
    postMessage({ t: 'drawn' });
  }
}

function shown(ts, decoded) {
  if (timings) postMessage({ t: 'shown', ts, decoded, drawn: now() });
}

function configure(sps) {
  const spsHex = Array.from(sps, (b) => b.toString(16).padStart(2, '0')).join('');
  if (decoder && decoder.state === 'configured' && spsHex === decoderSps) return true;
  reset();
  try {
    decoder = new VideoDecoder({
      output: (frame) => {
        const decoded = now();
        draw(frame, frame.displayWidth, frame.displayHeight);
        shown(frame.timestamp, decoded);
        frame.close();
      },
      error: () => postMessage({ t: 'error' }),
    });
    decoder.configure({ codec: codecFromSps(sps), optimizeForLatency: true });
    decoderSps = spsHex;
    return true;
  } catch {
    postMessage({ t: 'unsupported' });
    return false;
  }
}

function onFrame(buffer) {
  const frame = parseFrame(buffer);
  if (!frame) return;
  if (frame.kind === KIND_JPEG) {
    createImageBitmap(new Blob([frame.data], { type: 'image/jpeg' }))
      .then((bitmap) => {
        const decoded = now();
        draw(bitmap, bitmap.width, bitmap.height);
        shown(frame.ts, decoded);
        bitmap.close();
      })
      .catch(() => {});
    return;
  }
  if (frame.kind !== KIND_H264 || typeof VideoDecoder !== 'function') return;
  if (frame.key) {
    const sps = findSps(frame.data);
    if (!sps || !configure(sps)) return;
  }
  if (!decoder || (waitKey && !frame.key)) return;
  if (!frame.key && decoder.decodeQueueSize > 2) {
    // Behind: skip to the next key frame instead of showing the past late.
    waitKey = true;
    postMessage({ t: 'skipped' });
    postMessage({ t: 'sync' });
    return;
  }
  try {
    decoder.decode(new EncodedVideoChunk({ type: frame.key ? 'key' : 'delta', timestamp: frame.ts, data: frame.data }));
    if (frame.key) waitKey = false;
  } catch {
    waitKey = true;
    postMessage({ t: 'sync' });
  }
}
