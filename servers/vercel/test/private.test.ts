// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// A gateway is private: its page asks for nothing, every answer says noindex,
// and vercel.json gives the static files the same headers the function sends.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import type { Server } from "node:http";
import { Gateway, PRIVATE_HEADERS, configFromEnv } from "../src/gateway.js";
import { MemoryStore } from "../src/store.js";
import { PAGE_CSP, startLocal } from "../src/local.js";
import { connectLink, qrSvg } from "../src/page.js";

const ADMIN = "lok_" + "ab".repeat(32);
let server: Server;
let base: string;

before(async () => {
  server = await startLocal(new Gateway(new MemoryStore(), configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN })), 0);
  base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
});
after(() => server.close());

test("the page at / asks for nothing and runs nothing", async () => {
  const res = await fetch(`${base}/`);
  assert.equal(res.status, 200);
  assert.match(res.headers.get("content-security-policy") ?? "", /script-src 'none'/);
  const html = await res.text();
  for (const absent of ["<form", "<input", "<script", "password", "LATCH_ADMIN_TOKEN"]) {
    assert.ok(!html.includes(absent), `the page must not contain ${absent}`);
  }
  assert.match(html, /<meta name="robots" content="noindex/);
  assert.equal((await fetch(`${base}/admin.js`)).status, 404, "the old console is gone");
  assert.match(await (await fetch(`${base}/robots.txt`)).text(), /Disallow: \//);
});

test("the page at / shows the relay's own address as a QR code to scan (ADR-069)", async () => {
  const html = await (await fetch(`${base}/`)).text();
  assert.match(html, /Connect your phone/);
  assert.match(html, /<svg[^>]+viewBox="0 0 (\d+) \1"/);
  assert.ok(html.includes(base.replace(/^https?:\/\//, "")), "the address is written under the code");
  assert.equal(connectLink("https://relay.example/"), "https://relay.example/#connect");
  // The same text drawn by the library: modules on a square grid inside a quiet zone.
  const svg = qrSvg(connectLink("https://relay.example"));
  const size = Number(/viewBox="0 0 (\d+) /.exec(svg)![1]);
  assert.ok(size >= 21 + 8 && (size - 8 - 17) % 4 === 0, `a QR version's size: ${size}`);
});

test("before LATCH_ADMIN_TOKEN is set, the page says what is left instead of a code", async () => {
  const setup = await startLocal(new Gateway(new MemoryStore(), configFromEnv({})), 0);
  try {
    const url = `http://127.0.0.1:${(setup.address() as { port: number }).port}/`;
    const html = await (await fetch(url)).text();
    assert.match(html, /One step left/);
    assert.ok(!html.includes("<svg xmlns"), "no QR code until the relay can pair");
  } finally {
    setup.close();
  }
});

test("every answer, page or API, found or not, is private", async () => {
  for (const path of ["/", "/gateway.css", "/healthz", "/v1/info", "/v1/admin/devices", "/mcp", "/nope"]) {
    const res = await fetch(base + path);
    for (const [k, v] of Object.entries(PRIVATE_HEADERS)) assert.equal(res.headers.get(k), v, `${path}: ${k}`);
  }
});

test("public health tells nothing about the owner's phones", async () => {
  const health = await (await fetch(`${base}/healthz`)).json();
  assert.equal(health.status, "ok");
  assert.ok(!("devices_connected" in health));
});

test("vercel.json sends the same headers for the static page", () => {
  const config = JSON.parse(readFileSync(new URL("../vercel.json", import.meta.url), "utf8")) as {
    headers: { source: string; headers: { key: string; value: string }[] }[];
  };
  const all = config.headers.find((h) => h.source === "/(.*)")!;
  const sent = Object.fromEntries(all.headers.map((h) => [h.key.toLowerCase(), h.value]));
  for (const [k, v] of Object.entries(PRIVATE_HEADERS)) assert.equal(sent[k], v, k);
  const page = config.headers.find((h) => h.headers.some((x) => x.key === "Content-Security-Policy"))!;
  assert.equal(page.headers.find((x) => x.key === "Content-Security-Policy")!.value, PAGE_CSP);
});
