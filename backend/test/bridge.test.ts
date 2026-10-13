import { env, exports } from "cloudflare:workers";
import { evictDurableObject } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import { BOOTSTRAP, claims, clientToken, newDevice, ORIGIN, PhoneSocket, sha256Hex, sign, type Device } from "./helpers";

type Stub = DurableObjectStub;
const open: PhoneSocket[] = [];
afterEach(() => { open.splice(0).forEach((p) => { try { p.ws.close(1000, "done"); } catch { /* closed */ } }); });

const instance = (): Stub => env.BRIDGE.get(env.BRIDGE.idFromName(crypto.randomUUID()));
const call = (stub: Stub, path: string, init: RequestInit = {}) => stub.fetch(new Request(`${ORIGIN}${path}`, init));
const boot = (token = BOOTSTRAP) => ({ authorization: `Bearer ${token}` });

async function pair(stub: Stub, device: Device, audience = ORIGIN): Promise<Response> {
  const challenge = await call(stub, "/device/pair/challenge", { method: "POST", headers: boot() });
  expect(challenge.status).toBe(200);
  const { challenge: nonce } = await challenge.json<{ challenge: string }>();
  const proof = await sign({ alg: "ES256", typ: "hb-pair+jwt", jwk: device.jwk }, claims("pair", audience, nonce), device.key);
  return call(stub, "/device/pair/complete", { method: "POST", headers: boot(), body: JSON.stringify({ proof }) });
}

async function connectProof(stub: Stub, device: Device, kid: string, overrides: object = {}): Promise<string> {
  const response = await call(stub, "/device/challenge", { method: "POST" });
  expect(response.status).toBe(200);
  const body = await response.json<{ nonce: string; audience: string; pairing_generation: number }>();
  return sign({ alg: "ES256", typ: "hb-connect+jwt", kid },
    claims("connect", body.audience, body.nonce, { pairing_generation: body.pairing_generation, ...overrides }), device.key);
}

async function upgrade(stub: Stub, proof: string): Promise<Response> {
  return call(stub, "/device/connect", { headers: { upgrade: "websocket", authorization: `Bearer ${proof}` } });
}

async function connected(stub: Stub, device: Device, kid: string): Promise<PhoneSocket> {
  const response = await upgrade(stub, await connectProof(stub, device, kid));
  expect(response.status).toBe(101);
  const socket = response.webSocket as WebSocket;
  socket.accept();
  const phone = new PhoneSocket(socket);
  open.push(phone);
  await phone.next("hello");
  return phone;
}

async function register(phone: PhoneSocket, tokens: { token: string; expires?: number }[]) {
  const request_id = crypto.randomUUID();
  phone.send({
    type: "register_clients", version: 1, request_id,
    clients: await Promise.all(tokens.map(async ({ token, expires }) => ({
      client_id: token.split(".")[1], fingerprint: await sha256Hex(token), expires_at: expires ?? Date.now() + 86_400_000,
    }))),
  });
  const ack = await phone.next("ack");
  expect(ack).toMatchObject({ request_id, ok: true });
}

/** Paired, connected instance with one registered client token. */
async function ready() {
  const stub = instance();
  const device = await newDevice();
  const paired = await pair(stub, device);
  const { device_kid } = await paired.json<{ device_kid: string }>();
  const phone = await connected(stub, device, device_kid);
  const token = clientToken();
  await register(phone, [{ token }]);
  return { stub, device, kid: device_kid, phone, token };
}
const api = (stub: Stub, token: string | null, path: string, init: RequestInit = {}) =>
  call(stub, path, { ...init, headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(init.headers ?? {}) } });
const meal = { meal_name: "Synthetic", meal_type: "lunch", timestamp: "2030-01-01T12:00:00Z", calories_kcal: 100,
  protein_g: 1, carbs_g: 2, fat_g: 3, idempotency_key: "synthetic-1" };
const post = (body: unknown) => ({ method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) });

describe("pairing", () => {
  it("requires the bootstrap credential and is single-use", async () => {
    const stub = instance();
    expect((await call(stub, "/device/pair/challenge", { method: "POST" })).status).toBe(401);
    expect((await call(stub, "/device/pair/challenge", { method: "POST", headers: boot("wrong-bootstrap-0123456789abcdefghijklmnop") })).status).toBe(401);
    const first = await newDevice();
    const paired = await pair(stub, first);
    expect(paired.status).toBe(200);
    expect(await paired.json()).toMatchObject({ paired: true, pairing_generation: 1 });
    const again = await call(stub, "/device/pair/challenge", { method: "POST", headers: boot() });
    expect(again.status).toBe(401);
    expect(await again.json()).toMatchObject({ error_category: "unauthorized" });
  });

  it("rejects wrong audience and replayed or unknown challenges", async () => {
    const stub = instance();
    const device = await newDevice();
    expect((await pair(stub, device, "https://other.example.test")).status).toBe(401);
    const fake = await sign({ alg: "ES256", typ: "hb-pair+jwt", jwk: device.jwk }, claims("pair", ORIGIN, "A".repeat(43)), device.key);
    expect((await call(stub, "/device/pair/complete", { method: "POST", headers: boot(), body: JSON.stringify({ proof: fake }) })).status).toBe(401);
    const challenge = await (await call(stub, "/device/pair/challenge", { method: "POST", headers: boot() })).json<{ challenge: string }>();
    const proof = await sign({ alg: "ES256", typ: "hb-pair+jwt", jwk: device.jwk }, claims("pair", ORIGIN, challenge.challenge), device.key);
    const complete = () => call(stub, "/device/pair/complete", { method: "POST", headers: boot(), body: JSON.stringify({ proof }) });
    expect((await complete()).status).toBe(200);
    expect((await complete()).status).toBe(401);
  });

  it("bounds open pairing challenges", async () => {
    const stub = instance();
    const statuses = [];
    for (let i = 0; i < 5; i++) statuses.push((await call(stub, "/device/pair/challenge", { method: "POST", headers: boot() })).status);
    expect(statuses).toEqual([200, 200, 200, 200, 429]);
  });
});

describe("device sessions", () => {
  it("rejects replayed, foreign-key and wrong-audience proofs", async () => {
    const stub = instance();
    const device = await newDevice();
    const { device_kid } = await (await pair(stub, device)).json<{ device_kid: string }>();
    const proof = await connectProof(stub, device, device_kid);
    const first = await upgrade(stub, proof);
    expect(first.status).toBe(101);
    (first.webSocket as WebSocket).accept();
    open.push(new PhoneSocket(first.webSocket as WebSocket));
    expect((await upgrade(stub, proof)).status).toBe(401);
    const intruder = await newDevice();
    expect((await upgrade(stub, await connectProof(stub, intruder, device_kid))).status).toBe(401);
    expect((await upgrade(stub, await connectProof(stub, device, device_kid, { aud: `${ORIGIN}/elsewhere` }))).status).toBe(401);
    expect((await upgrade(stub, await connectProof(stub, device, device_kid, { pairing_generation: 2 }))).status).toBe(401);
  });

  it("bounds reconnect nonce flooding", async () => {
    const stub = instance();
    await pair(stub, await newDevice());
    const statuses = [];
    for (let i = 0; i < 9; i++) statuses.push((await call(stub, "/device/challenge", { method: "POST" })).status);
    expect(statuses.slice(0, 8).every((s) => s === 200)).toBe(true);
    expect(statuses[8]).toBe(429);
  });

  it("a reconnect displaces the old socket and resolves its in-flight write as uncertain", async () => {
    const { stub, device, kid, phone, token } = await ready();
    const write = api(stub, token, "/v1/nutrition", post(meal));
    const command = await phone.next("command");
    const replacement = await connected(stub, device, kid);
    const response = await write;
    expect(response.status).toBe(504);
    expect(await response.json()).toMatchObject({ error_category: "outcome_unknown" });
    expect(await phone.closed()).toBe(4001);
    expect(command.session_id).not.toBe((await replacement.messages.find((m) => m.type === "hello"))?.session_id);
    await register(replacement, [{ token }]);
    const status = api(stub, token, "/v1/status");
    const next = await replacement.next("command");
    replacement.reply({ ...next, session_id: "spoofed-session" }, { spoofed: true });
    replacement.reply({ ...next, request_id: "unknown-request" }, { spoofed: true });
    replacement.reply(next, { running: true });
    expect(await (await status).json()).toEqual({ running: true });
  });

  it("keeps sessions, registrations and limits across hibernation", async () => {
    const { stub, phone, token } = await ready();
    await evictDurableObject(stub);
    const status = api(stub, token, "/v1/status");
    const command = await phone.next("command");
    phone.reply(command, { running: true });
    expect((await status).status).toBe(200);
  });

  it("answers malformed device messages without affecting pending work", async () => {
    const { stub, phone, token } = await ready();
    const status = api(stub, token, "/v1/status");
    const command = await phone.next("command");
    phone.ws.send("not json");
    expect(await phone.next("error")).toMatchObject({ error: "malformed" });
    phone.send({ type: "register_clients", version: 1, request_id: "r", clients: [{ client_id: "x" }] });
    expect(await phone.next("ack")).toMatchObject({ ok: false });
    phone.reply(command, { running: true });
    expect((await status).status).toBe(200);
  });

  it("rejects duplicate client ids, oversized UTF-8 frames and fabricated event acknowledgements", async () => {
    const { stub, phone, token } = await ready();
    const id = token.split(".")[1];
    phone.send({ type: "register_clients", version: 1, request_id: "duplicate",
      clients: [
        { client_id: id, fingerprint: await sha256Hex(token), expires_at: Date.now() + 60_000 },
        { client_id: id, fingerprint: await sha256Hex(clientToken()), expires_at: Date.now() + 60_000 },
      ] });
    expect(await phone.next("ack")).toMatchObject({ request_id: "duplicate", ok: false });
    phone.ws.send("€".repeat(44_000));
    expect(await phone.next("error")).toMatchObject({ error: "malformed" });
    phone.send({ type: "events_ack", version: 1, up_to: Number.MAX_SAFE_INTEGER });
    expect(await phone.next("error")).toMatchObject({ error: "malformed" });
    const status = api(stub, token, "/v1/status");
    phone.reply(await phone.next("command"), { running: true });
    expect((await status).status).toBe(200);
  });
});

describe("health ingress", () => {
  it("never dispatches unauthenticated, unregistered or expired credentials", async () => {
    const { stub, phone, token } = await ready();
    expect((await api(stub, null, "/v1/status")).status).toBe(401);
    expect((await api(stub, clientToken(), "/v1/status")).status).toBe(401);
    expect((await api(stub, "hb.not-a-token", "/v1/status")).status).toBe(401);
    expect((await api(stub, BOOTSTRAP, "/v1/status")).status).toBe(401);
    const expired = clientToken();
    await register(phone, [{ token }, { token: expired, expires: Date.now() - 1 }]);
    expect((await api(stub, expired, "/v1/status")).status).toBe(401);
    await register(phone, []);
    expect((await api(stub, token, "/v1/status")).status).toBe(401);
    expect(phone.count("command")).toBe(0);
  });

  it("relays a synthetic read and write with the runtime credential and strict envelope", async () => {
    const { stub, phone, token } = await ready();
    const read = api(stub, token, "/v1/steps?start_time=2030-01-01T00:00:00Z&end_time=2030-01-02T00:00:00Z");
    const command = await phone.next("command");
    expect(Object.keys(command).sort()).toEqual(["arguments", "client_token", "expires_at", "operation", "request_id", "session_id", "type", "version"]);
    expect(command).toMatchObject({ version: 1, operation: "get_steps", client_token: token,
      arguments: { start_time: "2030-01-01T00:00:00Z", end_time: "2030-01-02T00:00:00Z" } });
    phone.reply(command, { total_steps: 42 });
    expect(await (await read).json()).toEqual({ total_steps: 42 });

    const write = api(stub, token, "/v1/nutrition", post(meal));
    const insert = await phone.next("command");
    expect(insert).toMatchObject({ operation: "add_nutrition", arguments: meal });
    phone.reply(insert, { success: true, record_id: "synthetic-record" });
    expect((await write).status).toBe(200);

    const denied = api(stub, token, "/v1/weight", post({ weight_kg: 70, timestamp: "2030-01-01T00:00:00Z", idempotency_key: "k" }));
    phone.reply(await phone.next("command"), { success: false, error_category: "phone_locked", message: "Unlock." });
    expect((await denied).status).toBe(403);
  });

  it("rejects invalid, keyless and oversized input before dispatch", async () => {
    const { stub, phone, token } = await ready();
    const { idempotency_key: _, ...keyless } = meal;
    expect((await api(stub, token, "/v1/nutrition", post(keyless))).status).toBe(400);
    expect((await api(stub, token, "/v1/nutrition", post({ ...meal, extra: 1 }))).status).toBe(400);
    expect((await api(stub, token, "/v1/nutrition", post({ ...meal, timestamp: "2030-01-01T12:00:00" }))).status).toBe(400);
    expect((await api(stub, token, "/v1/steps?start_time=2030-01-01T00:00:00Z")).status).toBe(400);
    expect((await api(stub, token, "/v1/nutrition", post({ ...meal, meal_name: "x".repeat(70_000) }))).status).toBe(413);
    expect((await api(stub, token, "/v1/nutrition", { method: "POST", headers: { "content-type": "application/json" },
      body: new Uint8Array([0x7b, 0xff, 0x7d]) })).status).toBe(400);
    expect(phone.count("command")).toBe(0);
  });

  it("times out writes as uncertain and cancels them on the phone", async () => {
    const { stub, phone, token } = await ready();
    const write = api(stub, token, "/v1/weight", post({ weight_kg: 70, timestamp: "2030-01-01T00:00:00Z", idempotency_key: "k" }));
    const command = await phone.next("command");
    const response = await write;
    expect(response.status).toBe(504);
    expect(await response.json()).toMatchObject({ error_category: "outcome_unknown" });
    expect(await phone.next("cancel")).toMatchObject({ request_id: command.request_id });
    phone.reply(command, { success: true });
  });

  it("reports disconnects and enforces concurrency and output limits", async () => {
    const { stub, phone, token } = await ready();
    const reads = Array.from({ length: 4 }, () => api(stub, token, "/v1/status"));
    const commands = await Promise.all(reads.map(() => phone.next("command")));
    expect((await api(stub, token, "/v1/status")).status).toBe(429);
    phone.reply(commands[0]!, { blob: "€".repeat(30_000) });
    expect(await (await reads[0]!).json()).toMatchObject({ error_category: "result_too_large" });
    commands.slice(1).forEach((c) => phone.reply(c, { ok: true }));
    await Promise.all(reads.slice(1));

    const firstDelete = api(stub, token, "/v1/nutrition/record-1", { method: "DELETE" });
    await phone.next("command");
    const second = await api(stub, token, "/v1/weight/record-2", { method: "DELETE" });
    expect(await second.json()).toMatchObject({ error_category: "approval_pending" });
    phone.ws.close(1000, "bye");
    const response = await firstDelete;
    expect(await response.json()).toMatchObject({ error_category: "outcome_unknown" });
    expect((await api(stub, token, "/v1/status")).status).toBe(503);
  });

  it("emits bounded security metadata without credentials", async () => {
    const { stub, phone, token } = await ready();
    const status = api(stub, token, "/v1/status");
    phone.reply(await phone.next("command"), { running: true });
    await status;
    await api(stub, clientToken(), "/v1/status");
    const all = phone.messages.filter((m) => m.type === "security_events").flatMap((m) => m.events as { type: string }[]);
    const types = all.map((e) => e.type);
    expect(types).toContain("device_connected");
    expect(types).toContain("client_first_use");
    expect(types).toContain("auth_failures");
    expect(JSON.stringify(phone.messages.filter((m) => m.type === "security_events"))).not.toContain(token.split(".")[2]);
  });
});

describe("worker entry", () => {
  const fetchWorker = (url: string, init: RequestInit = {}) => (exports as unknown as { default: Fetcher }).default.fetch(new Request(url, init));
  it("rejects browser origins, foreign hosts and unknown paths", async () => {
    expect((await fetchWorker(`${ORIGIN}/v1/status`, { headers: { origin: "https://evil.example" } })).status).toBe(403);
    expect((await fetchWorker("https://other.example.test/v1/status")).status).toBe(421);
    expect((await fetchWorker(`${ORIGIN}/admin`)).status).toBe(404);
    expect((await fetchWorker(`${ORIGIN}/v1/status`)).status).toBe(401);
  });
});
