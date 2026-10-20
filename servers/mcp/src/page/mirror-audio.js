// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
//
// R47: plays the phone's sound (48 kHz stereo 16-bit PCM, ADR-055) from a
// ring buffer on the audio thread, so the page's main thread neither
// converts samples nor makes a buffer and a source node every 20 ms.
'use strict';

/** Frames (one left and one right sample) the ring holds: 1 s. */
const RING = 48000;
/** Played from this far behind the newest sound: about 40 ms. */
const TARGET = 1920;
/** More queued than this (about 150 ms) jumps forward instead of playing late. */
const TOO_FAR = 7200;

class LatchSound extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ring = new Int16Array(RING * 2);
    this.write = 0;
    this.read = 0;
    this.queued = 0;
    this.started = false;
    this.port.onmessage = (event) => this.push(event.data);
  }

  /** Interleaved samples from the phone, appended to the ring. */
  push(samples) {
    const frames = samples.length >> 1;
    for (let i = 0; i < frames; i++) {
      this.ring[this.write * 2] = samples[i * 2];
      this.ring[this.write * 2 + 1] = samples[i * 2 + 1];
      this.write = (this.write + 1) % RING;
    }
    this.queued = Math.min(RING, this.queued + frames);
    if (this.queued > TOO_FAR) this.skipTo(TARGET);
  }

  skipTo(left) {
    this.read = (this.write - left + RING) % RING;
    this.queued = left;
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    const left = out[0];
    const right = out[1] || out[0];
    // Wait for a little sound before starting, so Wi-Fi jitter does not cut it.
    if (!this.started) {
      if (this.queued < TARGET) return true;
      this.started = true;
    }
    for (let i = 0; i < left.length; i++) {
      if (this.queued > 0) {
        left[i] = this.ring[this.read * 2] / 32768;
        right[i] = this.ring[this.read * 2 + 1] / 32768;
        this.read = (this.read + 1) % RING;
        this.queued -= 1;
      } else {
        // Ran dry: silence, and wait to build the cushion again.
        left[i] = 0;
        right[i] = 0;
        this.started = false;
      }
    }
    return true;
  }
}

registerProcessor('latch-sound', LatchSound);
