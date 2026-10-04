// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Latchy, the website mascot: a little padlock who wanders along the bottom of the
// page, follows the pointer with its eyes, and every few seconds picks a random
// action (hop, wave, walk, unlatch, hold up Stop, tap, dance, nap, tell a tip).
// Nothing is sent anywhere; the only stored value is "hidden" in localStorage.
(() => {
  "use strict";
  if (window.__latchy) return;
  window.__latchy = true;

  const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const finePointer = matchMedia("(pointer: fine)").matches;
  const rand = (a, b) => a + Math.random() * (b - a);
  const pick = (list) => list[Math.floor(Math.random() * list.length)];
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));
  const store = {
    get() { try { return localStorage.getItem("latchy"); } catch { return null; } },
    set(v) { try { v ? localStorage.setItem("latchy", v) : localStorage.removeItem("latchy"); } catch { /* private mode */ } },
  };

  // Everything Latchy says is true about Latch (see README and SECURITY.md).
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

  const SVG = `
<svg class="pet-svg" viewBox="0 0 120 132" aria-hidden="true" focusable="false">
  <defs>
    <radialGradient id="lb-body" cx="0.34" cy="0.28" r="0.9">
      <stop offset="0" stop-color="#ffd7bf"/><stop offset="0.2" stop-color="#ff9f72"/>
      <stop offset="0.52" stop-color="#ff6a3d"/><stop offset="0.82" stop-color="#d84d27"/><stop offset="1" stop-color="#97361a"/>
    </radialGradient>
    <radialGradient id="lb-limb" cx="0.35" cy="0.3" r="0.9">
      <stop offset="0" stop-color="#ff9a6c"/><stop offset="0.6" stop-color="#e2582f"/><stop offset="1" stop-color="#8f3318"/>
    </radialGradient>
    <linearGradient id="lb-shade" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0.5" stop-color="#5a1500" stop-opacity="0"/><stop offset="1" stop-color="#5a1500" stop-opacity="0.45"/>
    </linearGradient>
    <linearGradient id="lb-metal" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#55555f"/><stop offset="0.22" stop-color="#f3f3f9"/><stop offset="0.45" stop-color="#9b9bab"/>
      <stop offset="0.72" stop-color="#ffffff"/><stop offset="1" stop-color="#62626e"/>
    </linearGradient>
    <radialGradient id="lb-sclera" cx="0.42" cy="0.36" r="0.72">
      <stop offset="0" stop-color="#ffffff"/><stop offset="0.75" stop-color="#f3f0f6"/><stop offset="1" stop-color="#cfc8d8"/>
    </radialGradient>
    <radialGradient id="lb-iris" cx="0.5" cy="0.62" r="0.62">
      <stop offset="0" stop-color="#c26a35"/><stop offset="0.45" stop-color="#6e3216"/><stop offset="1" stop-color="#1a0904"/>
    </radialGradient>
    <radialGradient id="lb-cheek"><stop offset="0" stop-color="#ff7f9c" stop-opacity="0.8"/><stop offset="1" stop-color="#ff7f9c" stop-opacity="0"/></radialGradient>
    <filter id="lb-soft" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="1.3"/></filter>
    <filter id="lb-blur" x="-50%" y="-200%" width="200%" height="500%"><feGaussianBlur stdDeviation="2.4"/></filter>
    <clipPath id="lb-cl"><ellipse cx="46" cy="84" rx="11" ry="12.5"/></clipPath>
    <clipPath id="lb-cr"><ellipse cx="74" cy="84" rx="11" ry="12.5"/></clipPath>
  </defs>
  <ellipse class="p-shadow" cx="60" cy="125" rx="28" ry="4.5" fill="#000" opacity="0.55" filter="url(#lb-blur)"/>
  <g class="p-rig"><g class="p-inner">
    <g class="p-shackle">
      <path d="M40 58 V40 a20 20 0 0 1 40 0 V58" fill="none" stroke="url(#lb-metal)" stroke-width="9" stroke-linecap="round"/>
      <path d="M43.5 44 a16.5 16.5 0 0 1 12 -18" fill="none" stroke="#fff" stroke-opacity="0.7" stroke-width="1.6" stroke-linecap="round"/>
    </g>
    <g class="p-foot p-foot-l"><ellipse cx="47" cy="119.5" rx="10" ry="5.5" fill="url(#lb-limb)"/></g>
    <g class="p-foot p-foot-r"><ellipse cx="73" cy="119.5" rx="10" ry="5.5" fill="url(#lb-limb)"/></g>
    <g class="p-arm p-arm-l"><ellipse cx="22.5" cy="87" rx="6.5" ry="11" fill="url(#lb-limb)"/></g>
    <g class="p-sign">
      <rect x="103" y="34" width="3" height="40" rx="1.5" fill="#9a9aa6"/>
      <polygon points="99,18 108,18 114.5,24.5 114.5,33.5 108,40 99,40 92.5,33.5 92.5,24.5" fill="#ff5a5a" stroke="#fff" stroke-width="1.6" stroke-linejoin="round"/>
      <text x="103.5" y="31.6" text-anchor="middle" font-family="Geist Mono, monospace" font-size="6.4" font-weight="700" fill="#fff">STOP</text>
    </g>
    <g class="p-arm p-arm-r"><ellipse cx="97.5" cy="87" rx="6.5" ry="11" fill="url(#lb-limb)"/></g>
    <g class="p-body">
      <rect x="22" y="50" width="76" height="74" rx="32" fill="url(#lb-body)"/>
      <rect x="22" y="50" width="76" height="74" rx="32" fill="url(#lb-shade)"/>
      <path d="M93 80 a32 32 0 0 1 -17 41" fill="none" stroke="#ffe0d0" stroke-opacity="0.4" stroke-width="2" stroke-linecap="round"/>
      <ellipse cx="40" cy="63" rx="10" ry="5" fill="#fff" opacity="0.55" transform="rotate(-30 40 63)" filter="url(#lb-soft)"/>
      <circle cx="51" cy="57.5" r="1.7" fill="#fff" opacity="0.85"/>
      <g class="p-keyhole" fill="#6b230c" opacity="0.5"><circle cx="60" cy="113" r="2.6"/><path d="M58.6 114 h2.8 l0.9 5 h-4.6z"/></g>
    </g>
    <g class="p-face">
      <ellipse cx="34.5" cy="98" rx="8" ry="4.6" fill="url(#lb-cheek)" class="p-cheek"/>
      <ellipse cx="85.5" cy="98" rx="8" ry="4.6" fill="url(#lb-cheek)" class="p-cheek"/>
      <g class="p-eye">
        <ellipse cx="46" cy="84" rx="11" ry="12.5" fill="url(#lb-sclera)"/>
        <g clip-path="url(#lb-cl)">
          <g class="p-look"><circle cx="46" cy="85.5" r="7.6" fill="url(#lb-iris)"/><circle class="p-pupil" cx="46" cy="85.5" r="3.9" fill="#080303"/>
            <circle cx="48.9" cy="82" r="2.5" fill="#fff"/><circle cx="43.4" cy="88.6" r="1.1" fill="#fff" opacity="0.85"/></g>
        </g>
        <ellipse cx="46" cy="84" rx="11" ry="12.5" fill="none" stroke="#5a1500" stroke-opacity="0.35"/>
      </g>
      <g class="p-eye">
        <ellipse cx="74" cy="84" rx="11" ry="12.5" fill="url(#lb-sclera)"/>
        <g clip-path="url(#lb-cr)">
          <g class="p-look"><circle cx="74" cy="85.5" r="7.6" fill="url(#lb-iris)"/><circle class="p-pupil" cx="74" cy="85.5" r="3.9" fill="#080303"/>
            <circle cx="76.9" cy="82" r="2.5" fill="#fff"/><circle cx="71.4" cy="88.6" r="1.1" fill="#fff" opacity="0.85"/></g>
        </g>
        <ellipse cx="74" cy="84" rx="11" ry="12.5" fill="none" stroke="#5a1500" stroke-opacity="0.35"/>
      </g>
      <g class="p-shut" fill="none" stroke="#4a1406" stroke-width="2.4" stroke-linecap="round"><path d="M37 85 Q46 91.5 55 85"/><path d="M65 85 Q74 91.5 83 85"/></g>
      <path class="m m-smile" d="M54.5 101.5 Q60 106.5 65.5 101.5" fill="none" stroke="#4a1406" stroke-width="2.3" stroke-linecap="round"/>
      <g class="m m-open"><path d="M53 100 Q60 111 67 100 Z" fill="#3d1004" stroke="#3d1004" stroke-width="1.2" stroke-linejoin="round"/>
        <path d="M56.4 105.4 Q60 103 63.6 105.4 Q60 108.6 56.4 105.4 Z" fill="#ff7f8f"/></g>
      <ellipse class="m m-o" cx="60" cy="103.5" rx="3.3" ry="4.3" fill="#3d1004"/>
      <path class="m m-flat" d="M56 103.5 Q60 105 64 103.5" fill="none" stroke="#4a1406" stroke-width="2" stroke-linecap="round"/>
    </g>
  </g></g>
  <circle class="p-ripple" cx="112" cy="58" r="5" fill="none" stroke="#4ade80" stroke-width="1.6"/>
  <g class="p-zzz" font-family="Geist Mono, monospace" font-weight="600" fill="#cfcfe0">
    <text x="90" y="50" font-size="9">z</text><text x="99" y="38" font-size="11">z</text><text x="109" y="24" font-size="14">Z</text>
  </g>
</svg>`;

  // ----- Build ---------------------------------------------------------------
  const root = document.createElement("div");
  root.className = "pet";
  root.innerHTML = `
    <div class="pet-say" aria-hidden="true"><span></span></div>
    <button class="pet-btn" type="button" aria-label="Latchy, the Latch mascot. Press for a tip.">${SVG}</button>
    <button class="pet-x" type="button" aria-label="Hide Latchy" title="Hide Latchy">×</button>
    <p class="pet-sr" aria-live="polite"></p>`;
  const home = document.createElement("button");
  home.className = "pet-home";
  home.type = "button";
  home.textContent = "bring back latchy";
  document.body.append(root, home);

  const btn = root.querySelector(".pet-btn");
  const bubble = root.querySelector(".pet-say");
  const bubbleText = bubble.querySelector("span");
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
      bubbleText.textContent = text;
      bubble.classList.remove("typing");
    } else {
      let i = 0;
      bubbleText.textContent = "";
      typeTimer = setInterval(() => {
        bubbleText.textContent = text.slice(0, ++i);
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
    async unlatch() { await flash("is-unlatch", 1300); say(pick(["Unlatched… and latched. Safe again.", "Click! Still locked tight."])); },
    async stop() { say("Stop is always one tap. Big red button."); await flash("is-stop", 2600); },
    async tap() { say(pick(["tap ✓", "type_text ✓", "scroll ✓"])); await flash("is-tap", 1400); },
    async tip() { say(nextTip()); await flash("is-happy", 1600); },
    async look() {
      const targets = [...document.querySelectorAll("h1, h2, .btn-primary, .logo")]
        .map((el) => el.getBoundingClientRect()).filter((r) => r.bottom > 0 && r.top < innerHeight);
      if (targets.length) { const r = pick(targets); lookAt(r.left + r.width / 2, r.top + r.height / 2); }
      else gaze(rand(-3, 3), -2.6);
      await wait(rand(1100, 2000));
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
  const WEIGHTS = { hop: 3, wave: 3, walk: 6, spin: 1.5, dance: 1.5, unlatch: 2, stop: 1.5, tap: 2, tip: 4, look: 3, yawn: 1 };

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
    setTimeout(() => show(document.querySelector('meta[name="robots"][content="noindex"]') ? "This page ran away. I'll wait here." : "Hi! I'm Latchy. I guard your phone."), 1200);
  }
})();
