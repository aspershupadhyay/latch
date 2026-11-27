// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Renders latch-demo.html to docs/assets/latch-demo.gif: 120 frames at 12 fps, one palette.
const { chromium } = require('playwright');
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const out = fs.mkdtempSync(path.join(os.tmpdir(), 'latch-demo-'));
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 960, height: 540 }, deviceScaleFactor: 1 });
  await p.goto('file://' + path.join(__dirname, 'latch-demo.html'));
  const fps = 12, dur = 10;
  for (let i = 0; i < fps * dur; i++) {
    await p.evaluate(t => setFrame(t), i / fps);
    await p.screenshot({ path: path.join(out, `f${String(i).padStart(3, '0')}.png`) });
  }
  await b.close();
  execFileSync('python3', ['-I', '-c', `
import glob, sys
from PIL import Image
out, gif = sys.argv[1], sys.argv[2]
frames = [Image.open(f).convert('RGB') for f in sorted(glob.glob(out + '/f*.png'))]
key = Image.new('RGB', (960, 540 * 3))
for i, idx in enumerate([40, 70, 110]): key.paste(frames[idx], (0, 540 * i))
pal = key.quantize(colors=128, method=Image.Quantize.MEDIANCUT)
q = [f.quantize(palette=pal, dither=Image.Dither.NONE) for f in frames]
dur = [83] * len(q); dur[-1] = 2500
q[0].save(gif, save_all=True, append_images=q[1:], duration=dur, loop=0, optimize=True, disposal=1)
`, out, path.join(__dirname, 'latch-demo.gif')], { stdio: 'inherit' });
  fs.rmSync(out, { recursive: true });
})();
