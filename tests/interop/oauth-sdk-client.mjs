// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Aspersh Upadhyay and the Latch contributors
// Roadmap R17: OAuth on the Rust container gateway, checked the way
// servers/vercel/test/oauth.test.ts checks the Vercel gateway. The official
// MCP TypeScript SDK client connects with only the server URL: it discovers
// the server, registers, the owner approves in the app (the owner API), and it
// gets tokens. Then: refresh, revocation, PKCE, and redirect checks.
//
//   LATCH_URL=http://127.0.0.1:8799 LATCH_ADMIN_TOKEN=... node oauth-sdk-client.mjs
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { UnauthorizedError } from "@modelcontextprotocol/sdk/client/auth.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";

const base = process.env.LATCH_URL ?? "http://127.0.0.1:8787";
const ADMIN = process.env.LATCH_ADMIN_TOKEN;
if (!ADMIN) throw new Error("set LATCH_ADMIN_TOKEN");
const REDIRECT = "http://127.0.0.1:53682/callback";

const admin = async (path, init = {}) => {
  const res = await fetch(base + path, { ...init, headers: { authorization: `Bearer ${ADMIN}`, "content-type": "application/json" } });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
};

/** A minimal in-memory provider, as an MCP client app implements it. */
class MemoryProvider {
  info; saved; verifier = ""; authorizationUrl;
  get redirectUrl() { return REDIRECT; }
  get clientMetadata() {
    return { client_name: "SDK test app", redirect_uris: [REDIRECT], grant_types: ["authorization_code", "refresh_token"], response_types: ["code"], token_endpoint_auth_method: "none" };
  }
  clientInformation() { return this.info; }
  saveClientInformation(info) { this.info = info; }
  tokens() { return this.saved; }
  saveTokens(tokens) { this.saved = tokens; }
  redirectToAuthorization(url) { this.authorizationUrl = url; }
  saveCodeVerifier(v) { this.verifier = v; }
  codeVerifier() { return this.verifier; }
}

/** Plays the browser: opens the consent page, waits for the decision, follows it to the redirect. */
async function browserCode(authorizationUrl, decide) {
  const pageRes = await fetch(authorizationUrl);
  assert.equal(pageRes.status, 200);
  assert.match(pageRes.headers.get("content-security-policy") ?? "", /frame-ancestors 'none'/);
  const html = await pageRes.text();
  assert.match(html, /SDK test app wants to use your phone/);
  const requestPath = html.match(/action="(\/oauth\/requests\/lar_[0-9a-f]+)"/)[1];
  assert.equal((await (await fetch(base + requestPath)).json()).status, "pending");
  await decide();
  const status = await (await fetch(base + requestPath)).json();
  return new URL(status.redirect);
}

const form = (fields) => ({ method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(fields) });

// ---- Discovery ----
const unauth = await fetch(`${base}/mcp`, { method: "POST", body: "{}" });
assert.equal(unauth.status, 401);
assert.match(unauth.headers.get("www-authenticate") ?? "", /^Bearer resource_metadata="http:\/\/127\.0\.0\.1:\d+\/\.well-known\/oauth-protected-resource"/);
const prm = await (await fetch(`${base}/.well-known/oauth-protected-resource`)).json();
assert.equal(prm.resource, `${base}/mcp`);
assert.deepEqual(prm.authorization_servers, [base]);
assert.deepEqual(await (await fetch(`${base}/.well-known/oauth-protected-resource/mcp`)).json(), prm);
const as = await (await fetch(`${base}/.well-known/oauth-authorization-server`)).json();
assert.equal(as.issuer, base);
assert.deepEqual(as.code_challenge_methods_supported, ["S256"]);
assert.equal(as.registration_endpoint, `${base}/oauth/register`);
assert.equal((await fetch(`${base}/.well-known/openid-configuration`)).status, 200);
assert.equal((await fetch(`${base}/oauth/token`, { method: "OPTIONS" })).headers.get("access-control-allow-origin"), "*");
console.log("OK: discovery");

// ---- An MCP client signs in with only the URL, approved in the app ----
{
  const provider = new MemoryProvider();
  const first = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { authProvider: provider });
  await assert.rejects(new Client({ name: "t", version: "0" }).connect(first), UnauthorizedError);
  assert.ok(provider.info?.client_id, "dynamic client registration happened");
  assert.ok(provider.authorizationUrl, "the client was sent to the consent page");

  const redirect = await browserCode(provider.authorizationUrl, async () => {
    const pending = (await admin("/v1/admin/oauth/requests")).body.requests;
    assert.equal(pending.length, 1);
    assert.equal(pending[0].client_name, "SDK test app");
    assert.equal(pending[0].return_to, "127.0.0.1:53682");
    assert.match(pending[0].match, /^[A-Z2-9]{4}$/);
    assert.equal((await admin(`/v1/admin/oauth/requests/${pending[0].id}`, { method: "POST", body: JSON.stringify({ approve: true }) })).status, 204);
  });
  assert.equal(`${redirect.origin}${redirect.pathname}`, REDIRECT);
  assert.equal(redirect.searchParams.get("iss"), base);
  await first.finishAuth(redirect.searchParams.get("code"));

  const client = new Client({ name: "t", version: "0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { authProvider: provider }));
  const tools = (await client.listTools()).tools.map((t) => t.name);
  assert.ok(tools.includes("observe"));
  const devices = await client.callTool({ name: "list_devices", arguments: {} });
  assert.match(devices.content[0].text, /connected/);

  // The approval shows up as an AI app the owner can see and revoke.
  const apps = (await admin("/v1/admin/clients")).body.clients;
  const app = apps.find((c) => c.name === "SDK test app");
  assert.equal(app.kind, "oauth");
  assert.ok(app.last_used_ms, "use is recorded");

  // Codes are single use.
  const reuse = await fetch(`${base}/oauth/token`, form({
    grant_type: "authorization_code", code: redirect.searchParams.get("code"), client_id: provider.info.client_id, code_verifier: provider.verifier, redirect_uri: REDIRECT,
  }));
  assert.equal(reuse.status, 400);
  assert.equal((await reuse.json()).error, "invalid_grant");

  // Refresh rotates the refresh token.
  const oldRefresh = provider.saved.refresh_token;
  const refreshed = await fetch(`${base}/oauth/token`, form({ grant_type: "refresh_token", refresh_token: oldRefresh, client_id: provider.info.client_id }));
  assert.equal(refreshed.status, 200);
  const fresh = await refreshed.json();
  assert.notEqual(fresh.refresh_token, oldRefresh);
  assert.equal((await fetch(`${base}/oauth/token`, form({ grant_type: "refresh_token", refresh_token: oldRefresh }))).status, 400, "an old refresh token stops working");

  // Revoking the AI app cuts its access token and refresh token at once.
  assert.equal((await admin(`/v1/admin/clients/${app.id}`, { method: "DELETE" })).status, 204);
  const ping = JSON.stringify({ jsonrpc: "2.0", id: 9, method: "ping" });
  assert.equal((await fetch(`${base}/mcp`, { method: "POST", headers: { authorization: `Bearer ${fresh.access_token}` }, body: ping })).status, 401);
  assert.equal((await fetch(`${base}/oauth/token`, form({ grant_type: "refresh_token", refresh_token: fresh.refresh_token }))).status, 400);
  await client.close();
  console.log("OK: sign-in with only the URL, approved in the app; refresh; revocation");
}

// ---- A browser can only deny; approving takes the app, never the owner key in a page ----
{
  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  const reg = await (await fetch(`${base}/oauth/register`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ client_name: "<b>Browser app</b>", redirect_uris: ["https://app.example/cb"] }),
  })).json();
  const authorize = (state) => `${base}/oauth/authorize?${new URLSearchParams({
    response_type: "code", client_id: reg.client_id, redirect_uri: "https://app.example/cb", code_challenge: challenge, code_challenge_method: "S256", state, resource: `${base}/mcp`,
  })}`;

  const html = await (await fetch(authorize("s1"))).text();
  assert.doesNotMatch(html, /<b>Browser app<\/b>/, "names are escaped");
  assert.doesNotMatch(html, /owner_key|type="password"/, "the page never asks for the owner key");
  const action = html.match(/action="(\/oauth\/requests\/lar_[0-9a-f]+)"/)[1];
  const post = (fields) => fetch(base + action, { ...form(fields), redirect: "manual" });
  // Even the right owner key posted from a page approves nothing.
  assert.equal((await post({ decision: "approve", owner_key: ADMIN })).status, 200);
  assert.equal((await (await fetch(base + action)).json()).status, "pending");
  const id = action.slice("/oauth/requests/".length);
  assert.equal((await admin(`/v1/admin/oauth/requests/${id}`, { method: "POST", body: JSON.stringify({ approve: true }) })).status, 204);
  const back = new URL((await (await fetch(base + action)).json()).redirect);
  assert.equal(back.origin + back.pathname, "https://app.example/cb");
  assert.equal(back.searchParams.get("state"), "s1");

  // PKCE: a wrong verifier fails; the code is then spent.
  const exchange = (v) => fetch(`${base}/oauth/token`, form({
    grant_type: "authorization_code", code: back.searchParams.get("code"), client_id: reg.client_id, redirect_uri: "https://app.example/cb", code_verifier: v,
  }));
  assert.equal((await exchange(randomBytes(32).toString("base64url"))).status, 400);

  // Deny sends the app an access_denied error.
  const html2 = await (await fetch(authorize("s2"))).text();
  const deny = await fetch(base + html2.match(/action="(\/oauth\/requests\/lar_[0-9a-f]+)"/)[1], { ...form({ decision: "deny" }), redirect: "manual" });
  const denied = new URL(deny.headers.get("location"));
  assert.equal(denied.searchParams.get("error"), "access_denied");
  assert.equal(denied.searchParams.get("state"), "s2");
  console.log("OK: a browser can only deny; PKCE");
}

// ---- Authorization requests are validated before anything is shown ----
{
  const reg = await (await fetch(`${base}/oauth/register`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ redirect_uris: ["https://app.example/cb"] }),
  })).json();
  const q = (extra) => fetch(`${base}/oauth/authorize?${new URLSearchParams({
    response_type: "code", client_id: reg.client_id, redirect_uri: "https://app.example/cb", code_challenge: "x".repeat(43), code_challenge_method: "S256", ...extra,
  })}`, { redirect: "manual" });
  assert.equal((await q({ redirect_uri: "https://evil.example/cb" })).status, 400, "unregistered redirect is never followed");
  assert.equal((await q({ client_id: "lc_unknown" })).status, 400);
  const plain = await q({ code_challenge_method: "plain" });
  assert.equal(plain.status, 302);
  assert.equal(new URL(plain.headers.get("location")).searchParams.get("error"), "invalid_request");
  const target = await q({ resource: "https://other.example/mcp" });
  assert.equal(new URL(target.headers.get("location")).searchParams.get("error"), "invalid_target");
  const bad = await fetch(`${base}/oauth/register`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ redirect_uris: ["http://evil.example/cb"] }) });
  assert.equal(bad.status, 400);
  console.log("OK: authorization requests are validated");
}

console.log("OK: OAuth on the container gateway, with the official MCP TypeScript SDK client");
