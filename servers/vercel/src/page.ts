// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// The relay's page at "/" (ADR-069): "Connect your phone", with this relay's
// address as a QR code for the Latch app to scan. Still no scripts, forms, or
// questions: the code holds only the public address, and the phone proves the
// owner key it made for this deploy before it is paired.
import qrcode from "qrcode-generator";

/** The page's policy: its stylesheet only; the QR code is inline SVG. */
export const PAGE_CSP = "default-src 'none'; style-src 'self'; img-src 'self'; script-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

/** What the app reads from the code: the relay's address, marked as a relay to connect to. */
export function connectLink(baseUrl: string): string {
  return `${baseUrl.replace(/\/+$/, "")}/#connect`;
}

/** `text` as an SVG QR code: square modules, a 4-module quiet zone, black on white. */
export function qrSvg(text: string): string {
  const code = qrcode(0, "M");
  code.addData(text, "Byte");
  code.make();
  const count = code.getModuleCount();
  const quiet = 4;
  const size = count + 2 * quiet;
  let path = "";
  for (let row = 0; row < count; row++) {
    for (let col = 0; col < count; col++) {
      if (code.isDark(row, col)) path += `M${col + quiet} ${row + quiet}h1v1h-1z`;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges" role="img" aria-label="QR code of this relay for the Latch app"><rect width="${size}" height="${size}" fill="#fff"/><path fill="#000" d="${path}"/></svg>`;
}

function escape(text: string): string {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

const MARK = `<svg class="mark" viewBox="0 0 24 24" aria-hidden="true"><rect width="24" height="24" rx="6" fill="#C2532D"/><path d="M6.9 18.1V10.6a4.6 4.6 0 0 1 9.2 0v1.7" fill="none" stroke="#FFF8EE" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"/><circle cx="16.1" cy="16.6" r="2.3" fill="#FFF8EE"/></svg>`;

/** The page: the QR code once the relay has its owner key, else the one step left. */
export function connectPage(baseUrl: string, setupRequired: boolean): string {
  const shown = escape(baseUrl.replace(/^https?:\/\//, "").replace(/\/+$/, ""));
  const body = setupRequired
    ? `<h1 id="title">One step left.<br><span>Add the owner key.</span></h1>
      <p class="lead">In Vercel, open this project → Settings → Environment Variables, add the key the Latch app made as <code>LATCH_ADMIN_TOKEN</code>, and redeploy. Then open this page again: it shows the code to scan.</p>`
    : `<h1 id="title">Connect your phone.<br><span>Scan this with Latch.</span></h1>
      <div class="connect">
        <figure class="qr">${qrSvg(connectLink(baseUrl))}<figcaption>${shown}</figcaption></figure>
        <ol class="steps">
          <li>Open <strong>Latch</strong> on the phone that made this relay.</li>
          <li>Tap <strong>Scan QR code</strong> (first screen, or step 3 of Make your relay).</li>
          <li>Point it here. The phone connects as this relay's owner by itself.</li>
        </ol>
      </div>
      <p class="lead small">The code holds only this page's address. A phone connects only with the owner key it made for this relay, so anyone else who scans it gets nothing. Another phone? Add it from <strong>Connect</strong> in the app.</p>`;
  return `<!doctype html>
<!-- SPDX-License-Identifier: AGPL-3.0-only · Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors -->
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow, noarchive, nosnippet, noimageindex">
<meta name="referrer" content="no-referrer">
<meta name="color-scheme" content="dark">
<meta name="theme-color" content="#070809">
<title>Connect your phone · Latch relay</title>
<link rel="stylesheet" href="/gateway.css">
</head>
<body>
<div class="grid" aria-hidden="true"></div>
<div class="glow" aria-hidden="true"></div>

<main>
  <section class="term" aria-labelledby="title">
    <div class="term-bar" aria-hidden="true">
      <span class="dots"><i></i><i></i><i></i></span>
      ${MARK}
      <span class="term-name">latch-relay</span>
      <span class="live"><b></b>${setupRequired ? "setup" : "ready"}</span>
    </div>
    <div class="term-body">
      <p class="prompt" aria-hidden="true"><span>$</span> latch connect</p>
      ${body}
    </div>
  </section>

  <footer>
    <a href="https://aspershupadhyay.github.io/latch/" rel="noopener noreferrer">Latch</a> · free software under the GNU AGPL-3.0 · <a href="https://github.com/aspershupadhyay/latch" rel="noopener noreferrer">source code</a>
  </footer>
</main>
</body>
</html>
`;
}
