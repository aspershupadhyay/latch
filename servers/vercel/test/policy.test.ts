import { test } from "node:test";
import assert from "node:assert/strict";
import { evaluate } from "../src/policy";
import { CAPABILITIES, type CapabilityState, type CapabilityStatus, normalizeObservation, parseCommand } from "../src/protocol";
import { shared } from "./shared";

const doc = JSON.parse(shared("policy/cases.json"));

function capabilities(spec: unknown): CapabilityState[] {
  return CAPABILITIES.map((capability) => {
    let status: CapabilityStatus = "enabled";
    if (Array.isArray(spec)) status = spec.includes(capability) ? "enabled" : "disabled";
    else if (spec && typeof spec === "object") status = (spec as Record<string, CapabilityStatus>)[capability] ?? "enabled";
    return { capability, status };
  });
}

test("shared policy cases decide exactly like the Rust engine", () => {
  const now: number = doc.now_ms;
  const observation = normalizeObservation(doc.observation, 2000);
  assert.ok(doc.cases.length >= 30);
  for (const c of doc.cases) {
    const field = (k: string) => (k in c ? c[k] : doc.defaults[k]);
    const s = field("session");
    const age = field("observation_age_ms");
    let decision;
    try {
      decision = evaluate(parseCommand(c.command), {
        capabilities: capabilities(field("capabilities")),
        session: { expires_at_ms: now + s.expires_in_ms, approve_every_action: s.approve_every_action, paused: s.paused },
        latestObservation: age === null ? undefined : { observation, receivedAtMs: now - age },
        nowMs: now,
      });
    } catch (e) {
      // Commands the Rust serde layer would reject never reach policy; treat as invalid_request.
      decision = { kind: "deny" as const, error: e as { code: string } };
    }
    const want = c.expect;
    assert.equal(decision.kind, want.decision, c.name);
    if (decision.kind === "allow") assert.equal(decision.risk, want.risk, c.name);
    if (decision.kind === "confirm") {
      assert.equal(decision.request.risk, want.risk, c.name);
      if (want.title) assert.equal(decision.request.title, want.title, c.name);
    }
    if (decision.kind === "deny") assert.equal(decision.error.code, want.code, c.name);
  }
});
