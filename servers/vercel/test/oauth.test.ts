// OAuth for MCP clients: the official MCP TypeScript SDK client connects with
// only the server URL, the way Claude, ChatGPT, Codex, Cursor, and VS Code do.
// It discovers the server, registers itself, the owner approves (in the app
// via the owner API, or in the browser with the owner key), and it gets
// tokens. Then: refresh, revocation, PKCE, and redirect checks.
import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import type { Server } from "node:http";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import type { OAuthClientProvider } from "@modelcontextprotocol/sdk/client/auth.js";
import { UnauthorizedError } from "@modelcontextprotocol/sdk/client/auth.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import type { OAuthClientInformationMixed, OAuthClientMetadata, OAuthTokens } from "@modelcontextprotocol/sdk/shared/auth.js";
import { Gateway, configFromEnv } from "../src/gateway.js";
import { matchRedirect, redirectAllowed } from "../src/oauth.js";
import { MemoryStore } from "../src/store.js";
import { startLocal } from "../src/local.js";

const ADMIN = "admin-token-0123456789abcdef0123456789";
const REDIRECT = "http://127.0.0.1:53682/callback";
let server: Server;
let base: string;

before(async () => {
  server = await startLocal(new Gateway(new MemoryStore(), { ...configFromEnv({ LATCH_ADMIN_TOKEN: ADMIN }), settleMs: 0 }), 0);
  base = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
});
after(() => server.close());

const admin = async (path: string, init: RequestInit = {}) => {
  const res = await fetch(base + path, { ...init, headers: { authorization: `Bearer ${ADMIN}`, "content-type": "application/json" } });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
};

/** A minimal in-memory provider, as an MCP client app implements it. */
class MemoryProvider implements OAuthClientProvider {
  info?: OAuthClientInformationMixed;
  saved?: OAuthTokens;
  verifier = "";
  authorizationUrl?: URL;
  get redirectUrl() { return REDIRECT; }
  get clientMetadata(): OAuthClientMetadata {
    return { client_name: "SDK test app", redirect_uris: [REDIRECT], grant_types: ["authorization_code", "refresh_token"], response_types: ["code"], token_endpoint_auth_method: "none" };
  }
  clientInformation() { return this.info; }
  saveClientInformation(info: OAuthClientInformationMixed) { this.info = info; }
  tokens() { return this.saved; }
  saveTokens(tokens: OAuthTokens) { this.saved = tokens; }
  redirectToAuthorization(url: URL) { this.authorizationUrl = url; }
  saveCodeVerifier(v: string) { this.verifier = v; }
  codeVerifier() { return this.verifier; }
}

/** Plays the browser: opens the consent page, waits for the decision, follows it to the redirect. */
async function browserCode(authorizationUrl: URL, decide: () => Promise<void>): Promise<URL> {
  const pageRes = await fetch(authorizationUrl);
  assert.equal(pageRes.status, 200);
  assert.match(pageRes.headers.get("content-security-policy") ?? "", /frame-ancestors 'none'/);
  const html = await pageRes.text();
  assert.match(html, /SDK test app wants to use your phone/);
  const requestPath = html.match(/action="(\/oauth\/requests\/lar_[0-9a-f]+)"/)![1]!;
  assert.equal((await (await fetch(base + requestPath)).json()).status, "pending");
  await decide();
  const status = await (await fetch(base + requestPath)).json();
  return new URL(status.redirect);
}

test("discovery documents point clients at sign-in", async () => {
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
});

test("an MCP client signs in with only the URL, approved in the app", async () => {
  const provider = new MemoryProvider();
  const first = new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { authProvider: provider });
  await assert.rejects(new Client({ name: "t", version: "0" }).connect(first), UnauthorizedError);
  assert.ok(provider.info?.client_id, "dynamic client registration happened");
  assert.ok(provider.authorizationUrl, "the client was sent to the consent page");

  const redirect = await browserCode(provider.authorizationUrl!, async () => {
    const pending = (await admin("/v1/admin/oauth/requests")).body.requests;
    assert.equal(pending.length, 1);
    assert.equal(pending[0].client_name, "SDK test app");
    assert.equal(pending[0].return_to, "127.0.0.1:53682");
    assert.match(pending[0].match, /^[A-Z2-9]{4}$/);
    assert.equal((await admin(`/v1/admin/oauth/requests/${pending[0].id}`, { method: "POST", body: JSON.stringify({ approve: true }) })).status, 204);
  });
  assert.equal(`${redirect.origin}${redirect.pathname}`, REDIRECT);
  assert.equal(redirect.searchParams.get("iss"), base);
  await first.finishAuth(redirect.searchParams.get("code")!);

  const client = new Client({ name: "t", version: "0" });
  await client.connect(new StreamableHTTPClientTransport(new URL(`${base}/mcp`), { authProvider: provider }));
  const tools = (await client.listTools()).tools.map((t) => t.name);
  assert.ok(tools.includes("observe"));

  // The approval shows up as an AI app the owner can see and revoke.
  const apps = (await admin("/v1/admin/clients")).body.clients;
  const app = apps.find((c: { name: string }) => c.name === "SDK test app");
  assert.equal(app.kind, "oauth");
  assert.ok(app.last_used_ms, "use is recorded");

  // Codes are single use.
  const reuse = await fetch(`${base}/oauth/token`, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code: redirect.searchParams.get("code")!, client_id: provider.info!.client_id, code_verifier: provider.verifier, redirect_uri: REDIRECT }),
  });
  assert.equal(reuse.status, 400);
  assert.equal((await reuse.json()).error, "invalid_grant");

  // Refresh rotates the refresh token.
  const oldRefresh = provider.saved!.refresh_token!;
  const refreshed = await fetch(`${base}/oauth/token`, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: oldRefresh, client_id: provider.info!.client_id }),
  });
  assert.equal(refreshed.status, 200);
  const fresh = await refreshed.json();
  assert.notEqual(fresh.refresh_token, oldRefresh);
  const again = await fetch(`${base}/oauth/token`, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: oldRefresh }),
  });
  assert.equal(again.status, 400, "an old refresh token stops working");

  // Revoking the AI app cuts its access token and refresh token at once.
  assert.equal((await admin(`/v1/admin/clients/${app.id}`, { method: "DELETE" })).status, 204);
  const ping = JSON.stringify({ jsonrpc: "2.0", id: 9, method: "ping" });
  assert.equal((await fetch(`${base}/mcp`, { method: "POST", headers: { authorization: `Bearer ${fresh.access_token}` }, body: ping })).status, 401);
  const afterRevoke = await fetch(`${base}/oauth/token`, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: fresh.refresh_token }),
  });
  assert.equal(afterRevoke.status, 400);
  await client.close();
});

test("a browser can only deny; approving takes the app, never the owner key in a page", async () => {
  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  const reg = await (await fetch(`${base}/oauth/register`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ client_name: "<b>Browser app</b>", redirect_uris: ["https://app.example/cb"] }),
  })).json();
  const authorize = (state: string) => `${base}/oauth/authorize?${new URLSearchParams({
    response_type: "code", client_id: reg.client_id, redirect_uri: "https://app.example/cb", code_challenge: challenge, code_challenge_method: "S256", state, resource: `${base}/mcp`,
  })}`;

  const html = await (await fetch(authorize("s1"))).text();
  assert.doesNotMatch(html, /<b>Browser app<\/b>/, "names are escaped");
  assert.doesNotMatch(html, /owner_key|type="password"/, "the page never asks for the owner key");
  const action = html.match(/action="(\/oauth\/requests\/lar_[0-9a-f]+)"/)![1]!;
  const form = (fields: Record<string, string>) => fetch(base + action, {
    method: "POST", redirect: "manual", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams(fields),
  });
  // Even the right owner key posted from a page approves nothing.
  const posted = await form({ decision: "approve", owner_key: ADMIN });
  assert.equal(posted.status, 200);
  assert.equal((await (await fetch(base + action)).json()).status, "pending");
  const id = action.slice("/oauth/requests/".length);
  assert.equal((await admin(`/v1/admin/oauth/requests/${id}`, { method: "POST", body: JSON.stringify({ approve: true }) })).status, 204);
  const back = new URL((await (await fetch(base + action)).json()).redirect);
  assert.equal(back.origin + back.pathname, "https://app.example/cb");
  assert.equal(back.searchParams.get("state"), "s1");

  // PKCE: a wrong verifier fails, the right one works.
  const exchange = (v: string) => fetch(`${base}/oauth/token`, {
    method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "authorization_code", code: back.searchParams.get("code")!, client_id: reg.client_id, redirect_uri: "https://app.example/cb", code_verifier: v }),
  });
  assert.equal((await exchange(randomBytes(32).toString("base64url"))).status, 400);

  // Deny sends the app an access_denied error.
  const html2 = await (await fetch(authorize("s2"))).text();
  const deny = await fetch(base + html2.match(/action="(\/oauth\/requests\/lar_[0-9a-f]+)"/)![1]!, {
    method: "POST", redirect: "manual", headers: { "content-type": "application/x-www-form-urlencoded" }, body: "decision=deny",
  });
  const denied = new URL(deny.headers.get("location")!);
  assert.equal(denied.searchParams.get("error"), "access_denied");
  assert.equal(denied.searchParams.get("state"), "s2");
});

test("authorization requests are validated before anything is shown", async () => {
  const reg = await (await fetch(`${base}/oauth/register`, {
    method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ redirect_uris: ["https://app.example/cb"] }),
  })).json();
  const q = (extra: Record<string, string>) => fetch(`${base}/oauth/authorize?${new URLSearchParams({
    response_type: "code", client_id: reg.client_id, redirect_uri: "https://app.example/cb", code_challenge: "x".repeat(43), code_challenge_method: "S256", ...extra,
  })}`, { redirect: "manual" });
  assert.equal((await q({ redirect_uri: "https://evil.example/cb" })).status, 400, "unregistered redirect is never followed");
  assert.equal((await q({ client_id: "lc_unknown" })).status, 400);
  const plain = await q({ code_challenge_method: "plain" });
  assert.equal(plain.status, 302);
  assert.equal(new URL(plain.headers.get("location")!).searchParams.get("error"), "invalid_request");
  const target = await q({ resource: "https://other.example/mcp" });
  assert.equal(new URL(target.headers.get("location")!).searchParams.get("error"), "invalid_target");

  const bad = await fetch(`${base}/oauth/register`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ redirect_uris: ["http://evil.example/cb"] }) });
  assert.equal(bad.status, 400);
});

test("redirect rules", () => {
  assert.ok(redirectAllowed("https://claude.ai/api/mcp/auth_callback"));
  assert.ok(redirectAllowed("http://127.0.0.1:33418/callback"));
  assert.ok(redirectAllowed("http://localhost:6274/oauth/callback"));
  assert.ok(redirectAllowed("cursor://anysphere.cursor-retrieval/oauth/user-latch/callback"));
  assert.ok(!redirectAllowed("http://example.com/cb"));
  assert.ok(!redirectAllowed("javascript:alert(1)"));
  assert.ok(!redirectAllowed("https://app.example/cb#frag"));
  // Loopback may change port between registration and use (RFC 8252); nothing else may change.
  assert.equal(matchRedirect(["http://127.0.0.1:1000/cb"], "http://127.0.0.1:2000/cb"), "http://127.0.0.1:2000/cb");
  assert.equal(matchRedirect(["http://127.0.0.1:1000/cb"], "http://127.0.0.1:2000/other"), undefined);
  assert.equal(matchRedirect(["https://a.example/cb"], "https://a.example/cb2"), undefined);
});

test("a newer protocol version on initialize still negotiates", async () => {
  const created = (await admin("/v1/admin/clients", { method: "POST", body: JSON.stringify({ name: "key app" }) })).body;
  const res = await fetch(`${base}/mcp`, {
    method: "POST",
    headers: { authorization: `Bearer ${created.token}`, "content-type": "application/json", "mcp-protocol-version": "2099-01-01" },
    body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2099-01-01", capabilities: {}, clientInfo: { name: "future", version: "1" } } }),
  });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).result.protocolVersion, "2025-11-25");
});
