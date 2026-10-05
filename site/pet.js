// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Mochi, the website mascot: a little black-and-white critter who wanders along the
// bottom of the page, follows the pointer with its eyes, and every few seconds picks a
// random action (hop, wave, walk, wag, hold up Stop, tap, dance, nap, tell a tip).
// Nothing is sent anywhere; the only stored value is "hidden" in localStorage.
(() => {
  "use strict";
  if (window.__mochi) return;
  window.__mochi = true;

  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const finePointer = matchMedia("(pointer: fine)").matches;
  const rand = (a, b) => a + Math.random() * (b - a);
  const pick = (list) => list[Math.floor(Math.random() * list.length)];
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const store = {
    get() { try { return localStorage.getItem("mochi"); } catch { return null; } },
    set(v) { try { v ? localStorage.setItem("mochi", v) : localStorage.removeItem("mochi"); } catch { /* private mode */ } },
  };

  // Everything Mochi says is true about Latch (see README and SECURITY.md).
  const TIPS = [
    "Every switch starts off. Default deny!",
    "Stop is always one tap. Big red button.",
    "I never type passwords, PINs, or one-time codes.",
    "Payments, installs, permission pop-ups? Those wait for your OK.",
    "Your gateway, your account. No Latch server in the middle.",
    "Claude, ChatGPT, Cursor, Codex… any MCP AI works.",
    "Do a task once, save it as a skill, repeat it in one go.",
    "Files move AES-256 encrypted, up to 2 GB.",
    "Sessions end on their own after 15–120 minutes.",
    "Screen text is untrusted. “Ignore your rules”? Nope.",
    "Until you say yes, the AI can't even see that app.",
    "A pointer shows every tap the AI makes.",
    "Free and open source, AGPL-3.0.",
    "Setup takes about five minutes.",
  ];
  const SECTION_LINES = {
    how: "AI app → your gateway → your phone. That's the whole map.",
    features: "Skills are my favourite. Do it once, repeat forever.",
    safety: "Passwords? PINs? Codes? I don't touch those.",
    setup: "About five minutes. Three copy-pastes. I counted.",
    faq: "Ask away. I'm basically the FAQ with feet.",
    final: "Grab the beta? I'll keep the phone safe.",
  };
  const WAKE = ["Huh? I'm up! I'm up!", "Was I snoring?", "Five more minutes…", "Oh hi! Didn't see you."];
  const YAWN = ["*yaaawn*", "Mmm, cosy here.", "Is it nap o'clock?"];
  const POKES = ["Hehe, that tickles!", "Boop!", "Hi!", "Need a tip? Tap me again."];

  const SVG = `<svg class="pet-svg" viewBox="0 0 120 132" aria-hidden="true" focusable="false">
  <defs>
    <radialGradient id="mo-fur" gradientUnits="userSpaceOnUse" cx="48" cy="72" r="64">
      <stop offset="0" stop-color="#ffffff"/><stop offset="0.45" stop-color="#f3f3f6"/>
      <stop offset="0.8" stop-color="#d6d6de"/><stop offset="1" stop-color="#a4a4b0"/>
    </radialGradient>
    <radialGradient id="mo-limb" cx="0.4" cy="0.3" r="0.9">
      <stop offset="0" stop-color="#ffffff"/><stop offset="0.6" stop-color="#e4e4ea"/><stop offset="1" stop-color="#a9a9b4"/>
    </radialGradient>
    <linearGradient id="mo-shade" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0.55" stop-color="#3a3a48" stop-opacity="0"/><stop offset="1" stop-color="#3a3a48" stop-opacity="0.38"/>
    </linearGradient>
    <linearGradient id="mo-ink" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#55555f"/><stop offset="1" stop-color="#202027"/>
    </linearGradient>
    <radialGradient id="mo-sclera" cx="0.45" cy="0.35" r="0.75">
      <stop offset="0" stop-color="#ffffff"/><stop offset="0.7" stop-color="#f1f0f4"/><stop offset="1" stop-color="#cfcdd8"/>
    </radialGradient>
    <radialGradient id="mo-iris" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="#a6c3dc"/><stop offset="0.5" stop-color="#5b7fa2"/>
      <stop offset="0.85" stop-color="#2f4a66"/><stop offset="1" stop-color="#16222f"/>
    </radialGradient>
    <radialGradient id="mo-cheek"><stop offset="0" stop-color="#ffb3c4" stop-opacity="0.7"/><stop offset="1" stop-color="#ffb3c4" stop-opacity="0"/></radialGradient>
    <filter id="mo-soft" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="1.3"/></filter>
    <filter id="mo-blur" x="-50%" y="-200%" width="200%" height="500%"><feGaussianBlur stdDeviation="2.4"/></filter>
    <clipPath id="mo-cl"><path d="M33 80 C36 72 41.5 70 46 70 C52 70 56 73 58 79 C54 85 50 87 45.5 87 C40 87 35.5 85 33 80 Z"/></clipPath>
    <clipPath id="mo-cr"><path d="M87 80 C84 72 78.5 70 74 70 C68 70 64 73 62 79 C66 85 70 87 74.5 87 C80 87 84.5 85 87 80 Z"/></clipPath>
  </defs>
  <ellipse class="p-shadow" cx="60" cy="126" rx="30" ry="4.5" fill="#000" opacity="0.6" filter="url(#mo-blur)"/>
  <g class="p-rig"><g class="p-inner">
    <g class="p-tail">
      <path d="M88 112 C104 112 110 98 104 84" fill="none" stroke="url(#mo-limb)" stroke-width="9" stroke-linecap="round"/>
      <path d="M106.6 92 C106.4 89 105.6 86.4 104 84" fill="none" stroke="#18181d" stroke-width="9" stroke-linecap="round"/>
    </g>
    <g class="p-ear p-ear-l">
      <path d="M30 70 L31 40 Q32 34 37 37 L56 56 Z" fill="url(#mo-ink)" stroke="#8a8a97" stroke-width="5.6" stroke-linejoin="round"/>
      <path d="M30 70 L31 40 Q32 34 37 37 L56 56 Z" fill="url(#mo-ink)" stroke="url(#mo-ink)" stroke-width="3.6" stroke-linejoin="round"/>
      <path d="M35 62 L35.5 46 L48 57 Z" fill="#e8b9c4" opacity="0.75" stroke="#e8b9c4" stroke-width="2" stroke-linejoin="round"/>
    </g>
    <g class="p-ear p-ear-r">
      <path d="M90 70 L89 40 Q88 34 83 37 L64 56 Z" fill="url(#mo-ink)" stroke="#8a8a97" stroke-width="5.6" stroke-linejoin="round"/>
      <path d="M90 70 L89 40 Q88 34 83 37 L64 56 Z" fill="url(#mo-ink)" stroke="url(#mo-ink)" stroke-width="3.6" stroke-linejoin="round"/>
      <path d="M85 62 L84.5 46 L72 57 Z" fill="#e8b9c4" opacity="0.75" stroke="#e8b9c4" stroke-width="2" stroke-linejoin="round"/>
    </g>
    <g class="p-foot p-foot-l"><ellipse cx="46" cy="121" rx="10" ry="5.5" fill="url(#mo-limb)"/><path d="M43 118.5 v3.5 M48.5 118.5 v3.5" stroke="#9a9aa6" stroke-width="0.9" stroke-linecap="round"/></g>
    <g class="p-foot p-foot-r"><ellipse cx="74" cy="121" rx="10" ry="5.5" fill="url(#mo-limb)"/><path d="M71.5 118.5 v3.5 M77 118.5 v3.5" stroke="#9a9aa6" stroke-width="0.9" stroke-linecap="round"/></g>
    <g class="p-arm p-arm-l"><ellipse cx="22.5" cy="99" rx="6.5" ry="10.5" fill="url(#mo-limb)"/></g>
    <g class="p-sign">
      <rect x="103" y="44" width="2.6" height="34" rx="1.3" fill="#8e8e9a"/>
      <polygon points="99,26 108,26 114.5,32.5 114.5,41.5 108,48 99,48 92.5,41.5 92.5,32.5" fill="#f2f2f5" stroke="#16161b" stroke-width="1.8" stroke-linejoin="round"/>
      <text x="103.5" y="39.6" text-anchor="middle" font-family="Geist Mono, monospace" font-size="6.2" font-weight="700" fill="#e5484d">STOP</text>
    </g>
    <g class="p-arm p-arm-r"><ellipse cx="97.5" cy="99" rx="6.5" ry="10.5" fill="url(#mo-limb)"/></g>
    <g class="p-body">
      <ellipse cx="60" cy="90" rx="38" ry="34" fill="url(#mo-fur)"/>
      <ellipse cx="60" cy="90" rx="38" ry="34" fill="url(#mo-shade)"/>
      <path d="M94 96 a36 32 0 0 1 -22 26" fill="none" stroke="#ffffff" stroke-opacity="0.5" stroke-width="1.6" stroke-linecap="round"/>
      <ellipse cx="40" cy="66" rx="10" ry="4.5" fill="#fff" opacity="0.9" transform="rotate(-28 40 66)" filter="url(#mo-soft)"/>
      <path d="M50 57.5 q3 -3.5 6 -1 M57 56 q3 -4 6.5 -0.5 M63.5 56.5 q3 -3 6 0" fill="none" stroke="#d2d2da" stroke-width="1.2" stroke-linecap="round"/>
    </g>
    <g class="p-face">
      <ellipse cx="35" cy="93" rx="7.5" ry="4.2" fill="url(#mo-cheek)" class="p-cheek"/>
      <ellipse cx="85" cy="93" rx="7.5" ry="4.2" fill="url(#mo-cheek)" class="p-cheek"/>
      <g class="p-whisk" stroke="#b4b4c0" stroke-width="0.8" stroke-linecap="round" fill="none">
        <path d="M29 92 L15 89.5"/><path d="M29 95 L14.5 96"/><path d="M91 92 L105 89.5"/><path d="M91 95 L105.5 96"/>
      </g>
      <g class="p-brow p-brow-l"><path d="M35 66.5 Q43 61.5 52.5 64" fill="none" stroke="#24242b" stroke-width="2.6" stroke-linecap="round"/></g>
      <g class="p-brow p-brow-r"><path d="M85 66.5 Q77 61.5 67.5 64" fill="none" stroke="#24242b" stroke-width="2.6" stroke-linecap="round"/></g>
      <g class="p-eye p-eye-l">
        <path d="M35 72.5 C39 67 44 66 47 66 C52 66 55.5 68.5 57.5 72.5" fill="none" stroke="#bdbdc8" stroke-width="1" stroke-linecap="round"/>
        <path d="M33 80 C36 72 41.5 70 46 70 C52 70 56 73 58 79 C54 85 50 87 45.5 87 C40 87 35.5 85 33 80 Z" fill="url(#mo-sclera)"/>
        <g clip-path="url(#mo-cl)">
          <ellipse cx="57.5" cy="79.5" rx="2.6" ry="3" fill="#eeaab6" opacity="0.7"/>
          <g class="p-look">
            <circle cx="45.5" cy="78.5" r="7.2" fill="url(#mo-iris)"/>
            <circle cx="45.5" cy="78.5" r="4.9" fill="none" stroke="#d6e6f4" stroke-opacity="0.35" stroke-width="2" stroke-dasharray="0.5 1.1"/>
            <circle class="p-pupil" cx="45.5" cy="78.5" r="3.1" fill="#050608"/>
            <circle cx="48.4" cy="75.6" r="2" fill="#fff"/><circle cx="43" cy="81.6" r="0.9" fill="#fff" opacity="0.8"/>
          </g>
          <g class="p-lid">
            <path d="M28 52 H62 V74 C56 69.5 50.5 68.3 46 68.3 C41 68.3 36 70.5 31 76.5 Z" fill="url(#mo-fur)"/>
            <path d="M31 76.5 C36 70.5 41 68.3 46 68.3 C50.5 68.3 56 69.5 62 74" fill="none" stroke="#121216" stroke-width="2.6"/>
          </g>
        </g>
        <path d="M33 80 C35.5 85 40 87 45.5 87 C50 87 54 85 58 79" fill="none" stroke="#8c8c98" stroke-width="0.9"/>
        <path d="M33.5 79 L29.8 76.4" stroke="#121216" stroke-width="1.6" stroke-linecap="round"/>
      </g>
      <g class="p-eye p-eye-r">
        <path d="M85 72.5 C81 67 76 66 73 66 C68 66 64.5 68.5 62.5 72.5" fill="none" stroke="#bdbdc8" stroke-width="1" stroke-linecap="round"/>
        <path d="M87 80 C84 72 78.5 70 74 70 C68 70 64 73 62 79 C66 85 70 87 74.5 87 C80 87 84.5 85 87 80 Z" fill="url(#mo-sclera)"/>
        <g clip-path="url(#mo-cr)">
          <ellipse cx="62.5" cy="79.5" rx="2.6" ry="3" fill="#eeaab6" opacity="0.7"/>
          <g class="p-look">
            <circle cx="74.5" cy="78.5" r="7.2" fill="url(#mo-iris)"/>
            <circle cx="74.5" cy="78.5" r="4.9" fill="none" stroke="#d6e6f4" stroke-opacity="0.35" stroke-width="2" stroke-dasharray="0.5 1.1"/>
            <circle class="p-pupil" cx="74.5" cy="78.5" r="3.1" fill="#050608"/>
            <circle cx="77.4" cy="75.6" r="2" fill="#fff"/><circle cx="72" cy="81.6" r="0.9" fill="#fff" opacity="0.8"/>
          </g>
          <g class="p-lid">
            <path d="M92 52 H58 V74 C64 69.5 69.5 68.3 74 68.3 C79 68.3 84 70.5 89 76.5 Z" fill="url(#mo-fur)"/>
            <path d="M89 76.5 C84 70.5 79 68.3 74 68.3 C69.5 68.3 64 69.5 58 74" fill="none" stroke="#121216" stroke-width="2.6"/>
          </g>
        </g>
        <path d="M87 80 C84.5 85 80 87 74.5 87 C70 87 66 85 62 79" fill="none" stroke="#8c8c98" stroke-width="0.9"/>
        <path d="M86.5 79 L90.2 76.4" stroke="#121216" stroke-width="1.6" stroke-linecap="round"/>
      </g>
      <path d="M57.4 90.4 Q60 89.2 62.6 90.4 Q61.4 93 60 93.4 Q58.6 93 57.4 90.4 Z" fill="#2a2a31"/>
      <path class="m m-smile" d="M55 96 Q57.6 98.6 60 96.2 Q62.4 98.6 65 96" fill="none" stroke="#2a2a31" stroke-width="1.6" stroke-linecap="round"/>
      <g class="m m-open"><path d="M55 95.6 Q60 104.5 65 95.6 Q60 97.2 55 95.6 Z" fill="#26161b" stroke="#26161b" stroke-width="1" stroke-linejoin="round"/>
        <path d="M57.4 100 Q60 98.4 62.6 100 Q60 102.6 57.4 100 Z" fill="#f08ea0"/></g>
      <ellipse class="m m-o" cx="60" cy="98" rx="2.6" ry="3.3" fill="#26161b"/>
      <path class="m m-flat" d="M56.5 97.4 Q60 98.6 63.5 97.4" fill="none" stroke="#2a2a31" stroke-width="1.6" stroke-linecap="round"/>
    </g>
  </g></g>
  <circle class="p-ripple" cx="116" cy="92" r="5" fill="none" stroke="#ffffff" stroke-width="1.6"/>
  <g class="p-zzz" font-family="Geist Mono, monospace" font-weight="600" fill="#d8d8e4">
    <text x="92" y="56" font-size="9">z</text><text x="101" y="44" font-size="11">z</text><text x="110" y="30" font-size="14">Z</text>
  </g>
</svg>`;

  // ----- Build ---------------------------------------------------------------
  const root = document.createElement("div");
  root.className = "pet";
  root.innerHTML = `
    <div class="pet-say" aria-hidden="true"><span><span class="typed"></span><span class="rest"></span></span></div>
    <button class="pet-btn" type="button" aria-label="Mochi, the Latch mascot. Press for a tip.">${SVG}</button>
    <button class="pet-x" type="button" aria-label="Hide Mochi" title="Hide Mochi">×</button>
    <p class="pet-sr" aria-live="polite"></p>`;
  const home = document.createElement("button");
  home.className = "pet-home";
  home.type = "button";
  home.textContent = "bring back mochi";
  document.body.append(root, home);

  const btn = root.querySelector(".pet-btn");
  const bubble = root.querySelector(".pet-say");
  // The untyped rest of a line is laid out but hidden, so the bubble has its full size from the first letter.
  const typed = bubble.querySelector(".typed");
  const rest = bubble.querySelector(".rest");
  const live = root.querySelector(".pet-sr");

  let x = 0, busy = false, sleeping = false, away = false, last = "";
  let lastSeenUser = Date.now(), lastPointer = 0, pointer = null;
  let actTimer = 0, blinkTimer = 0, glanceTimer = 0, sayTimer = 0, typeTimer = 0;
  let tipBag = [];

  const width = () => btn.offsetWidth || 92;
  const maxX = () => Math.max(8, innerWidth - width() - 12);
  function place(nx, ms = 0) {
    x = Math.min(Math.max(8, nx), maxX());
    root.style.transition = ms ? `transform ${ms}ms linear` : "none";
    root.style.transform = `translate3d(${x}px, 0, 0)`;
  }
  function gaze(gx, gy) {
    root.style.setProperty("--gx", gx.toFixed(2));
    root.style.setProperty("--gy", gy.toFixed(2));
  }
  function flash(cls, ms) {
    root.classList.add(cls);
    return wait(ms).then(() => root.classList.remove(cls));
  }
  function nextTip() {
    if (!tipBag.length) tipBag = TIPS.slice().sort(() => Math.random() - 0.5);
    return tipBag.pop();
  }

  // ----- Speech bubble (typed out, terminal style) --------------------------
  function say(text, announce = false) {
    clearTimeout(sayTimer);
    clearInterval(typeTimer);
    bubble.dataset.side = x + width() / 2 > innerWidth / 2 ? "right" : "left";
    bubble.classList.add("on", "typing");
    if (announce) live.textContent = text;
    if (reduce) {
      typed.textContent = text;
      rest.textContent = "";
      bubble.classList.remove("typing");
    } else {
      let i = 0;
      typed.textContent = "";
      rest.textContent = text;
      typeTimer = setInterval(() => {
        typed.textContent = text.slice(0, ++i);
        rest.textContent = text.slice(i);
        if (i >= text.length) { clearInterval(typeTimer); bubble.classList.remove("typing"); }
      }, 24);
    }
    sayTimer = setTimeout(() => bubble.classList.remove("on"), 2600 + text.length * 55);
  }

  // ----- Eyes ----------------------------------------------------------------
  function lookAt(px, py) {
    const r = btn.getBoundingClientRect();
    const cx = r.left + r.width / 2, cy = r.top + r.height * 0.62;
    const dx = px - cx, dy = py - cy, d = Math.hypot(dx, dy) || 1;
    const m = Math.min(1, d / 220) * 3.6;
    gaze((dx / d) * m, (dy / d) * m * 0.8);
    return d;
  }
  function glance() {
    clearTimeout(glanceTimer);
    if (!sleeping && !busy && Date.now() - lastPointer > 3500) {
      Math.random() < 0.3 ? gaze(0, 0) : gaze(rand(-3.4, 3.4), rand(-2.6, 2.4));
    }
    glanceTimer = setTimeout(glance, rand(1200, 3800));
  }
  function blink() {
    clearTimeout(blinkTimer);
    if (!sleeping) {
      flash("is-blink", 130);
      if (Math.random() < 0.18) setTimeout(() => flash("is-blink", 120), 260);
    }
    blinkTimer = setTimeout(blink, rand(1800, 5600));
  }

  // ----- Actions ---------------------------------------------------------------
  const ACTIONS = {
    async hop() { await flash("is-hop", 900); if (Math.random() < 0.35) await flash("is-hop", 900); },
    async wave() { if (Math.random() < 0.5) say(pick(["Hi there!", "Hello!", "Hey!"])); await flash("is-wave", 1700); },
    async walk() {
      let to = rand(8, maxX());
      if (Math.abs(to - x) < 90) to = x > innerWidth / 2 ? x - rand(120, 260) : x + rand(120, 260);
      to = Math.min(Math.max(8, to), maxX());
      const dir = to > x ? 1 : -1, ms = Math.max(700, (Math.abs(to - x) / rand(70, 110)) * 1000);
      root.style.setProperty("--dir", dir);
      gaze(3.2 * dir, 0.4);
      root.classList.add("is-walk");
      place(to, ms);
      await wait(ms);
      root.classList.remove("is-walk");
      if (Math.random() < 0.35) await ACTIONS[pick(["hop", "wave", "look"])]();
    },
    async spin() { await flash("is-spin", 950); say(pick(["Ta-da!", "Wheee!", "Did you see that?"])); },
    async dance() { say(pick(["♪ beep boop ♪", "♪ tap tap swipe ♪", "♪ latch latch ♪"])); await flash("is-dance", 2000); },
    async wag() { if (Math.random() < 0.5) say(pick(["Wag wag!", "*ear twitch*", "Hehe."])); await flash("is-wag", 1500); },
    async stop() { say("Stop is always one tap. Big red button."); await flash("is-stop", 2600); },
    async tap() { say(pick(["tap ✓", "type_text ✓", "scroll ✓"])); await flash("is-tap", 1400); },
    async tip() { say(nextTip()); await flash("is-happy", 1600); },
    async look() {
      const targets = [...document.querySelectorAll("h1, h2, .btn-primary, .logo")]
        .map((el) => el.getBoundingClientRect()).filter((r) => r.bottom > 0 && r.top < innerHeight);
      if (targets.length) { const r = pick(targets); lookAt(r.left + r.width / 2, r.top + r.height / 2); }
      else gaze(rand(-3, 3), -2.6);
      await flash("is-look", rand(1100, 2000));
      if (Math.random() < 0.4) say(pick(["Ooh, what's that?", "Hmm…", "Nice page, right?", "Just looking."]));
    },
    async yawn() { say(pick(YAWN)); await flash("is-yawn", 1700); },
    async sleep() {
      sleeping = true;
      root.classList.add("is-sleep");
      bubble.classList.remove("on");
      const until = Date.now() + rand(14000, 26000);
      while (sleeping && Date.now() < until) await wait(400);
      if (sleeping) wake(pick(YAWN));
    },
  };
  const WEIGHTS = { hop: 3, wave: 3, walk: 6, spin: 1.5, dance: 1.5, wag: 2.5, stop: 1.5, tap: 2, tip: 4, look: 3, yawn: 1 };

  function choose() {
    const idle = Date.now() - lastSeenUser;
    const w = reduce ? { tip: 3, look: 2 } : { ...WEIGHTS, ...(idle > 35000 ? { sleep: 8, yawn: 3 } : {}) };
    delete w[last];
    const total = Object.values(w).reduce((a, b) => a + b, 0);
    let r = Math.random() * total;
    for (const [k, v] of Object.entries(w)) if ((r -= v) <= 0) return k;
    return "look";
  }
  function schedule(ms = rand(3200, 8500)) {
    clearTimeout(actTimer);
    actTimer = setTimeout(run, ms);
  }
  async function run() {
    if (away || document.hidden) return;
    if (busy || sleeping) return schedule(1500);
    busy = true;
    last = choose();
    try { await ACTIONS[last](); } finally { busy = false; }
    schedule();
  }

  function wake(line) {
    if (!sleeping) return;
    sleeping = false;
    root.classList.remove("is-sleep");
    flash("is-wow", 700);
    say(line || pick(WAKE));
  }
  function noticeUser() {
    lastSeenUser = Date.now();
    if (sleeping) wake();
  }

  // ----- Reactions to the visitor -------------------------------------------
  let pokes = [];
  btn.addEventListener("click", () => {
    noticeUser();
    const now = Date.now();
    pokes = pokes.filter((t) => now - t < 2500).concat(now);
    if (pokes.length >= 5 && !reduce) {
      pokes = [];
      say("Whoa… too many taps. Ironic, for me.", true);
      flash("is-dizzy", 1800);
      return;
    }
    flash("is-squish", 380);
    flash("is-love", 1200);
    hearts();
    say(pokes.length === 1 ? nextTip() : pick(POKES.concat(nextTip())), true);
  });

  function hearts() {
    if (reduce) return;
    for (let i = 0; i < 3; i++) {
      const h = document.createElement("i");
      h.className = "pet-heart";
      h.style.setProperty("--dx", `${rand(-28, 28).toFixed(0)}px`);
      h.style.animationDelay = `${i * 110}ms`;
      root.append(h);
      setTimeout(() => h.remove(), 1400);
    }
  }

  addEventListener("pointermove", (e) => {
    if (e.pointerType !== "mouse") return;
    pointer = e; lastPointer = Date.now(); noticeUser();
  }, { passive: true });
  addEventListener("scroll", noticeUser, { passive: true });
  addEventListener("keydown", noticeUser);
  (function track() {
    if (pointer && !sleeping && !busy && Date.now() - lastPointer < 3500) {
      const d = lookAt(pointer.clientX, pointer.clientY);
      root.classList.toggle("is-happy-near", d < 150);
    } else root.classList.remove("is-happy-near");
    requestAnimationFrame(track);
  })();

  let ctaAt = 0;
  document.querySelectorAll(".btn-primary").forEach((a) => a.addEventListener("mouseenter", () => {
    if (away || busy || sleeping || Date.now() - ctaAt < 20000) return;
    ctaAt = Date.now();
    say("Android 11+, free. Go on, tap it!");
    flash("is-hop", 900);
  }));

  if ("IntersectionObserver" in window) {
    const seen = new Set();
    const io = new IntersectionObserver((entries) => entries.forEach((en) => {
      const key = en.target.id || "final";
      if (!en.isIntersecting || seen.has(key) || away) return;
      seen.add(key);
      setTimeout(() => {
        if (sleeping) wake(SECTION_LINES[key]);
        else if (!busy) { say(SECTION_LINES[key]); flash(pick(["is-hop", "is-wave", "is-happy"]), 900); }
      }, rand(300, 900));
    }), { threshold: 0.35 });
    document.querySelectorAll("#how, #features, #safety, #setup, #faq, .final").forEach((s) => io.observe(s));
  }

  // ----- Hide / bring back ---------------------------------------------------
  function hide() {
    away = true;
    store.set("away");
    clearTimeout(actTimer);
    root.classList.add("is-away");
    home.classList.add("on");
  }
  function show(greet) {
    away = false;
    store.set(null);
    home.classList.remove("on");
    root.classList.remove("is-away");
    root.classList.add("is-enter");
    setTimeout(() => root.classList.remove("is-enter"), 900);
    if (greet) setTimeout(() => say(greet), 650);
    schedule(rand(4000, 7000));
  }
  root.querySelector(".pet-x").addEventListener("click", hide);
  home.addEventListener("click", () => show("I'm back! Missed me?"));

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) clearTimeout(actTimer);
    else if (!away) schedule(rand(1500, 3000));
  });
  addEventListener("resize", () => place(x));

  // ----- Start -------------------------------------------------------------
  place(innerWidth - width() - 28);
  gaze(0, 0);
  blink();
  glance();
  if (store.get() === "away") {
    away = true;
    root.classList.add("is-away");
    home.classList.add("on");
  } else {
    root.classList.add("is-away");
    setTimeout(() => show(document.querySelector('meta[name="robots"][content="noindex"]') ? "This page ran away. I'll wait here." : "Hi! I'm Mochi. Tap me for Latch tips."), 1200);
  }
})();
