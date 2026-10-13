import { BridgeObject, respond, type Env } from "./bridge";
import { failure, LIMITS } from "./protocol";
import { handleAuthorize } from "./authorize";
import { handleMcp, type McpAuth } from "./mcp";
import { authorizationServer, bearerChallenge, ensureClient, mcpResource, oauthConfig, protectedResourceMetadata, type GrantProps } from "./oauth";

export { BridgeObject };

/** Returns the canonical origin only for HTTPS, or plain HTTP on loopback for local development. */
export function configuredOrigin(value: string | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    const loopback = url.hostname === "localhost" || url.hostname === "127.0.0.1";
    if (url.protocol !== "https:" && !(url.protocol === "http:" && loopback)) return null;
    if (url.username || url.password || url.search || url.hash || url.pathname !== "/" || url.origin !== value) return null;
    return url.origin;
  } catch { return null; }
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff" };

/** OpenAI hosts the consent interaction; the OAuth library still requires its per-request handle and browser-bound cookie. */
function isAllowedOAuthRequest(request: Request, origin: string): boolean {
  const sent = request.headers.get("origin");
  return sent === null || sent === origin || sent === "https://chatgpt.com" ||
    (sent === "null" && request.headers.get("sec-fetch-site") === "same-origin");
}

async function mcp(request: Request, env: Env, origin: string): Promise<Response> {
  if (request.method !== "POST") return new Response(null, { status: 405, headers: { allow: "POST", "cache-control": "no-store" } });
  const stub = env.BRIDGE.get(env.BRIDGE.idFromName("owner"));
  if (!(await stub.mcpAllow())) return respond(429, failure("rate_limited", "Too many requests."));
  const header = request.headers.get("authorization");
  let auth: McpAuth | null = null;
  if (header !== null) {
    const config = await oauthConfig(env);
    const token = header.startsWith("Bearer ") ? header.slice(7) : "";
    const validated = config && token.length > 0 && token.length <= 512
      ? await authorizationServer(origin).validateToken<GrantProps>(mcpResource(origin), token, env) : null;
    if (!validated || validated.clientId !== config?.clientId || validated.audience !== mcpResource(origin) || validated.expiresAt * 1000 <= Date.now()) {
      return new Response(JSON.stringify({ error: "invalid_token" }), {
        status: 401, headers: { ...JSON_HEADERS, "www-authenticate": bearerChallenge(origin, "invalid_token") } });
    }
    auth = { props: validated.props, scopes: validated.scope };
  }
  const response = await handleMcp(request, env, origin, auth);
  const headers = new Headers(response.headers);
  headers.set("cache-control", "no-store"); headers.set("x-content-type-options", "nosniff");
  return new Response(response.body, { status: response.status, headers });
}

async function oauthEndpoint(request: Request, env: Env, ctx: ExecutionContext, origin: string): Promise<Response> {
  const config = await oauthConfig(env);
  if (!config) return respond(503, failure("not_configured", "OAuth is not configured."));
  await ensureClient(env, origin, config);
  const response = await authorizationServer(origin).fetch(request, env, ctx);
  const headers = new Headers(response.headers);
  headers.set("cache-control", "no-store"); headers.set("x-content-type-options", "nosniff");
  // Advertise only what this instance supports: confidential predefined client, no dynamic registration.
  if (new URL(request.url).pathname === "/.well-known/oauth-authorization-server" && response.ok) {
    const metadata = await response.json() as Record<string, unknown>;
    delete metadata.registration_endpoint; delete metadata.client_id_metadata_document_supported;
    metadata.token_endpoint_auth_methods_supported = ["client_secret_post", "client_secret_basic"];
    metadata.code_challenge_methods_supported = ["S256"];
    metadata.grant_types_supported = ["authorization_code", "refresh_token"];
    metadata.authorization_response_iss_parameter_supported = true;
    headers.delete("content-length");
    return new Response(JSON.stringify(metadata), { status: 200, headers });
  }
  return new Response(response.body, { status: response.status, headers });
}

export default {
  async fetch(request, env, ctx): Promise<Response> {
    const origin = configuredOrigin(env.PUBLIC_ORIGIN);
    if (!origin) return respond(503, failure("not_configured", "PUBLIC_ORIGIN is missing or invalid."));
    const url = new URL(request.url);
    if (url.origin !== origin) return respond(421, failure("not_found", "Unexpected host."));
    const length = Number(request.headers.get("content-length") ?? "0");
    if (!Number.isFinite(length) || length > LIMITS.inputBytes) return respond(413, failure("invalid_input", "Request body exceeds 64 KiB."));
    const path = url.pathname;
    // The browser consent pages accept only this instance's own Origin; their POSTs require it.
    if (path === "/authorize" || path.startsWith("/authorize/")) {
      if (!isAllowedOAuthRequest(request, origin)) return respond(403, failure("unauthorized", "Cross-origin request refused."));
      return handleAuthorize(request, env, origin);
    }
    if (path === "/mcp") {
      if (!isAllowedOAuthRequest(request, origin)) return respond(403, failure("unauthorized", "Cross-origin request refused."));
      return mcp(request, env, origin);
    }
    if (path === "/.well-known/oauth-protected-resource" || path === "/.well-known/oauth-protected-resource/mcp") {
      if (request.method !== "GET") return respond(405, failure("not_found", "Use GET."));
      return new Response(JSON.stringify(protectedResourceMetadata(origin)), { headers: JSON_HEADERS });
    }
    if (path === "/.well-known/oauth-authorization-server" || path === "/oauth/token") {
      return oauthEndpoint(request, env, ctx, origin);
    }
    // Browsers attach Origin; the phone and server-side ChatGPT Actions calls do not need cross-origin access.
    if (request.headers.has("origin")) return respond(403, failure("unauthorized", "Browser origins are not accepted."));
    if (!path.startsWith("/v1/") && !path.startsWith("/device/")) return respond(404, failure("not_found", "Not found."));
    return env.BRIDGE.get(env.BRIDGE.idFromName("owner")).fetch(request);
  },
} satisfies ExportedHandler<Env>;
