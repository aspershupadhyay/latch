// OAuth 2.1 for MCP clients, as the MCP authorization spec describes it, so any
// MCP client can connect with only the server URL: it discovers this server
// (RFC 9728, RFC 8414), registers itself (RFC 7591, or a client ID metadata
// document), and the owner approves it, on the phone in the Latch app or with
// the owner key. Authorization code + PKCE (S256) only; public clients only.
//
// Every approval becomes an entry in the owner's list of AI apps, so revoking
// it there cuts its tokens at once. Tokens are random and stored as hashes.

import { newId, newToken, pairingCode, secretsEqual, sha256 } from "./secret.js";
import type { Store } from "./store.js";
import { createHash } from "node:crypto";

export const SCOPE = "phone";
const REQUEST_TTL_MS = 10 * 60 * 1000;
const CODE_TTL_MS = 5 * 60 * 1000;
const ACCESS_TTL_S = 3600;
const REFRESH_TTL_MS = 90 * 24 * 3600 * 1000;
const MAX_REGISTERED = 200;
const MAX_PENDING = 10;
const MAX_KEY_FAILURES_PER_MINUTE = 10;

const K = {
  clients: "latch:oauth:clients",
  cimd: (url: string) => `latch:oauth:cimd:${sha256(url)}`,
  request: (id: string) => `latch:oauth:req:${id}`,
  pending: "latch:oauth:pending",
  code: (code: string) => `latch:oauth:code:${sha256(code)}`,
  access: (token: string) => `latch:oauth:access:${sha256(token)}`,
  refresh: (token: string) => `latch:oauth:refresh:${sha256(token)}`,
  keyFailures: () => `latch:oauth:keyfail:${Math.floor(Date.now() / 60_000)}`,
};

interface RegisteredClient {
  client_id: string;
  client_name: string;
  redirect_uris: string[];
  created_at_ms: number;
}

export interface AuthRequest {
  id: string;
  client_id: string;
  client_name: string;
  redirect_uri: string;
  code_challenge: string;
  state?: string;
  /** Short code shown on the consent page and in the app, so the owner approves the right request. */
  match: string;
  created_at_ms: number;
  /** This gateway's issuer URL, returned as `iss` (RFC 9207). */
  iss: string;
  status: "pending" | "approved" | "denied";
  /** Where the browser goes next, once decided. Holds a single-use, PKCE-bound code. */
  redirect?: string;
}

/** What the gateway needs from its host: the owner key and the list of AI apps. */
export interface OAuthHost {
  adminToken: string | undefined;
  /** Creates an entry in the owner's AI app list and returns its id. */
  addApp(name: string, oauthClientId: string): Promise<string>;
  appExists(id: string): Promise<boolean>;
}

const cors = { "access-control-allow-origin": "*", "access-control-allow-headers": "authorization, content-type, mcp-protocol-version", "access-control-allow-methods": "GET, POST, OPTIONS" };
const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store", ...cors, ...headers } });
const oauthError = (status: number, error: string, description: string) => json(status, { error, error_description: description });

export class OAuth {
  constructor(private readonly store: Store, private readonly host: OAuthHost) {}

  // ---- Discovery ----

  protectedResource(base: string) {
    return json(200, {
      resource: `${base}/mcp`,
      authorization_servers: [base],
      scopes_supported: [SCOPE],
      bearer_methods_supported: ["header"],
      resource_name: "Latch phone control",
    }, { "cache-control": "public, max-age=300" });
  }

  authorizationServer(base: string) {
    return json(200, {
      issuer: base,
      authorization_endpoint: `${base}/oauth/authorize`,
      token_endpoint: `${base}/oauth/token`,
      registration_endpoint: `${base}/oauth/register`,
      revocation_endpoint: `${base}/oauth/revoke`,
      scopes_supported: [SCOPE],
      response_types_supported: ["code"],
      response_modes_supported: ["query"],
      grant_types_supported: ["authorization_code", "refresh_token"],
      code_challenge_methods_supported: ["S256"],
      token_endpoint_auth_methods_supported: ["none"],
      revocation_endpoint_auth_methods_supported: ["none"],
      client_id_metadata_document_supported: true,
      authorization_response_iss_parameter_supported: true,
    }, { "cache-control": "public, max-age=300" });
  }

  preflight() {
    return new Response(null, { status: 204, headers: { ...cors, "access-control-max-age": "86400" } });
  }

  // ---- Registration (RFC 7591) ----

  async register(request: Request): Promise<Response> {
    let body: Record<string, unknown>;
    try {
      body = (await request.json()) as Record<string, unknown>;
    } catch {
      return oauthError(400, "invalid_client_metadata", "expected a JSON body");
    }
    const uris = body?.redirect_uris;
    if (!Array.isArray(uris) || uris.length === 0 || uris.length > 10 || !uris.every((u) => typeof u === "string" && redirectAllowed(u))) {
      return oauthError(400, "invalid_redirect_uri", "redirect_uris must be https, a loopback http address, or an app scheme");
    }
    const method = body.token_endpoint_auth_method;
    if (method !== undefined && method !== "none") {
      return oauthError(400, "invalid_client_metadata", "only public clients are supported (token_endpoint_auth_method: none)");
    }
    const all = await this.store.hgetall(K.clients);
    if (Object.keys(all).length >= MAX_REGISTERED) return oauthError(429, "temporarily_unavailable", "too many registered clients");
    const client: RegisteredClient = {
      client_id: newId("lc"),
      client_name: cleanName(body.client_name),
      redirect_uris: uris as string[],
      created_at_ms: Date.now(),
    };
    await this.store.hset(K.clients, client.client_id, JSON.stringify(client));
    return json(201, {
      client_id: client.client_id,
      client_id_issued_at: Math.floor(client.created_at_ms / 1000),
      client_name: client.client_name,
      redirect_uris: client.redirect_uris,
      grant_types: ["authorization_code", "refresh_token"],
      response_types: ["code"],
      token_endpoint_auth_method: "none",
    });
  }

  /** A client registered here, or one that publishes a client ID metadata document at an https URL. */
  private async client(clientId: string): Promise<RegisteredClient | undefined> {
    if (clientId.startsWith("https://")) return this.metadataDocument(clientId);
    const raw = (await this.store.hgetall(K.clients))[clientId];
    return raw ? (JSON.parse(raw) as RegisteredClient) : undefined;
  }

  private async metadataDocument(url: string): Promise<RegisteredClient | undefined> {
    const cached = await this.store.get(K.cimd(url));
    if (cached) return JSON.parse(cached) as RegisteredClient;
    if (url.length > 512 || !isPublicHttps(url)) return undefined;
    try {
      const res = await fetch(url, { redirect: "error", signal: AbortSignal.timeout(3000), headers: { accept: "application/json" } });
      if (!res.ok) return undefined;
      const text = await res.text();
      if (text.length > 16 * 1024) return undefined;
      const doc = JSON.parse(text) as Record<string, unknown>;
      const uris = doc.redirect_uris;
      if (doc.client_id !== url || !Array.isArray(uris) || !uris.every((u) => typeof u === "string" && redirectAllowed(u))) return undefined;
      const client: RegisteredClient = { client_id: url, client_name: cleanName(doc.client_name), redirect_uris: uris as string[], created_at_ms: Date.now() };
      await this.store.set(K.cimd(url), JSON.stringify(client), { px: 3600_000 });
      return client;
    } catch {
      return undefined;
    }
  }

  // ---- Authorization ----

  /** GET /oauth/authorize: validate, then show the consent page. */
  async authorize(request: Request, base: string): Promise<Response> {
    const q = new URL(request.url).searchParams;
    const clientId = q.get("client_id") ?? "";
    const redirectUri = q.get("redirect_uri") ?? "";
    const client = clientId ? await this.client(clientId) : undefined;
    // Without a trusted redirect target, errors must not be redirected (RFC 6749 §4.1.2.1).
    if (!client) return page(400, "Unknown app", "This app is not registered with this gateway. Try connecting again from the app.");
    const redirect = matchRedirect(client.redirect_uris, redirectUri);
    if (!redirect) return page(400, "Wrong return address", "The app asked to return to an address it did not register.");

    const state = q.get("state") ?? undefined;
    const fail = (error: string, description: string) => redirectWith(redirect, { error, error_description: description, state, iss: base });
    if (q.get("response_type") !== "code") return fail("unsupported_response_type", "only response_type=code is supported");
    const challenge = q.get("code_challenge") ?? "";
    if (q.get("code_challenge_method") !== "S256" || !/^[A-Za-z0-9_-]{43,128}$/.test(challenge)) {
      return fail("invalid_request", "PKCE with code_challenge_method=S256 is required");
    }
    const resource = q.get("resource");
    if (resource !== null && !sameResource(resource, base)) return fail("invalid_target", "this server only issues tokens for its own /mcp endpoint");
    if (this.host.adminToken === undefined) return page(503, "Gateway not set up", "Set LATCH_ADMIN_TOKEN in the Vercel project, then redeploy.");

    const pending = await this.pendingRequests();
    if (pending.length >= MAX_PENDING) return fail("temporarily_unavailable", "too many sign-in requests are waiting; try again in a few minutes");
    const req: AuthRequest = {
      id: newToken("lar"),
      client_id: client.client_id,
      client_name: client.client_name,
      redirect_uri: redirect,
      code_challenge: challenge,
      state,
      match: pairingCode().slice(0, 4),
      created_at_ms: Date.now(),
      iss: base,
      status: "pending",
    };
    await this.saveRequest(req);
    await this.store.hset(K.pending, req.id, String(req.created_at_ms));
    return consentPage(req, hostOf(redirect));
  }

  /** GET /oauth/requests/<id>: the consent page polls this until the owner decides. */
  async requestStatus(id: string): Promise<Response> {
    const req = await this.loadRequest(id);
    if (!req) return json(404, { status: "expired" });
    return json(200, { status: req.status, redirect: req.redirect ?? null });
  }

  /** POST /oauth/requests/<id>: approve with the owner key (form), or deny. */
  async decideInBrowser(request: Request, id: string): Promise<Response> {
    const req = await this.loadRequest(id);
    if (!req || req.status !== "pending") return page(410, "Request expired", "Start connecting again from your AI app.");
    const form = new URLSearchParams(await request.text());
    if (form.get("decision") === "deny") {
      const decided = await this.decide(req, false);
      return seeOther(decided.redirect!);
    }
    const failures = K.keyFailures();
    if (Number((await this.store.get(failures)) ?? "0") >= MAX_KEY_FAILURES_PER_MINUTE) {
      return consentPage(req, hostOf(req.redirect_uri), "Too many wrong keys. Wait a minute.");
    }
    const key = (form.get("owner_key") ?? "").trim();
    if (this.host.adminToken === undefined || key === "" || !secretsEqual(key, this.host.adminToken)) {
      await this.store.incr(failures, 120_000);
      return consentPage(req, hostOf(req.redirect_uri), "That owner key is not right.");
    }
    const decided = await this.decide(req, true);
    return seeOther(decided.redirect!);
  }

  /** Owner API: requests waiting for approval in the app. */
  async pendingRequests(): Promise<AuthRequest[]> {
    const ids = Object.keys(await this.store.hgetall(K.pending));
    const out: AuthRequest[] = [];
    for (const id of ids) {
      const req = await this.loadRequest(id);
      if (req && req.status === "pending") out.push(req);
      else await this.store.hdel(K.pending, id);
    }
    return out.sort((a, b) => a.created_at_ms - b.created_at_ms);
  }

  /** Owner API: approve or deny a request from the app. False when it no longer exists. */
  async decideAsOwner(id: string, approve: boolean): Promise<boolean> {
    const req = await this.loadRequest(id);
    if (!req || req.status !== "pending") return false;
    await this.decide(req, approve);
    return true;
  }

  private async decide(req: AuthRequest, approve: boolean): Promise<AuthRequest> {
    const iss = req.iss;
    let next: AuthRequest;
    if (approve) {
      const appId = await this.host.addApp(req.client_name, req.client_id);
      const code = newToken("lac");
      await this.store.set(K.code(code), JSON.stringify({
        client_id: req.client_id, redirect_uri: req.redirect_uri, code_challenge: req.code_challenge, app_id: appId,
      }), { px: CODE_TTL_MS });
      next = { ...req, status: "approved", redirect: withParams(req.redirect_uri, { code, state: req.state, iss }) };
    } else {
      next = { ...req, status: "denied", redirect: withParams(req.redirect_uri, { error: "access_denied", error_description: "the owner declined", state: req.state, iss }) };
    }
    // Keep the decision briefly so the waiting consent page can pick it up.
    await this.saveRequest(next, 2 * 60 * 1000);
    await this.store.hdel(K.pending, req.id);
    return next;
  }

  // ---- Tokens ----

  async token(request: Request): Promise<Response> {
    const params = await readParams(request);
    const grant = params.get("grant_type");
    if (grant === "authorization_code") {
      const code = params.get("code") ?? "";
      const raw = code ? await this.store.getdel(K.code(code)) : null;
      if (!raw) return oauthError(400, "invalid_grant", "the code is wrong, used, or expired");
      const saved = JSON.parse(raw) as { client_id: string; redirect_uri: string; code_challenge: string; app_id: string };
      if (params.get("client_id") !== saved.client_id) return oauthError(400, "invalid_grant", "the code was issued to another client");
      const redirect = params.get("redirect_uri");
      if (redirect !== null && redirect !== saved.redirect_uri) return oauthError(400, "invalid_grant", "redirect_uri does not match");
      const verifier = params.get("code_verifier") ?? "";
      if (!/^[A-Za-z0-9._~-]{43,128}$/.test(verifier) || s256(verifier) !== saved.code_challenge) {
        return oauthError(400, "invalid_grant", "PKCE verification failed");
      }
      if (!(await this.host.appExists(saved.app_id))) return oauthError(400, "invalid_grant", "access was revoked");
      return this.issue(saved.client_id, saved.app_id);
    }
    if (grant === "refresh_token") {
      const token = params.get("refresh_token") ?? "";
      const raw = token ? await this.store.getdel(K.refresh(token)) : null;
      if (!raw) return oauthError(400, "invalid_grant", "the refresh token is wrong, used, or expired");
      const saved = JSON.parse(raw) as { client_id: string; app_id: string };
      const clientId = params.get("client_id");
      if (clientId !== null && clientId !== saved.client_id) return oauthError(400, "invalid_grant", "the token was issued to another client");
      if (!(await this.host.appExists(saved.app_id))) return oauthError(400, "invalid_grant", "access was revoked");
      return this.issue(saved.client_id, saved.app_id);
    }
    return oauthError(400, "unsupported_grant_type", "use authorization_code or refresh_token");
  }

  private async issue(clientId: string, appId: string): Promise<Response> {
    const access = newToken("lat");
    const refresh = newToken("lrt");
    await this.store.set(K.access(access), appId, { px: ACCESS_TTL_S * 1000 });
    await this.store.set(K.refresh(refresh), JSON.stringify({ client_id: clientId, app_id: appId }), { px: REFRESH_TTL_MS });
    return json(200, { access_token: access, token_type: "Bearer", expires_in: ACCESS_TTL_S, refresh_token: refresh, scope: SCOPE });
  }

  /** RFC 7009: always 200, whether or not the token existed. */
  async revoke(request: Request): Promise<Response> {
    const token = (await readParams(request)).get("token") ?? "";
    if (token) {
      await this.store.del(K.access(token));
      await this.store.del(K.refresh(token));
    }
    return json(200, {});
  }

  /** The AI app an access token belongs to, if the token is live and the app was not revoked. */
  async appForAccessToken(token: string): Promise<string | undefined> {
    const appId = await this.store.get(K.access(token));
    if (!appId || !(await this.host.appExists(appId))) return undefined;
    return appId;
  }

  private async saveRequest(req: AuthRequest, ttl = REQUEST_TTL_MS) {
    await this.store.set(K.request(req.id), JSON.stringify(req), { px: ttl });
  }

  private async loadRequest(id: string): Promise<AuthRequest | undefined> {
    if (!/^lar_[0-9a-f]{64}$/.test(id)) return undefined;
    const raw = await this.store.get(K.request(id));
    return raw ? (JSON.parse(raw) as AuthRequest) : undefined;
  }
}

// ---- Helpers ----

const s256 = (verifier: string) => createHash("sha256").update(verifier).digest("base64url");

function cleanName(value: unknown): string {
  // eslint-disable-next-line no-control-regex
  const name = typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim() : "";
  return name === "" ? "An AI app" : [...name].slice(0, 40).join("");
}

const LOOPBACK = new Set(["localhost", "127.0.0.1", "[::1]"]);
const BLOCKED_SCHEMES = new Set(["javascript:", "data:", "file:", "vbscript:", "blob:", "about:"]);

/** https anywhere, http only on loopback (RFC 8252), or a native app's own scheme. Never fragments. */
export function redirectAllowed(uri: string): boolean {
  if (uri.length > 512) return false;
  let url: URL;
  try {
    url = new URL(uri);
  } catch {
    return false;
  }
  if (url.hash !== "" || BLOCKED_SCHEMES.has(url.protocol)) return false;
  if (url.protocol === "https:") return true;
  if (url.protocol === "http:") return LOOPBACK.has(url.hostname);
  return /^[a-z][a-z0-9+.-]*:$/.test(url.protocol);
}

/** Exact match, except that loopback redirects may use any port (RFC 8252 §7.3). */
export function matchRedirect(registered: string[], requested: string): string | undefined {
  if (registered.includes(requested)) return requested;
  let want: URL;
  try {
    want = new URL(requested);
  } catch {
    return undefined;
  }
  if (want.protocol !== "http:" || !LOOPBACK.has(want.hostname)) return undefined;
  for (const r of registered) {
    try {
      const have = new URL(r);
      if (have.protocol === "http:" && have.hostname === want.hostname && have.pathname === want.pathname && have.search === want.search) return requested;
    } catch {
      // ignore malformed registrations
    }
  }
  return undefined;
}

function isPublicHttps(url: string): boolean {
  try {
    const u = new URL(url);
    if (u.protocol !== "https:" || u.username || u.password || u.port) return false;
    const h = u.hostname;
    return !(LOOPBACK.has(h) || h.endsWith(".local") || h.endsWith(".internal") || /^(10|127|169\.254|192\.168|172\.(1[6-9]|2\d|3[01]))\./.test(h) || h.includes(":"));
  } catch {
    return false;
  }
}

function sameResource(resource: string, base: string): boolean {
  const norm = (s: string) => s.replace(/\/+$/, "");
  return norm(resource) === norm(`${base}/mcp`) || norm(resource) === norm(base);
}

const hostOf = (uri: string) => {
  try {
    const u = new URL(uri);
    return u.host || u.protocol.replace(":", "");
  } catch {
    return "an app";
  }
};

function withParams(target: string, params: Record<string, string | undefined>): string {
  const url = new URL(target);
  for (const [k, v] of Object.entries(params)) if (v !== undefined) url.searchParams.set(k, v);
  return url.toString();
}

const redirectWith = (target: string, params: Record<string, string | undefined>) =>
  new Response(null, { status: 302, headers: { location: withParams(target, params), "cache-control": "no-store" } });

const seeOther = (location: string) => new Response(null, { status: 303, headers: { location, "cache-control": "no-store" } });

async function readParams(request: Request): Promise<URLSearchParams> {
  const text = await request.text();
  if ((request.headers.get("content-type") ?? "").includes("application/json")) {
    try {
      const obj = JSON.parse(text) as Record<string, unknown>;
      return new URLSearchParams(Object.entries(obj).filter(([, v]) => typeof v === "string") as [string, string][]);
    } catch {
      return new URLSearchParams();
    }
  }
  return new URLSearchParams(text);
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!);

const STYLE = `
:root{color-scheme:dark light;--bg:#09090b;--card:#131316;--line:#2a2a2f;--text:#fafafa;--muted:#a1a1aa;--accent:#34d399;--ink:#fafafa;--on-ink:#09090b;--danger:#f87171}
@media (prefers-color-scheme:light){:root{--bg:#f7f7f5;--card:#fff;--line:#e4e4e2;--text:#0a0a0b;--muted:#5b5b63;--accent:#047857;--ink:#0a0a0b;--on-ink:#fafafa;--danger:#dc2626}}
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;background:var(--bg);color:var(--text);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:16px}
main{width:100%;max-width:420px;background:var(--card);border:1px solid var(--line);border-radius:24px;padding:28px}
.mark{width:44px;height:44px;border-radius:14px;display:grid;place-items:center;background:color-mix(in srgb,var(--accent) 16%,transparent);color:var(--accent);margin-bottom:16px}
h1{font-size:22px;line-height:1.3;margin:0 0 6px}p{margin:0 0 14px;color:var(--muted)}
.code{display:flex;gap:8px;margin:18px 0}.code span{flex:1;text-align:center;font:600 26px/1 ui-monospace,Menlo,monospace;padding:14px 0;border-radius:14px;background:var(--bg);border:1px solid var(--line)}
.wait{display:flex;align-items:center;gap:10px;color:var(--muted);font-size:14px;margin:6px 0 18px}.dot{width:8px;height:8px;border-radius:50%;background:var(--accent);animation:p 1.4s infinite}@keyframes p{50%{opacity:.25}}
@media (prefers-reduced-motion:reduce){.dot{animation:none}}
details{border-top:1px solid var(--line);padding-top:14px}summary{cursor:pointer;color:var(--muted);font-size:14px}
input{width:100%;margin:12px 0 10px;padding:12px 14px;border-radius:12px;border:1px solid var(--line);background:var(--bg);color:var(--text);font:inherit}
button{width:100%;padding:13px;border-radius:14px;border:0;font:600 15px/1 inherit;cursor:pointer}.primary{background:var(--ink);color:var(--on-ink)}.ghost{background:transparent;color:var(--danger);margin-top:8px}
.err{color:var(--danger);font-size:14px;margin:0 0 8px}.foot{font-size:12px;margin:16px 0 0}`;

const SHIELD = `<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l8 3v5c0 5-3.5 8.5-8 10-4.5-1.5-8-5-8-10V6z"/><path d="M8.5 12l2.5 2.5 4.5-5"/></svg>`;

function html(status: number, body: string, nonce?: string): Response {
  const script = nonce ? `'nonce-${nonce}'` : "'none'";
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><title>Latch</title><style>${STYLE}</style></head><body><main>${body}</main></body></html>`, {
    status,
    headers: {
      "content-type": "text/html; charset=utf-8",
      "cache-control": "no-store",
      "content-security-policy": `default-src 'none'; style-src 'unsafe-inline'; script-src ${script}; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'`,
      "x-frame-options": "DENY",
    },
  });
}

function page(status: number, title: string, text: string): Response {
  return html(status, `<div class="mark">${SHIELD}</div><h1>${esc(title)}</h1><p>${esc(text)}</p>`);
}

function consentPage(req: AuthRequest, returnHost: string, error?: string): Response {
  const nonce = newToken("n").slice(2, 34);
  const code = [...req.match].map((c) => `<span>${esc(c)}</span>`).join("");
  const action = `/oauth/requests/${req.id}`;
  return html(200, `
<div class="mark">${SHIELD}</div>
<h1>${esc(req.client_name)} wants to use your phone</h1>
<p>Through your Latch gateway, only within what you allow in the app. It returns to <b>${esc(returnHost)}</b>.</p>
<p style="margin:0;color:var(--text);font-weight:600">Approve in the Latch app</p>
<p style="margin:0">Open Latch → Connect. Check the code matches:</p>
<div class="code" aria-label="Match code">${code}</div>
<div class="wait" role="status"><span class="dot"></span><span id="s">Waiting for your approval…</span></div>
${error ? `<p class="err">${esc(error)}</p>` : ""}
<details${error ? " open" : ""}><summary>Approve with your owner key instead</summary>
<form method="post" action="${action}"><input type="password" name="owner_key" autocomplete="off" placeholder="Owner key (LATCH_ADMIN_TOKEN)" aria-label="Owner key"><button class="primary" name="decision" value="approve">Approve</button></form>
</details>
<form method="post" action="${action}"><button class="ghost" name="decision" value="deny">Deny</button></form>
<p class="foot">Not you? Deny. Nothing is shared until you approve.</p>
<script nonce="${nonce}">
(async function poll(){try{const r=await fetch(${JSON.stringify(action)},{cache:"no-store"});const j=await r.json();
if(j.redirect){document.getElementById("s").textContent=j.status==="approved"?"Approved. Returning to the app…":"Declined.";location.replace(j.redirect);return}
if(j.status==="expired"){document.getElementById("s").textContent="This request expired. Start again from your AI app.";return}}catch(e){}
setTimeout(poll,1500)})();
</script>`, nonce);
}
