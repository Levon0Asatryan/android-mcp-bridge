import { DurableObject } from "cloudflare:workers";
import { digestsEqual, randomToken, sha256Hex, thumbprint, verifyConnectProof, verifyPairingProof, type PublicDeviceKey } from "./auth";
import {
  failure, isDelete, isObject, isWrite, LIMITS, parseObject, PROTOCOL_VERSION, readBoundedText, routeAction, statusFor,
  TOKEN_PATTERN, validateArguments, withinOutputBudget, BodyReadTimeout, type ErrorCategory, type Operation,
} from "./protocol";
import type { AuthRequest } from "@cloudflare/workers-oauth-provider";
import { authorizationServer, GRANT_TTL_S, OWNER_USER, type GrantProps } from "./oauth";
import { isScope, SCOPE_FOR, type Scope } from "./scopes";

export interface Env {
  BRIDGE: DurableObjectNamespace<BridgeObject>;
  PUBLIC_ORIGIN: string;
  OWNER_BOOTSTRAP_TOKEN?: string;
  PAIRING_RESET_EPOCH?: string;
  RELAY_DEADLINE_MS?: string;
  OAUTH_KV?: KVNamespace;
  OAUTH_CLIENT_ID?: string;
  OAUTH_CLIENT_SECRET?: string;
  OAUTH_REDIRECT_URI?: string;
}

export const LINK_TTL_MS = 120_000;
export const LINKS_PER_HOUR = 6;
export type LinkView =
  | { state: "pending"; code: string; expires_at: number }
  | { state: "redirect"; to: string }
  | { state: "closed"; request: AuthRequest }
  | { state: "unknown" };
export interface McpOutcome { status: number; body: Record<string, unknown> }
interface OAuthRegistration { client_id: string; scopes: Scope[]; pairing_generation: number; grant_version: number }

interface Attachment { sid: string; gen: number }
type Settled = { kind: "result"; body: Record<string, unknown> } | { kind: "timeout" } | { kind: "disconnected" };
interface Pending { sessionId: string; operation: Operation; settle: (result: Settled) => void }
interface ClientRow { client_id: string; expires_at: number; first_used: number | null }
type OAuthClientRow = { client_id: string; expires_at: number; first_used: number | null; oauth_client: string | null; scopes: string | null; generation: number | null; grant_version: number | null };

const COLLAPSED_EVENTS = new Set(["device_connected", "auth_failures", "challenge_flood"]);
const REGISTER_ID = /^[0-9a-f-]{36}$/;
const FINGERPRINT = /^[0-9a-f]{64}$/;

export function respond(status: number, body: Record<string, unknown> | null, headers: Record<string, string> = {}): Response {
  return new Response(body === null ? null : JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff",
      ...headers,
    },
  });
}
const fail = (category: ErrorCategory, message: string, headers: Record<string, string> = {}) =>
  respond(statusFor(category), failure(category, message), headers);

function bearer(request: Request): string | null {
  const value = request.headers.get("authorization");
  return value?.startsWith("Bearer ") ? value.slice(7) : null;
}

/** Single-owner relay. Stores only public device identity, token fingerprints and bounded metadata. */
export class BridgeObject extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  private readonly pending = new Map<string, Pending>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS pair_challenges (nonce TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS connect_nonces (nonce TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS used_bootstraps (digest TEXT PRIMARY KEY);
      CREATE TABLE IF NOT EXISTS clients (client_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL UNIQUE,
        expires_at INTEGER NOT NULL, first_used INTEGER);
      CREATE TABLE IF NOT EXISTS rate (bucket TEXT PRIMARY KEY, window INTEGER NOT NULL, count INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, type TEXT NOT NULL,
        detail TEXT, count INTEGER NOT NULL DEFAULT 1);
    `);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS links (id TEXT PRIMARY KEY, code TEXT NOT NULL, created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL, status TEXT NOT NULL, browser_hash TEXT NOT NULL, auth_request TEXT NOT NULL, redirect_to TEXT)`);
    const columns = new Set(this.sql.exec<{ name: string }>("PRAGMA table_info(clients)").toArray().map((row) => row.name));
    for (const [name, type] of [["oauth_client", "TEXT"], ["scopes", "TEXT"], ["generation", "INTEGER"], ["grant_version", "INTEGER"]]) {
      if (!columns.has(name as string)) this.sql.exec(`ALTER TABLE clients ADD COLUMN ${name} ${type}`);
    }
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  // ---- storage helpers -------------------------------------------------------------------------
  private meta(key: string): string | null {
    const row = this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = ?", key).toArray()[0];
    return row ? row.value : null;
  }
  private setMeta(key: string, value: string | null): void {
    if (value === null) this.sql.exec("DELETE FROM meta WHERE key = ?", key);
    else this.sql.exec("INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", key, value);
  }
  private pairingGeneration(): number { return Number(this.meta("pairing_generation") ?? "1"); }
  private device(): { key: PublicDeviceKey; kid: string } | null {
    const key = this.meta("device_key");
    const kid = this.meta("device_kid");
    return key && kid ? { key: JSON.parse(key) as PublicDeviceKey, kid } : null;
  }

  /** Durable fixed-window counter so limits survive hibernation. Returns false when the limit is reached. */
  private allow(bucket: string, limit: number, consume: boolean): boolean {
    const window = Math.floor(Date.now() / 60_000);
    const row = this.sql.exec<{ window: number; count: number }>("SELECT window, count FROM rate WHERE bucket = ?", bucket).toArray()[0];
    const count = row && row.window === window ? row.count : 0;
    if (count >= limit) return false;
    if (consume) {
      this.sql.exec("INSERT INTO rate (bucket, window, count) VALUES (?, ?, ?) ON CONFLICT(bucket) DO UPDATE SET window = excluded.window, count = excluded.count",
        bucket, window, count + 1);
    }
    return true;
  }

  /** Bounded security metadata. Never contains tokens, IP addresses, health values or record IDs. */
  private event(type: string, detail: string | null = null): void {
    const at = Math.floor(Date.now() / 60_000) * 60_000;
    let count = 1;
    if (COLLAPSED_EVENTS.has(type)) {
      const last = this.sql.exec<{ seq: number; at: number; type: string; detail: string | null; count: number }>(
        "SELECT seq, at, type, detail, count FROM events ORDER BY seq DESC LIMIT 1").toArray()[0];
      if (last && last.type === type && last.detail === detail && at - last.at < 3_600_000) {
        count = last.count + 1;
        this.sql.exec("DELETE FROM events WHERE seq = ?", last.seq);
      }
    }
    this.sql.exec("INSERT INTO events (at, type, detail, count) VALUES (?, ?, ?, ?)", at, type, detail, count);
    this.sql.exec("DELETE FROM events WHERE seq NOT IN (SELECT seq FROM events ORDER BY seq DESC LIMIT ?)", LIMITS.maxEvents);
    const socket = this.currentSocket();
    if (socket) this.pushEvents(socket);
  }
  private pushEvents(socket: WebSocket): void {
    const acked = Number(this.meta("events_acked") ?? "0");
    const events = this.sql.exec("SELECT seq, at, type, detail, count FROM events WHERE seq > ? ORDER BY seq LIMIT ?", acked, LIMITS.maxEvents).toArray();
    if (events.length === 0) return;
    try { socket.send(JSON.stringify({ type: "security_events", version: PROTOCOL_VERSION, events })); } catch { /* closed socket; resent next session */ }
  }

  // ---- sessions --------------------------------------------------------------------------------
  private currentSocket(): WebSocket | null {
    const sid = this.meta("current_session_id");
    if (!sid) return null;
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = ws.deserializeAttachment() as Attachment | null;
      if (attachment?.sid === sid && ws.readyState === WebSocket.OPEN) return ws;
    }
    return null;
  }
  private settleAll(predicate: (pending: Pending) => boolean, result: Settled): void {
    for (const [id, pending] of [...this.pending]) {
      if (!predicate(pending)) continue;
      this.pending.delete(id);
      pending.settle(result);
    }
  }
  private closeAll(code: number, reason: string): void {
    for (const ws of this.ctx.getWebSockets()) { try { ws.close(code, reason); } catch { /* already closed */ } }
  }

  /** Operator reset: raising PAIRING_RESET_EPOCH in private hosting config clears pairing and client registrations. */
  private async applyReset(): Promise<void> {
    if (this.meta("oauth_revoke_all") === "1") await this.revokeGrants(() => true);
    const target = Number.parseInt(this.env.PAIRING_RESET_EPOCH ?? "0", 10);
    const current = Number(this.meta("reset_epoch") ?? "0");
    if (!Number.isSafeInteger(target) || target <= current) return;
    this.ctx.storage.transactionSync(() => {
      const hadDevice = this.device() !== null;
      for (const key of ["device_key", "device_kid", "paired_at", "current_session_id"]) this.setMeta(key, null);
      this.sql.exec("DELETE FROM clients");
      this.sql.exec("DELETE FROM pair_challenges");
      this.sql.exec("DELETE FROM connect_nonces");
      this.sql.exec("DELETE FROM links");
      this.setMeta("oauth_revoke_all", "1");
      if (hadDevice) this.setMeta("pairing_generation", String(this.pairingGeneration() + 1));
      this.setMeta("reset_epoch", String(target));
    });
    this.settleAll(() => true, { kind: "disconnected" });
    this.closeAll(4003, "Pairing was reset.");
    this.event("pairing_reset");
    await this.revokeGrants(() => true);
  }

  /** Revokes provider grants (and so every access and refresh token) whose phone client matches. */
  private async revokeGrants(match: (clientId: string) => boolean): Promise<void> {
    if (this.env.OAUTH_KV) {
      const api = authorizationServer(this.env.PUBLIC_ORIGIN).getOAuthApi(this.env);
      let cursor: string | undefined;
      do {
        const page = await api.listUserGrants(OWNER_USER, cursor ? { cursor } : {});
        for (const grant of page.items) {
          const linked = typeof grant.metadata?.client_id === "string" ? grant.metadata.client_id as string : "";
          if (match(linked)) await api.revokeGrant(grant.id, OWNER_USER);
        }
        cursor = page.cursor;
      } while (cursor);
    }
    if (this.meta("oauth_revoke_all") === "1" && match("")) this.setMeta("oauth_revoke_all", null);
  }

  override async fetch(request: Request): Promise<Response> {
    try { return await this.handleRequest(request); }
    catch (error) {
      if (error instanceof BodyReadTimeout) return fail("expired_request", "Request body was not received in time.");
      throw error;
    }
  }

  private async handleRequest(request: Request): Promise<Response> {
    await this.applyReset();
    const url = new URL(request.url);
    const route = `${request.method} ${url.pathname}`;
    if (route === "POST /device/pair/challenge") return this.pairChallenge(request);
    if (route === "POST /device/pair/complete") return this.pairComplete(request);
    if (route === "POST /device/challenge") return this.connectChallenge();
    if (route === "GET /device/connect") return this.acceptDevice(request);
    if (url.pathname.startsWith("/v1/")) return this.action(request, url);
    return fail("not_found", "Not found.");
  }

  // ---- pairing ---------------------------------------------------------------------------------
  private async bootstrap(request: Request): Promise<Response | string> {
    const configured = this.env.OWNER_BOOTSTRAP_TOKEN;
    if (!configured || configured.length < 32) return fail("not_configured", "Pairing is not configured for this instance.");
    if (!this.allow("auth_fail", LIMITS.authFailuresPerMinute, false)) return fail("rate_limited", "Too many failed attempts. Try later.");
    const supplied = bearer(request) ?? "";
    const expected = await sha256Hex(configured);
    if (!digestsEqual(await sha256Hex(supplied), expected)) {
      this.allow("auth_fail", LIMITS.authFailuresPerMinute, true);
      this.event("auth_failures", "pairing");
      return fail("unauthorized", "Invalid pairing credential.");
    }
    if (this.sql.exec("SELECT 1 FROM used_bootstraps WHERE digest = ?", expected).toArray().length > 0) {
      return fail("unauthorized", "This pairing credential was already used. Configure a new one to pair again.");
    }
    if (this.device()) {
      this.event("device_replacement_rejected");
      return fail("already_paired", "A device is already paired. Reset pairing through hosting configuration first.");
    }
    return expected;
  }

  private async pairChallenge(request: Request): Promise<Response> {
    const auth = await this.bootstrap(request);
    if (auth instanceof Response) return auth;
    const now = Date.now();
    this.sql.exec("DELETE FROM pair_challenges WHERE expires_at <= ?", now);
    const open = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM pair_challenges").one().n;
    if (open >= LIMITS.pairChallenges) return fail("rate_limited", "Too many open pairing challenges.");
    const challenge = randomToken();
    const expiresAt = now + LIMITS.pairChallengeTtlMs;
    this.sql.exec("INSERT INTO pair_challenges (nonce, expires_at) VALUES (?, ?)", challenge, expiresAt);
    return respond(200, {
      challenge, purpose: "pair", audience: this.env.PUBLIC_ORIGIN, protocol_version: PROTOCOL_VERSION,
      expires_at: new Date(expiresAt).toISOString(),
    });
  }

  private async pairComplete(request: Request): Promise<Response> {
    const auth = await this.bootstrap(request);
    if (auth instanceof Response) return auth;
    const text = await readBoundedText(request.body, 8192);
    const body = typeof text === "string" && text !== "too_large" && text !== "malformed" ? parseObject(text) : null;
    if (!body || typeof body.proof !== "string") return fail("invalid_input", "Provide a pairing proof.");
    const verified = await verifyPairingProof(body.proof, this.env.PUBLIC_ORIGIN, Math.floor(Date.now() / 1000));
    if (!verified) {
      this.allow("auth_fail", LIMITS.authFailuresPerMinute, true);
      this.event("auth_failures", "pairing_proof");
      return fail("unauthorized", "Invalid pairing proof.");
    }
    const kid = await thumbprint(verified.key);
    const outcome = this.ctx.storage.transactionSync((): ErrorCategory | null => {
      if (this.device()) return "already_paired";
      const consumed = this.sql.exec("DELETE FROM pair_challenges WHERE nonce = ? AND expires_at > ?", verified.claims.nonce, Date.now());
      if (consumed.rowsWritten !== 1) return "unauthorized";
      this.setMeta("device_key", JSON.stringify(verified.key));
      this.setMeta("device_kid", kid);
      this.setMeta("paired_at", String(Date.now()));
      if (!this.meta("pairing_generation")) this.setMeta("pairing_generation", "1");
      this.sql.exec("INSERT OR IGNORE INTO used_bootstraps (digest) VALUES (?)", auth);
      return null;
    });
    if (outcome === "already_paired") {
      this.event("device_replacement_rejected");
      return fail("already_paired", "A device is already paired.");
    }
    if (outcome) {
      this.allow("auth_fail", LIMITS.authFailuresPerMinute, true);
      return fail("unauthorized", "The pairing challenge is unknown, expired or already used.");
    }
    this.event("device_paired");
    return respond(200, { paired: true, device_kid: kid, pairing_generation: this.pairingGeneration(), protocol_version: PROTOCOL_VERSION });
  }

  // ---- device session --------------------------------------------------------------------------
  private connectAudience(): string { return `${this.env.PUBLIC_ORIGIN}/device/connect`; }

  private connectChallenge(): Response {
    if (!this.allow("unauthenticated", LIMITS.unauthenticatedPerMinute, true)) return fail("rate_limited", "Too many requests.");
    if (!this.device()) return fail("not_paired", "This instance is not paired.");
    const now = Date.now();
    this.sql.exec("DELETE FROM connect_nonces WHERE expires_at <= ?", now);
    const open = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM connect_nonces").one().n;
    if (open >= LIMITS.connectNonces) {
      this.event("challenge_flood");
      return fail("rate_limited", "Too many open challenges. Retry shortly.");
    }
    const nonce = randomToken();
    const expiresAt = now + LIMITS.connectNonceTtlMs;
    this.sql.exec("INSERT INTO connect_nonces (nonce, expires_at) VALUES (?, ?)", nonce, expiresAt);
    return respond(200, {
      nonce, purpose: "connect", audience: this.connectAudience(), pairing_generation: this.pairingGeneration(),
      protocol_version: PROTOCOL_VERSION, expires_at: new Date(expiresAt).toISOString(),
    });
  }

  private async acceptDevice(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") return fail("invalid_input", "WebSocket upgrade required.");
    if (!this.allow("auth_fail", LIMITS.authFailuresPerMinute, false)) return fail("rate_limited", "Too many failed attempts.");
    const device = this.device();
    if (!device) return fail("not_paired", "This instance is not paired.");
    const proof = bearer(request) ?? "";
    const claims = await verifyConnectProof(proof, device.key, device.kid, this.connectAudience(), Math.floor(Date.now() / 1000));
    const consumed = claims && claims.pairing_generation === this.pairingGeneration() &&
      this.sql.exec("DELETE FROM connect_nonces WHERE nonce = ? AND expires_at > ?", claims.nonce, Date.now()).rowsWritten === 1;
    if (!consumed) {
      this.allow("auth_fail", LIMITS.authFailuresPerMinute, true);
      this.event("auth_failures", "device");
      return fail("unauthorized", "Invalid, expired or replayed device proof.");
    }
    const generation = Number(this.meta("session_generation") ?? "0") + 1;
    const sid = randomToken(18);
    const displaced = this.ctx.getWebSockets().length > 0;
    this.setMeta("session_generation", String(generation));
    this.setMeta("current_session_id", sid);
    this.settleAll(() => true, { kind: "disconnected" });
    this.closeAll(4001, "Replaced by a newer device session.");
    const [client, server] = Object.values(new WebSocketPair()) as [WebSocket, WebSocket];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ sid, gen: generation } satisfies Attachment);
    server.send(JSON.stringify({
      type: "hello", version: PROTOCOL_VERSION, session_id: sid, session_generation: generation,
      pairing_generation: this.pairingGeneration(),
      limits: { input_bytes: LIMITS.inputBytes, output_bytes: LIMITS.outputBytes, output_chars: LIMITS.outputChars,
        in_flight: LIMITS.inFlight, delete_approval_ms: LIMITS.deleteApprovalMs },
    }));
    this.event("device_connected", displaced ? "replaced_previous" : null);
    await this.resendPendingLinks(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    if (!attachment || attachment.sid !== this.meta("current_session_id")) {
      ws.close(4001, "Session replaced.");
      return;
    }
    const error = (reason: string) => ws.send(JSON.stringify({ type: "error", version: PROTOCOL_VERSION, error: reason }));
    if (typeof message !== "string" || new TextEncoder().encode(message).byteLength > LIMITS.deviceMessageBytes) return error("malformed");
    const msg = parseObject(message);
    if (!msg || msg.version !== PROTOCOL_VERSION || typeof msg.type !== "string") return error("malformed");
    if (msg.type === "result") {
      if (msg.session_id !== attachment.sid || typeof msg.request_id !== "string") return error("malformed");
      const pending = this.pending.get(msg.request_id);
      if (!pending || pending.sessionId !== attachment.sid) return; // Late, duplicate or unknown: discard.
      this.pending.delete(msg.request_id);
      pending.settle(isObject(msg.body) ? { kind: "result", body: msg.body } : { kind: "result", body: failure("protocol_error", "Malformed phone result.") });
      return;
    }
    if (msg.type === "register_clients") return this.registerClients(ws, msg);
    if (msg.type === "link_result") return this.linkResult(ws, msg);
    if (msg.type === "events_ack") {
      const last = this.sql.exec<{ seq: number | null }>("SELECT MAX(seq) AS seq FROM events").one().seq ?? 0;
      if (!Number.isSafeInteger(msg.up_to) || (msg.up_to as number) < 0 || (msg.up_to as number) > last) return error("malformed");
      this.setMeta("events_acked", String(Math.max(Number(this.meta("events_acked") ?? "0"), msg.up_to as number)));
      return;
    }
    return error("unknown_type");
  }

  /** Full-set registration from the authenticated phone session. Replaces previous registrations atomically. */
  private async registerClients(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const list = msg.clients;
    const requestId = typeof msg.request_id === "string" && msg.request_id.length <= 64 ? msg.request_id : null;
    const valid = Array.isArray(list) && list.length <= LIMITS.maxClients && requestId !== null && list.every((entry) =>
      isObject(entry) && typeof entry.client_id === "string" && REGISTER_ID.test(entry.client_id) &&
      typeof entry.fingerprint === "string" && FINGERPRINT.test(entry.fingerprint) && Number.isSafeInteger(entry.expires_at) &&
      (entry.oauth === undefined || parseOAuthRegistration(entry.oauth) !== null));
    const fingerprints = valid ? new Set((list as { fingerprint: string }[]).map((entry) => entry.fingerprint)) : null;
    const ids = valid ? new Set((list as { client_id: string }[]).map((entry) => entry.client_id)) : null;
    if (!valid || fingerprints?.size !== list.length || ids?.size !== list.length) {
      ws.send(JSON.stringify({ type: "ack", version: PROTOCOL_VERSION, request_id: requestId, ok: false, error: "malformed" }));
      return;
    }
    const entries = (list as { client_id: string; fingerprint: string; expires_at: number; oauth?: unknown }[]).map((entry) => ({
      ...entry, oauth: entry.oauth === undefined ? null : parseOAuthRegistration(entry.oauth) }));
    const oauthClient = this.env.OAUTH_CLIENT_ID ?? "";
    const removed = new Set<string>();
    this.ctx.storage.transactionSync(() => {
      const previous = this.sql.exec<{ fingerprint: string; first_used: number | null; client_id: string; oauth_client: string | null }>(
        "SELECT fingerprint, first_used, client_id, oauth_client FROM clients").toArray();
      const firstUse = new Map(previous.map((row) => [row.fingerprint, row.first_used]));
      const kept = new Set(entries.filter((entry) => entry.oauth).map((entry) => `${entry.client_id}|${entry.fingerprint}`));
      for (const row of previous) if (row.oauth_client !== null && !kept.has(`${row.client_id}|${row.fingerprint}`)) removed.add(row.client_id);
      this.sql.exec("DELETE FROM clients");
      for (const entry of entries) {
        this.sql.exec("INSERT INTO clients (client_id, fingerprint, expires_at, first_used, oauth_client, scopes, generation, grant_version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
          entry.client_id, entry.fingerprint, entry.expires_at, firstUse.get(entry.fingerprint) ?? null,
          entry.oauth ? oauthClient : null, entry.oauth ? entry.oauth.scopes.join(" ") : null,
          entry.oauth?.pairing_generation ?? null, entry.oauth?.grant_version ?? null);
      }
      this.sql.exec("DELETE FROM rate WHERE bucket LIKE 'client:%'");
    });
    ws.send(JSON.stringify({ type: "ack", version: PROTOCOL_VERSION, request_id: requestId, ok: true, registered: entries.length }));
    // Removing or rotating a linked client on the phone ends its OAuth grant, including refresh tokens.
    if (removed.size > 0) await this.revokeGrants((clientId) => removed.has(clientId));
  }

  // ---- OAuth linking ---------------------------------------------------------------------------
  /** A reconnect drops the phone's prompt; replay unexpired pending requests so they are not stuck until expiry. */
  private async resendPendingLinks(socket: WebSocket): Promise<void> {
    const rows = this.sql.exec<{ id: string; code: string; expires_at: number; auth_request: string }>(
      "SELECT id, code, expires_at, auth_request FROM links WHERE status = 'pending' AND expires_at > ?", Date.now() + 5_000).toArray();
    for (const row of rows) {
      const request = JSON.parse(row.auth_request) as AuthRequest;
      const scopes = request.scope.length > 0 ? request.scope.filter(isScope) : [...new Set(Object.values(SCOPE_FOR))];
      try {
        socket.send(JSON.stringify({
          type: "link_request", version: PROTOCOL_VERSION, link_id: row.id, code: row.code, binding: await linkBinding(row.id, request),
          oauth_client_id: request.clientId, redirect_host: new URL(request.redirectUri).host, scopes, expires_at: row.expires_at,
        }));
      } catch { return; }
    }
  }

  /** Cheap pre-check before the browser's one-use consent handle is spent. */
  async linkPreflight(): Promise<"ok" | "offline" | "rate_limited" | "busy"> {
    await this.applyReset();
    if (!this.currentSocket()) return "offline";
    this.sql.exec("DELETE FROM links WHERE expires_at < ?", Date.now() - LINK_TTL_MS);
    if (this.sql.exec("SELECT id FROM links WHERE status = 'pending' AND expires_at > ?", Date.now()).toArray().length > 0) return "busy";
    const hour = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM links WHERE created_at > ?", Date.now() - 3_600_000).one().n;
    if (hour >= LINKS_PER_HOUR || !this.allow("link", LINKS_PER_HOUR, false)) return "rate_limited";
    return "ok";
  }

  /** Records one pending, expiring request bound to the validated OAuth request and asks the phone owner to decide. */
  async createLink(request: AuthRequest, browserSecretHash: string): Promise<{ id: string } | { error: string }> {
    const status = await this.linkPreflight();
    if (status !== "ok") return { error: status };
    if (!this.allow("link", LINKS_PER_HOUR, true)) return { error: "rate_limited" };
    const socket = this.currentSocket();
    if (!socket) return { error: "offline" };
    const id = crypto.randomUUID();
    const code = String(new DataView(crypto.getRandomValues(new Uint8Array(4)).buffer).getUint32(0) % 1_000_000).padStart(6, "0");
    const now = Date.now(); const expires = now + LINK_TTL_MS;
    const binding = await linkBinding(id, request);
    this.sql.exec("INSERT INTO links (id, code, created_at, expires_at, status, browser_hash, auth_request) VALUES (?, ?, ?, ?, 'pending', ?, ?)",
      id, code, now, expires, browserSecretHash, JSON.stringify(request));
    const scopes = request.scope.length > 0 ? request.scope.filter(isScope) : [...new Set(Object.values(SCOPE_FOR))];
    try {
      socket.send(JSON.stringify({
        type: "link_request", version: PROTOCOL_VERSION, link_id: id, code, binding, oauth_client_id: request.clientId,
        redirect_host: new URL(request.redirectUri).host, scopes, expires_at: expires,
      }));
    } catch { this.sql.exec("DELETE FROM links WHERE id = ?", id); return { error: "offline" }; }
    return { id };
  }

  /** Browser poll. Consumes an approved link exactly once; never reveals metadata beyond the matching code. */
  async linkStatus(id: string, browserSecretHash: string): Promise<LinkView> {
    await this.applyReset();
    const row = this.sql.exec<{ code: string; expires_at: number; status: string; browser_hash: string; auth_request: string; redirect_to: string | null }>(
      "SELECT code, expires_at, status, browser_hash, auth_request, redirect_to FROM links WHERE id = ?", id).toArray()[0];
    if (!row || !digestsEqual(row.browser_hash, browserSecretHash)) return { state: "unknown" };
    if (row.status === "approved" && row.redirect_to) {
      this.sql.exec("DELETE FROM links WHERE id = ?", id);
      return { state: "redirect", to: row.redirect_to };
    }
    if (row.status === "pending" && row.expires_at > Date.now()) return { state: "pending", code: row.code, expires_at: row.expires_at };
    this.sql.exec("DELETE FROM links WHERE id = ?", id);
    return { state: "closed", request: JSON.parse(row.auth_request) as AuthRequest };
  }

  private async linkResult(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    const reply = (ok: boolean, error?: string) => ws.send(JSON.stringify({ type: "link_ack", version: PROTOCOL_VERSION, link_id: msg.link_id, ok, ...(error ? { error } : {}) }));
    const id = typeof msg.link_id === "string" && REGISTER_ID.test(msg.link_id) ? msg.link_id : null;
    const row = id ? this.sql.exec<{ expires_at: number; status: string; auth_request: string }>(
      "SELECT expires_at, status, auth_request FROM links WHERE id = ?", id).toArray()[0] : undefined;
    if (!id || !row || row.status !== "pending" || row.expires_at <= Date.now()) return reply(false, "expired");
    const request = JSON.parse(row.auth_request) as AuthRequest;
    if (msg.binding !== await linkBinding(id, request)) return reply(false, "binding_mismatch");
    if (msg.approved !== true) {
      this.sql.exec("UPDATE links SET status = 'denied' WHERE id = ?", id);
      return reply(true);
    }
    const requested = new Set(request.scope.length > 0 ? request.scope : Object.values(SCOPE_FOR));
    const scopes = Array.isArray(msg.scopes) ? [...new Set(msg.scopes)] : [];
    const registration = parseOAuthRegistration({ client_id: msg.client_id, scopes, pairing_generation: msg.pairing_generation, grant_version: msg.grant_version });
    const secret = typeof msg.grant_secret === "string" && TOKEN_PATTERN.test(msg.grant_secret) ? msg.grant_secret : null;
    const expiresAt = Number.isSafeInteger(msg.expires_at) ? msg.expires_at as number : 0;
    const fail = (reason: string) => reply(false, reason);
    if (!registration || registration.scopes.length === 0) return fail("bad_registration");
    if (!secret) return fail("bad_secret");
    if (!registration.scopes.every((scope) => requested.has(scope))) return fail("scope_mismatch");
    if (registration.pairing_generation !== this.pairingGeneration()) return fail("pairing_generation");
    if (expiresAt <= Date.now() || expiresAt > Date.now() + GRANT_TTL_S * 1000 + 3_600_000) return fail("bad_expiry");
    if (typeof msg.fingerprint !== "string" || msg.fingerprint !== await sha256Hex(secret)) return fail("fingerprint");
    if (this.sql.exec("SELECT client_id FROM clients WHERE client_id = ? AND oauth_client IS NULL", registration.client_id).toArray().length > 0) {
      return fail("client_conflict");
    }
    const oauthClient = this.env.OAUTH_CLIENT_ID ?? "";
    if (!this.env.OAUTH_KV || request.clientId !== oauthClient) return reply(false, "not_configured");
    const props: GrantProps = { v: 1, client_id: registration.client_id, grant_secret: secret, oauth_client_id: oauthClient,
      pairing_generation: registration.pairing_generation, grant_version: registration.grant_version };
    const { redirectTo } = await authorizationServer(this.env.PUBLIC_ORIGIN).getOAuthApi(this.env).completeAuthorization({
      request, userId: OWNER_USER, scope: registration.scopes, props, metadata: { client_id: registration.client_id }, revokeExistingGrants: false,
    });
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("DELETE FROM clients WHERE client_id = ?", registration.client_id);
      this.sql.exec("INSERT INTO clients (client_id, fingerprint, expires_at, first_used, oauth_client, scopes, generation, grant_version) VALUES (?, ?, ?, NULL, ?, ?, ?, ?)",
        registration.client_id, msg.fingerprint as string, expiresAt, oauthClient, registration.scopes.join(" "), registration.pairing_generation, registration.grant_version);
      this.sql.exec("UPDATE links SET status = 'approved', redirect_to = ? WHERE id = ?", redirectTo, id);
    });
    this.event("client_linked", null);
    reply(true);
  }

  // ---- MCP adapter -----------------------------------------------------------------------------
  /** Anonymous protocol-level limiter for /mcp; consumed before parsing. */
  async mcpAllow(): Promise<boolean> {
    await this.applyReset();
    return this.allow("mcp_anon", LIMITS.unauthenticatedPerMinute, true);
  }

  /** Runs one health operation for a validated OAuth access token. The backend checks every binding before relaying. */
  async mcpExecute(operation: Operation, args: Record<string, unknown>, props: GrantProps, tokenScopes: string[]): Promise<McpOutcome> {
    await this.applyReset();
    const outcome = (category: ErrorCategory, message: string): McpOutcome => ({ status: statusFor(category), body: failure(category, message) });
    const oauthClient = this.env.OAUTH_CLIENT_ID ?? "";
    if (!props || props.v !== 1 || typeof props.grant_secret !== "string" || !TOKEN_PATTERN.test(props.grant_secret)) return outcome("unauthorized", "Grant is not valid.");
    const row = this.sql.exec<OAuthClientRow>(
      "SELECT client_id, expires_at, first_used, oauth_client, scopes, generation, grant_version FROM clients WHERE fingerprint = ?",
      await sha256Hex(props.grant_secret)).toArray()[0];
    const scope = SCOPE_FOR[operation];
    if (!row || row.expires_at <= Date.now() || row.oauth_client !== oauthClient || props.oauth_client_id !== oauthClient ||
      row.client_id !== props.client_id || row.generation !== props.pairing_generation || row.generation !== this.pairingGeneration() ||
      row.grant_version !== props.grant_version) {
      this.event("auth_failures", "oauth");
      return outcome("unauthorized", "The linked grant was revoked, expired or replaced. Reconnect the app.");
    }
    if (!tokenScopes.includes(scope) || !(row.scopes ?? "").split(" ").includes(scope)) return outcome("permission_denied", `This grant lacks the ${scope} scope.`);
    if (!this.allow(`client:${row.client_id}`, LIMITS.clientCallsPerMinute, true)) return outcome("rate_limited", "Too many requests for this client.");
    const invalid = validateArguments(operation, args);
    if (invalid) return outcome("invalid_input", invalid);
    const socket = this.currentSocket();
    const attachment = socket?.deserializeAttachment() as Attachment | null | undefined;
    if (!socket || !attachment) return outcome("phone_unavailable", "The phone bridge is not connected. Open the app and start bridge access.");
    if (this.pending.size >= LIMITS.inFlight) return outcome("rate_limited", "Too many requests are in progress.");
    if (isDelete(operation) && [...this.pending.values()].filter((p) => isDelete(p.operation)).length >= LIMITS.pendingDeletes) {
      return outcome("approval_pending", "Another deletion is awaiting phone approval.");
    }
    if (row.first_used === null) {
      this.sql.exec("UPDATE clients SET first_used = ? WHERE client_id = ?", Date.now(), row.client_id);
      this.event("client_first_use", row.client_id);
    }
    const auth = { kind: "oauth", oauth_client_id: oauthClient, client_id: row.client_id, grant_version: row.grant_version,
      pairing_generation: row.generation, scopes: (row.scopes ?? "").split(" ") };
    const settled = await this.dispatch(socket, attachment.sid, operation, args, props.grant_secret, auth);
    const response = this.toResponse(operation, settled);
    return { status: response.status, body: await response.json() as Record<string, unknown> };
  }

  override async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    this.detach(ws);
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, reason); } catch { /* already closed */ }
  }
  override async webSocketError(ws: WebSocket): Promise<void> { this.detach(ws); }
  private detach(ws: WebSocket): void {
    const attachment = ws.deserializeAttachment() as Attachment | null;
    if (!attachment) return;
    if (attachment.sid === this.meta("current_session_id")) this.setMeta("current_session_id", null);
    this.settleAll((pending) => pending.sessionId === attachment.sid, { kind: "disconnected" });
  }

  // ---- ChatGPT Actions adapter -----------------------------------------------------------------
  private deadlineMs(): number {
    const configured = Number.parseInt(this.env.RELAY_DEADLINE_MS ?? "", 10);
    return Number.isSafeInteger(configured) ? Math.min(Math.max(configured, 100), LIMITS.relayDeadlineMs) : LIMITS.relayDeadlineMs;
  }

  private async authenticateClient(request: Request): Promise<ClientRow | null> {
    const token = bearer(request);
    if (!token || !TOKEN_PATTERN.test(token)) return null;
    const row = this.sql.exec<{ client_id: string; expires_at: number; first_used: number | null }>(
      "SELECT client_id, expires_at, first_used FROM clients WHERE fingerprint = ? AND oauth_client IS NULL", await sha256Hex(token)).toArray()[0];
    return row && row.expires_at > Date.now() ? row : null;
  }

  private async action(request: Request, url: URL): Promise<Response> {
    const route = routeAction(request.method, url);
    if (!route) return fail("not_found", "Unknown operation.");
    if (!this.allow("auth_fail", LIMITS.authFailuresPerMinute, false)) return fail("rate_limited", "Too many failed attempts.");
    const client = await this.authenticateClient(request);
    if (!client) {
      this.allow("auth_fail", LIMITS.authFailuresPerMinute, true);
      this.event("auth_failures", "client");
      return fail("unauthorized", "Missing, invalid, expired or revoked client credential.", { "www-authenticate": 'Bearer realm="AndroidMcpBridge"' });
    }
    if (!this.allow(`client:${client.client_id}`, LIMITS.clientCallsPerMinute, true)) return fail("rate_limited", "Too many requests for this client.");
    let args = route.arguments;
    if (args === null) {
      const type = request.headers.get("content-type")?.split(";")[0]?.trim().toLowerCase();
      if (type !== "application/json") return respond(415, failure("invalid_input", "Use application/json."));
      const text = await readBoundedText(request.body, LIMITS.inputBytes);
      if (text === "too_large") return respond(413, failure("invalid_input", "Request body exceeds 64 KiB."));
      args = text === "malformed" ? null : parseObject(text);
      if (!args) return fail("invalid_input", "Body must be a UTF-8 JSON object.");
    }
    const invalid = validateArguments(route.operation, args);
    if (invalid) return fail("invalid_input", invalid);
    const socket = this.currentSocket();
    const attachment = socket?.deserializeAttachment() as Attachment | null | undefined;
    if (!socket || !attachment) return fail("phone_unavailable", "The phone bridge is not connected. Open the app and start bridge access.");
    if (this.pending.size >= LIMITS.inFlight) return fail("rate_limited", "Too many requests are in progress.");
    if (isDelete(route.operation) && [...this.pending.values()].filter((p) => isDelete(p.operation)).length >= LIMITS.pendingDeletes) {
      return fail("approval_pending", "Another deletion is awaiting phone approval.");
    }
    if (client.first_used === null) {
      this.sql.exec("UPDATE clients SET first_used = ? WHERE client_id = ?", Date.now(), client.client_id);
      this.event("client_first_use", client.client_id);
    }
    const settled = await this.dispatch(socket, attachment.sid, route.operation, args, bearer(request) as string);
    return this.toResponse(route.operation, settled);
  }

  private dispatch(socket: WebSocket, sid: string, operation: Operation, args: Record<string, unknown>, token: string,
    auth?: Record<string, unknown>): Promise<Settled> {
    const requestId = randomToken(18);
    const deadline = this.deadlineMs();
    return new Promise<Settled>((resolve) => {
      const timer = setTimeout(() => {
        if (!this.pending.delete(requestId)) return;
        try { socket.send(JSON.stringify({ type: "cancel", version: PROTOCOL_VERSION, session_id: sid, request_id: requestId })); } catch { /* closed */ }
        resolve({ kind: "timeout" });
      }, deadline);
      this.pending.set(requestId, { sessionId: sid, operation, settle: (result) => { clearTimeout(timer); resolve(result); } });
      try {
        socket.send(JSON.stringify({
          type: "command", version: PROTOCOL_VERSION, session_id: sid, request_id: requestId,
          expires_at: new Date(Date.now() + deadline).toISOString(), operation, client_token: token, arguments: args,
          ...(auth ? { auth } : {}),
        }));
      } catch {
        this.pending.get(requestId)?.settle({ kind: "disconnected" });
        this.pending.delete(requestId);
      }
    });
  }

  private toResponse(operation: Operation, settled: Settled): Response {
    const mutating = isWrite(operation) || isDelete(operation);
    if (settled.kind === "timeout") {
      return mutating
        ? fail("outcome_unknown", "The phone did not answer in time. The change may or may not have been applied; read back or retry with the same idempotency_key.")
        : fail("expired_request", "The phone did not answer in time.");
    }
    if (settled.kind === "disconnected") {
      return mutating
        ? fail("outcome_unknown", "The phone disconnected. The change may or may not have been applied; read back or retry with the same idempotency_key.")
        : fail("phone_unavailable", "The phone disconnected before answering.");
    }
    const serialized = JSON.stringify(settled.body);
    if (!withinOutputBudget(serialized)) return fail("result_too_large", "The result exceeds 64 KiB. Query a shorter date range.");
    const category = settled.body.error_category;
    return new Response(serialized, {
      status: settled.body.success === false || category !== undefined ? statusFor(category) : 200,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" },
    });
  }
}

function parseOAuthRegistration(value: unknown): OAuthRegistration | null {
  if (!isObject(value)) return null;
  const { client_id, scopes, pairing_generation, grant_version } = value;
  if (typeof client_id !== "string" || !REGISTER_ID.test(client_id) || !Array.isArray(scopes) || scopes.length > 64 ||
    !scopes.every(isScope) || new Set(scopes).size !== scopes.length || !Number.isSafeInteger(pairing_generation) ||
    !Number.isSafeInteger(grant_version) || (grant_version as number) < 1) return null;
  return { client_id, scopes: scopes as Scope[], pairing_generation: pairing_generation as number, grant_version: grant_version as number };
}

/** Binds a phone decision to the exact validated authorization request (client, redirect, resource, scopes, state, PKCE). */
export async function linkBinding(id: string, request: AuthRequest): Promise<string> {
  return sha256Hex(JSON.stringify([id, request.clientId, request.redirectUri, request.resource ?? null, [...request.scope].sort(),
    request.state, request.codeChallenge ?? null, request.codeChallengeMethod ?? null]));
}
