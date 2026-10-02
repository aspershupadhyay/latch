// Latch owner console. No dependencies; every piece of device-provided text is
// rendered with textContent, never as HTML.
"use strict";

const TOKEN_KEY = "latch.adminToken";
const $ = (id) => document.getElementById(id);
let token = null;
let refreshTimer = null;
let pairTimer = null;

function readToken() {
  try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
}
function writeToken(value) {
  try { value ? sessionStorage.setItem(TOKEN_KEY, value) : sessionStorage.removeItem(TOKEN_KEY); } catch { /* private mode */ }
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json", ...(options.headers || {}) },
  });
  if (response.status === 401) {
    lock("That token was not accepted.");
    throw new Error("unauthorized");
  }
  if (response.status === 204) return null;
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.error || `request failed (${response.status})`);
  return body;
}

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else if (k === "text") node.textContent = v;
    else node.setAttribute(k, v);
  }
  for (const c of children) if (c) node.append(c);
  return node;
}

function ago(ms) {
  if (!ms) return "never";
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return new Date(ms).toLocaleDateString();
}

function renderDevices(list) {
  const ul = $("devices");
  ul.replaceChildren();
  $("devices-empty").hidden = list.length > 0;
  const online = list.filter((d) => d.connected).length;
  $("devices-summary").textContent = list.length ? `${online} of ${list.length} connected` : "";
  for (const d of list) {
    const live = d.live;
    const paused = live && live.session && live.session.paused;
    const state = !d.connected ? "offline" : paused ? "paused" : "online";
    const stateText = !d.connected ? `Not connected · last seen ${ago(d.last_seen_ms)}`
      : paused ? "Connected · paused by the owner on the phone"
      : "Connected · session active";
    const chips = el("div", { class: "chips" });
    if (live) {
      for (const c of live.capabilities) {
        const on = c.status === "enabled";
        chips.append(el("span", { class: `chip ${on ? "on" : c.status === "needs_permission" ? "warn" : ""}`,
          text: `${c.capability}${on ? "" : ` · ${c.status.replace("_", " ")}`}` }));
      }
      if (live.session.approve_every_action) chips.append(el("span", { class: "chip warn", text: "approves every action" }));
    }
    const revoke = el("button", { class: "danger", type: "button", text: "Revoke" });
    revoke.setAttribute("aria-label", `Revoke ${d.name}`);
    revoke.addEventListener("click", async () => {
      if (!confirm(`Revoke "${d.name}"? The phone is disconnected immediately and must pair again to reconnect.`)) return;
      revoke.disabled = true;
      try { await api(`/v1/admin/devices/${encodeURIComponent(d.id)}`, { method: "DELETE" }); await refresh(); }
      catch (e) { alert(e.message); revoke.disabled = false; }
    });
    const info = el("div", {},
      el("div", { class: "name", text: d.name }),
      el("div", { class: "muted", text: `${d.model} · ${d.platform}${live ? ` ${live.os_version}` : ""} · ${d.id}` }),
      el("div", { class: "muted", text: stateText }),
      chips);
    const dots = el("span", { class: "field", "aria-hidden": "true" }, el("i"), el("i"));
    ul.append(el("li", { class: `device state-${state}`, "aria-label": `${d.name}: ${stateText}` }, dots, info, revoke));
  }
}

function renderActivity(events) {
  const table = $("activity");
  const body = table.querySelector("tbody");
  body.replaceChildren();
  table.hidden = events.length === 0;
  $("activity-empty").hidden = events.length > 0;
  for (const e of events.slice(0, 100)) {
    const decisionClass = e.decision === "deny" ? "bad" : e.decision === "confirm" ? "ask" : "";
    body.append(el("tr", {},
      el("td", { text: new Date(e.at_ms).toLocaleTimeString() }),
      el("td", { text: e.device_id }),
      el("td", { class: "mono", text: e.command }),
      el("td", { class: decisionClass, text: e.decision === "confirm" ? "asked owner" : e.decision }),
      el("td", { class: e.outcome === "ok" ? "ok" : "bad", text: e.outcome.replaceAll("_", " ") }),
      el("td", { text: String(e.latency_ms) })));
  }
}

async function refresh() {
  try {
    const [devices, audit] = await Promise.all([api("/v1/admin/devices"), api("/v1/admin/audit")]);
    renderDevices(devices.devices);
    renderActivity(audit.events);
  } catch (e) {
    if (e.message !== "unauthorized") $("health").textContent = `Could not refresh: ${e.message}`;
  }
}

async function health() {
  try {
    const h = await (await fetch("/healthz")).json();
    $("health").textContent = `Gateway ${h.version} · protocol ${h.protocol} · ${h.devices_connected} connected`;
  } catch {
    $("health").textContent = "Gateway unreachable";
  }
}

function renderSnippets() {
  const url = `${location.origin}/mcp`;
  $("mcp-url").textContent = url;
  $("snippet-claude").textContent =
    `claude mcp add --transport http latch ${url} \\\n  --header "Authorization: Bearer $LATCH_MCP_TOKEN"`;
  $("snippet-json").textContent = JSON.stringify({
    mcpServers: { latch: { type: "http", url, headers: { Authorization: "Bearer <LATCH_MCP_TOKEN>" } } },
  }, null, 2);
}

function unlock(value) {
  token = value;
  writeToken(value);
  $("login").hidden = true;
  $("app").hidden = false;
  $("signout").hidden = false;
  renderSnippets();
  refresh();
  clearInterval(refreshTimer);
  refreshTimer = setInterval(() => { refresh(); health(); }, 5000);
}

function lock(message = "") {
  token = null;
  writeToken(null);
  clearInterval(refreshTimer);
  $("app").hidden = true;
  $("signout").hidden = true;
  $("login").hidden = false;
  $("login-error").textContent = message;
}

document.addEventListener("DOMContentLoaded", () => {
  health();
  const saved = readToken();
  if (saved) unlock(saved);

  $("login-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const value = $("token").value.trim();
    token = value;
    try {
      await api("/v1/admin/devices");
      $("token").value = "";
      $("login-error").textContent = "";
      unlock(value);
    } catch (e) {
      if (e.message !== "unauthorized") $("login-error").textContent = e.message;
    }
  });

  $("signout").addEventListener("click", () => lock());

  $("pair-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    $("pair-error").textContent = "";
    try {
      const r = await api("/v1/admin/pairings", { method: "POST", body: JSON.stringify({ name: $("pair-name").value.trim() }) });
      $("pair-url").textContent = r.gateway_url;
      $("pair-code").textContent = r.code;
      $("pair-result").hidden = false;
      clearInterval(pairTimer);
      const tick = () => {
        const left = Math.max(0, Math.round((r.expires_at_ms - Date.now()) / 1000));
        $("pair-expiry").textContent = left > 0
          ? `Expires in ${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")} · single use`
          : "Expired. Create a new code.";
        if (left === 0) { clearInterval(pairTimer); $("pair-code").textContent = "—"; }
      };
      tick();
      pairTimer = setInterval(tick, 1000);
    } catch (e) {
      if (e.message !== "unauthorized") $("pair-error").textContent = e.message;
    }
  });
});
