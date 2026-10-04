#!/usr/bin/env node
// Checks the public website (site/) before GitHub Pages publishes it: search
// and share tags are present, structured data parses, and every local file a
// page references exists. No dependencies.
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

const site = new URL("../site/", import.meta.url).pathname;
const BASE = "https://aspershupadhyay.github.io/latch/";
const problems = [];
const index = readFileSync(join(site, "index.html"), "utf8");

for (const [what, re] of [
  ["title", /<title>[^<]{20,70}<\/title>/],
  ["meta description", /<meta name="description" content="[^"]{80,170}">/],
  ["canonical", new RegExp(`<link rel="canonical" href="${BASE}">`)],
  ["og:title", /<meta property="og:title"/],
  ["og:image", new RegExp(`<meta property="og:image" content="${BASE}og.png">`)],
  ["twitter:card", /<meta name="twitter:card" content="summary_large_image">/],
  ["one h1", /^(?![\s\S]*<h1[\s\S]*<h1)[\s\S]*<h1/],
  ["lang", /<html lang="en">/],
]) if (!re.test(index)) problems.push(`index.html: missing or malformed ${what}`);

for (const m of index.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)) {
  try { JSON.parse(m[1]); } catch (e) { problems.push(`index.html: structured data does not parse: ${e.message}`); }
}

for (const page of ["index.html", "404.html"]) {
  const html = readFileSync(join(site, page), "utf8");
  for (const m of html.matchAll(/(?:href|src)="([^"]+)"/g)) {
    const ref = m[1];
    if (/^(https?:|mailto:|#)/.test(ref)) continue;
    const file = ref.replace(/^\/latch\//, "").replace(/^\.\//, "") || "index.html";
    if (!existsSync(join(site, file))) problems.push(`${page}: ${ref} does not exist`);
  }
}
for (const f of ["og.png", "favicon.svg", "apple-touch-icon.png", "sitemap.xml", "404.html", ".nojekyll"]) {
  if (!existsSync(join(site, f))) problems.push(`missing site/${f}`);
}

if (problems.length) {
  console.error(problems.join("\n"));
  process.exit(1);
}
console.log("site/ is ready to publish");
