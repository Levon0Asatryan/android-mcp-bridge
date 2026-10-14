import { env, exports } from "cloudflare:workers";
import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
import { runInDurableObject } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import { b64u, BOOTSTRAP, claims, clientToken, newDevice, ORIGIN, PhoneSocket, sha256Hex, sign, type Device } from "./helpers";

// Synthetic OAuth client/redirect from vitest.config.ts; no deployable values.
const CLIENT_ID = "test-chatgpt-client";
const CLIENT_SECRET = "test-client-secret-0123456789abcdefghijklmnop";
const REDIRECT = "https://chatgpt.example.test/connector/oauth/callback-1";
const RESOURCE = `${ORIGIN}/mcp`;

const worker = (exports as unknown as { default: Fetcher }).default;
const fetchWorker = (input: string | URL | Request, init?: RequestInit) => worker.fetch(new Request(input, init));
const open: PhoneSocket[] = [];
const owner = () => env.BRIDGE.get(env.BRIDGE.idFromName("owner"));
afterEach(async () => {
  for (const p of open.splice(0)) { try { p.ws.close(1000, "done"); } catch { /* closed */ } await p.closed().catch(() => {}); }
  // The owner instance is shared by this file; clear per-test link and rate state.
  await runInDurableObject(owner(), async (_object, state) => { state.storage.sql.exec("DELETE FROM links"); state.storage.sql.exec("DELETE FROM rate"); });
});

let paired: { device: Device; device_kid: string } | null = null;
async function pairedPhone(): Promise<PhoneSocket> {
  if (!paired) {
    const device = await newDevice();
    const boot = { authorization: `Bearer ${BOOTSTRAP}` };
    const { challenge } = await (await fetchWorker(`${ORIGIN}/device/pair/challenge`, { method: "POST", headers: boot })).json<{ challenge: string }>();
    const pairProof = await sign({ alg: "ES256", typ: "hb-pair+jwt", jwk: device.jwk }, claims("pair", ORIGIN, challenge), device.key);
    const response = await fetchWorker(`${ORIGIN}/device/pair/complete`, { method: "POST", headers: boot, body: JSON.stringify({ proof: pairProof }) });
    expect(response.status).toBe(200);
    paired = { device, device_kid: (await response.json<{ device_kid: string }>()).device_kid };
  }
  const { device, device_kid } = paired;
  const nonce = await (await fetchWorker(`${ORIGIN}/device/challenge`, { method: "POST" })).json<{ nonce: string; audience: string; pairing_generation: number }>();
  const proof = await sign({ alg: "ES256", typ: "hb-connect+jwt", kid: device_kid },
    claims("connect", nonce.audience, nonce.nonce, { pairing_generation: nonce.pairing_generation }), device.key);
  const response = await fetchWorker(`${ORIGIN}/device/connect`, { headers: { upgrade: "websocket", authorization: `Bearer ${proof}` } });
  expect(response.status).toBe(101);
  const socket = response.webSocket as WebSocket;
  socket.accept();
  const phone = new PhoneSocket(socket);
  open.push(phone);
  await phone.next("hello");
  return phone;
}

async function pkce() {
  const verifier = b64u(crypto.getRandomValues(new Uint8Array(32)));
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  return { verifier, challenge: b64u(new Uint8Array(digest)) };
}

function authorizeUrl(params: Record<string, string>): string {
  const url = new URL(`${ORIGIN}/authorize`);
  for (const [k, v] of Object.entries({ response_type: "code", client_id: CLIENT_ID, redirect_uri: REDIRECT, state: "state-1",
    code_challenge_method: "S256", resource: RESOURCE, scope: "bridge.status health.nutrition.read health.nutrition.delete", ...params })) {
    if (v !== "") url.searchParams.set(k, v);
  }
  return url.href;
}

const cookies = (response: Response) => response.headers.getSetCookie().map((c) => c.split(";")[0] ?? "").filter((c) => !c.endsWith("=")).join("; ");

/** Browser part of the flow up to the phone prompt. */
async function startLink(challenge: string, scope?: string, postOrigin = ORIGIN, fetchSite?: string) {
  const page = await fetchWorker(authorizeUrl({ code_challenge: challenge, ...(scope ? { scope } : {}) }));
  expect(page.status).toBe(200);
  expect(page.headers.get("cache-control")).toBe("no-store");
  expect(page.headers.get("content-security-policy")).toContain("frame-ancestors 'none'");
  const html = await page.text();
  expect(html).not.toContain("code=");
  const handle = /name="handle" value="([^"]+)"/.exec(html)?.[1] as string;
  const consentCookie = cookies(page);
  const link = await fetchWorker(`${ORIGIN}/authorize/link`, { method: "POST", redirect: "manual",
    headers: { origin: postOrigin, ...(fetchSite ? { "sec-fetch-site": fetchSite } : {}),
      cookie: consentCookie, "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ handle }).toString() });
  return { link, handle, consentCookie };
}

async function approve(phone: PhoneSocket, request: Record<string, any>, scopes: string[], overrides: Record<string, unknown> = {}) {
  const secret = clientToken();
  const clientId = secret.split(".")[1] as string;
  phone.send({ type: "link_result", version: 1, link_id: request.link_id, binding: request.binding, approved: true, scopes,
    client_id: clientId, grant_secret: secret, fingerprint: await sha256Hex(secret), expires_at: Date.now() + 86_400_000,
    grant_version: 1, pairing_generation: 1, ...overrides });
  return { secret, clientId, ack: await phone.next("link_ack") };
}

async function token(body: Record<string, string>) {
  return fetchWorker(`${ORIGIN}/oauth/token`, { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: CLIENT_ID, client_secret: CLIENT_SECRET, ...body }).toString() });
}

/** Full approved link; returns tokens and the phone-held grant secret. */
async function linked(scopes = ["bridge.status", "health.nutrition.read", "health.nutrition.delete"]) {
  const phone = await pairedPhone();
  const { verifier, challenge } = await pkce();
  const { link } = await startLink(challenge);
  expect(link.status).toBe(303);
  const request = await phone.next("link_request");
  expect(request).toMatchObject({ oauth_client_id: CLIENT_ID, redirect_host: "chatgpt.example.test", scopes: ["bridge.status", "health.nutrition.read", "health.nutrition.delete"] });
  const grant = await approve(phone, request, scopes);
  expect(grant.ack).toMatchObject({ ok: true });
  const wait = await fetchWorker(`${ORIGIN}${link.headers.get("location")}`, { redirect: "manual", headers: { cookie: cookies(link) } });
  expect(wait.status).toBe(303);
  const callback = new URL(wait.headers.get("location") as string);
  expect(`${callback.origin}${callback.pathname}`).toBe(REDIRECT);
  expect(callback.searchParams.get("state")).toBe("state-1");
  expect(callback.searchParams.get("iss")).toBe(ORIGIN);
  const code = callback.searchParams.get("code") as string;
  const exchange = await token({ grant_type: "authorization_code", code, redirect_uri: REDIRECT, code_verifier: verifier, resource: RESOURCE });
  expect(exchange.status).toBe(200);
  const tokens = await exchange.json<{ access_token: string; refresh_token: string; scope: string; expires_in: number }>();
  return { phone, tokens, code, verifier, ...grant };
}

async function mcpClient(accessToken?: string) {
  const client = new Client({ name: "test", version: "1.0.0" });
  const transport = new StreamableHTTPClientTransport(new URL(RESOURCE), {
    fetch: fetchWorker as typeof fetch,
    ...(accessToken ? { requestInit: { headers: { authorization: `Bearer ${accessToken}` } } } : {}),
  });
  await client.connect(transport);
  return client;
}

describe("MCP endpoint", () => {
  it("initializes, lists and calls the harmless check without auth, and refuses health tools", async () => {
    const client = await mcpClient();
    const tools = (await client.listTools()).tools;
    expect(tools.map((t) => t.name).sort()).toEqual([
      "bridge_connection_check", "bridge_permissions", "bridge_status",
      ...["add_body_measurement", "add_hydration", "add_mindfulness", "add_nutrition", "add_nutrition_batch", "add_oxygen_saturation",
        "add_sleep", "add_weight", "add_workout", "delete_hydration", "delete_nutrition", "delete_weight", "get_active_calories", "get_activity_detail",
        "get_body_measurements", "get_daily_summary", "get_data_freshness", "get_heart_rate", "get_hydration", "get_mindfulness",
        "get_nutrition", "get_oxygen_saturation", "get_sleep", "get_steps", "get_vitals", "get_weight", "get_workouts",
        "update_hydration", "update_nutrition"].map((name) => `health_${name}`),
      ...["dismiss_alarm", "get_next_alarm", "set_alarm", "set_timer", "show_alarms"].map((name) => `clock_${name}`),
      ...["send_notification", "speak"].map((name) => `notify_${name}`),
      ...["run_shell", "status"].map((name) => `dev_${name}`),
    ].sort());
    const del = tools.find((t) => t.name === "health_delete_weight");
    expect(del?.annotations).toMatchObject({ destructiveHint: true, readOnlyHint: false });
    expect(tools.find((t) => t.name === "health_get_steps")?.annotations).toMatchObject({ readOnlyHint: true });
    const check = await client.callTool({ name: "bridge_connection_check", arguments: {} });
    expect(check.structuredContent).toEqual({ ok: true, protocol: "mcp" });
    const health = await client.callTool({ name: "bridge_status", arguments: {} });
    expect(health.isError).toBe(true);
    expect(JSON.stringify(health._meta)).toContain("resource_metadata=");
    await client.close();
  });

  it("serves a raw legacy initialize and rejects invalid bearer tokens with a challenge", async () => {
    const init = await fetchWorker(RESOURCE, { method: "POST", headers: { "content-type": "application/json", accept: "application/json, text/event-stream" },
      body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "x", version: "1" } } }) });
    expect(init.status).toBe(200);
    expect(await init.text()).toContain("android-mcp");
    const crossOrigin = await fetchWorker(RESOURCE, { method: "POST", headers: { origin: "https://evil.example",
      "content-type": "application/json" }, body: "{}" });
    expect(crossOrigin.status).toBe(403);
    const bad = await fetchWorker(RESOURCE, { method: "POST", headers: { authorization: "Bearer nope:nope:nope", "content-type": "application/json" }, body: "{}" });
    expect(bad.status).toBe(401);
    expect(bad.headers.get("www-authenticate")).toContain(`resource_metadata="${ORIGIN}/.well-known/oauth-protected-resource/mcp"`);
    expect((await fetchWorker(RESOURCE, { method: "POST", headers: { "content-type": "application/json" }, body: "x".repeat(70_000) })).status).toBe(413);
  });

  it("publishes only supported OAuth metadata", async () => {
    const prm = await (await fetchWorker(`${ORIGIN}/.well-known/oauth-protected-resource/mcp`)).json<Record<string, unknown>>();
    expect(prm).toMatchObject({ resource: RESOURCE, authorization_servers: [ORIGIN] });
    const as = await (await fetchWorker(`${ORIGIN}/.well-known/oauth-authorization-server`)).json<Record<string, unknown>>();
    expect(as).toMatchObject({ issuer: ORIGIN, authorization_endpoint: `${ORIGIN}/authorize`, token_endpoint: `${ORIGIN}/oauth/token`,
      code_challenge_methods_supported: ["S256"], authorization_response_iss_parameter_supported: true });
    expect(as.registration_endpoint).toBeUndefined();
  });
});

describe("OAuth authorization", () => {
  it("rejects wrong client, redirect, resource and missing PKCE without a phone prompt", async () => {
    const phone = await pairedPhone();
    const { challenge } = await pkce();
    expect((await fetchWorker(authorizeUrl({ code_challenge: challenge, client_id: "other-client-123" }))).status).toBe(400);
    expect((await fetchWorker(authorizeUrl({ code_challenge: challenge, redirect_uri: "https://evil.example/cb" }))).status).toBe(400);
    const resource = await fetchWorker(authorizeUrl({ code_challenge: challenge, resource: "https://evil.example/mcp" }), { redirect: "manual" });
    expect([302, 303, 400]).toContain(resource.status);
    if (resource.status !== 400) expect(new URL(resource.headers.get("location") as string).searchParams.get("error")).toBeTruthy();
    const missing = await fetchWorker(authorizeUrl({}), { redirect: "manual" });
    expect(new URL(missing.headers.get("location") as string).searchParams.get("iss")).toBe(ORIGIN);
    const plain = await fetchWorker(authorizeUrl({ code_challenge: challenge, code_challenge_method: "plain" }), { redirect: "manual" });
    expect(plain.headers.get("location")).toContain("error=");
    expect(phone.count("link_request")).toBe(0);
  });

  it("refuses cross-site link posts and bounds pending prompts", async () => {
    const phone = await pairedPhone();
    const { challenge } = await pkce();
    const page = await fetchWorker(authorizeUrl({ code_challenge: challenge }));
    const handle = /name="handle" value="([^"]+)"/.exec(await page.text())?.[1] as string;
    const forged = await fetchWorker(`${ORIGIN}/authorize/link`, { method: "POST", headers: { origin: "https://evil.example",
      cookie: cookies(page), "content-type": "application/x-www-form-urlencoded" }, body: `handle=${handle}` });
    expect(forged.status).toBe(403);
    const noCookie = await fetchWorker(`${ORIGIN}/authorize/link`, { method: "POST", headers: { origin: ORIGIN,
      "content-type": "application/x-www-form-urlencoded" }, body: `handle=${handle}` });
    expect(noCookie.status).toBe(400);
    expect((await startLink(challenge, undefined, "null", "same-origin")).link.status).toBe(303);
    expect((await startLink(challenge, undefined, "null", "cross-site")).link.status).toBe(403);
    expect((await startLink(challenge)).link.status).toBe(429);
    expect(phone.count("link_request")).toBe(1);
  });

  it("fails cleanly when the phone is offline, denies, or answers with mismatched bindings", async () => {
    const { challenge } = await pkce();
    expect((await startLink(challenge)).link.status).toBe(503);
    const phone = await pairedPhone();
    const { link } = await startLink(challenge);
    const request = await phone.next("link_request");
    expect((await approve(phone, request, ["health.nutrition.read"], { binding: "0".repeat(64) })).ack).toMatchObject({ ok: false, error: "binding_mismatch" });
    expect((await approve(phone, request, ["health.weight.write"])).ack).toMatchObject({ ok: false });
    expect((await approve(phone, request, ["health.nutrition.read"], { pairing_generation: 9 })).ack).toMatchObject({ ok: false });
    phone.send({ type: "link_result", version: 1, link_id: request.link_id, binding: request.binding, approved: false });
    expect(await phone.next("link_ack")).toMatchObject({ ok: true });
    const wait = await fetchWorker(`${ORIGIN}${link.headers.get("location")}`, { redirect: "manual", headers: { cookie: cookies(link) } });
    const back = new URL(wait.headers.get("location") as string);
    expect(back.searchParams.get("error")).toBe("access_denied");
    expect(back.searchParams.get("iss")).toBe(ORIGIN);
    expect(back.searchParams.get("code")).toBeNull();
    const again = await fetchWorker(`${ORIGIN}${link.headers.get("location")}`, { redirect: "manual", headers: { cookie: cookies(link) } });
    expect(again.status).toBe(400);
  });

  it("issues tokens only for the right verifier, once, and rotates refresh tokens", async () => {
    const { tokens, phone, secret } = await linked();
    expect(tokens.expires_in).toBeLessThanOrEqual(900);
    expect(tokens.scope.split(" ").sort()).toEqual(["bridge.status", "health.nutrition.delete", "health.nutrition.read"]);
    const refreshed = await token({ grant_type: "refresh_token", refresh_token: tokens.refresh_token });
    expect(refreshed.status).toBe(200);
    const next = await refreshed.json<{ refresh_token: string; access_token: string }>();
    expect(next.refresh_token).not.toBe(tokens.refresh_token);
    // The provider tolerates one retry of the previous token until its successor is used; then reuse is rejected.
    const second = await token({ grant_type: "refresh_token", refresh_token: next.refresh_token });
    expect(second.status).toBe(200);
    Object.assign(next, await second.json<{ refresh_token: string; access_token: string }>());
    expect((await token({ grant_type: "refresh_token", refresh_token: tokens.refresh_token })).status).toBe(400);
    const client = await mcpClient(next.access_token);
    const status = client.callTool({ name: "bridge_status", arguments: {} });
    const command = await phone.next("command");
    expect(command.client_token).toBe(secret);
    phone.reply(command, { running: true });
    expect((await status).structuredContent).toEqual({ running: true });
    const kv = env.OAUTH_KV as KVNamespace;
    for (const key of (await kv.list()).keys) {
      expect(await kv.get(key.name)).not.toContain(secret);
    }
    phone.send({ type: "register_clients", version: 1, request_id: "keep", clients: [] });
    await phone.next("ack");
    const revoked = await client.callTool({ name: "bridge_status", arguments: {} }).catch((e: Error) => e);
    expect(revoked instanceof Error || (revoked as { isError?: boolean }).isError).toBeTruthy();
    expect((await token({ grant_type: "refresh_token", refresh_token: next.refresh_token })).status).toBe(400);
  });

  it("treats authorization code replay as compromise and revokes the grant", async () => {
    const { tokens, code, verifier } = await linked();
    const replay = await token({ grant_type: "authorization_code", code, redirect_uri: REDIRECT, code_verifier: verifier, resource: RESOURCE });
    expect(replay.status).toBe(400);
    expect((await token({ grant_type: "refresh_token", refresh_token: tokens.refresh_token })).status).toBe(400);
  });

  it("rejects a wrong PKCE verifier", async () => {
    const phone = await pairedPhone();
    const { challenge } = await pkce();
    const { link } = await startLink(challenge);
    await approve(phone, await phone.next("link_request"), ["health.nutrition.read"]);
    const wait = await fetchWorker(`${ORIGIN}${link.headers.get("location")}`, { redirect: "manual", headers: { cookie: cookies(link) } });
    const code = new URL(wait.headers.get("location") as string).searchParams.get("code") as string;
    const wrong = await token({ grant_type: "authorization_code", code, redirect_uri: REDIRECT, code_verifier: (await pkce()).verifier, resource: RESOURCE });
    expect(wrong.status).toBe(400);
  });
});

describe("OAuth health tools", () => {
  it("relays scoped calls with the phone-held grant and an auth envelope, and enforces scopes", async () => {
    const { tokens, phone, secret, clientId } = await linked(["bridge.status", "health.nutrition.read"]);
    const client = await mcpClient(tokens.access_token);
    const pending = client.callTool({ name: "health_get_nutrition", arguments: { start_time: "2030-01-01T00:00:00Z", end_time: "2030-01-02T00:00:00Z" } });
    const command = await phone.next("command");
    expect(command).toMatchObject({ operation: "get_nutrition", client_token: secret,
      auth: { kind: "oauth", oauth_client_id: CLIENT_ID, client_id: clientId, grant_version: 1, pairing_generation: 1 } });
    phone.reply(command, { success: true, records: [] });
    expect((await pending).structuredContent).toEqual({ success: true, records: [] });
    const denied = await client.callTool({ name: "health_delete_nutrition", arguments: { record_id: "r1" } });
    expect(denied.isError).toBe(true);
    expect(JSON.stringify(denied._meta)).toContain("insufficient_scope");
    const invalid = await client.callTool({ name: "health_get_nutrition", arguments: { start_time: "x", end_time: "y", extra: 1 } }).catch((e: Error) => e);
    expect(invalid instanceof Error || (invalid as { isError?: boolean }).isError).toBeTruthy();
    expect(phone.count("command")).toBe(1);
    // The Actions path never accepts an OAuth grant secret.
    expect((await fetchWorker(`${ORIGIN}/v1/status`, { headers: { authorization: `Bearer ${secret}` } })).status).toBe(401);
  });

  it("revokes every grant on operator reset", async () => {
    const { tokens } = await linked();
    const kv = env.OAUTH_KV as KVNamespace;
    expect((await kv.list({ prefix: "grant:" })).keys.length).toBeGreaterThan(0);
    // Simulate the reset path's provider revocation directly.
    const { authorizationServer } = await import("../src/oauth");
    const api = authorizationServer(ORIGIN).getOAuthApi(env);
    for (const grant of (await api.listUserGrants("owner")).items) await api.revokeGrant(grant.id, "owner");
    expect((await token({ grant_type: "refresh_token", refresh_token: tokens.refresh_token })).status).toBe(400);
  });
});
