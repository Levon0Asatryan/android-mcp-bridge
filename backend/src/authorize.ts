import { AuthorizationError, authorizationErrorRedirect, type AuthRequest } from "@cloudflare/workers-oauth-provider";
import type { Env, LinkView } from "./bridge";
import { sha256Hex, randomToken } from "./auth";
import { authorizationServer, ensureClient, mcpResource, oauthConfig } from "./oauth";
import { readBoundedText } from "./protocol";
import { isScope } from "./scopes";

const LINK_COOKIE = "__Host-hb-link";

function isAllowedConsentOrigin(request: Request, origin: string): boolean {
  const sent = request.headers.get("origin");
  return sent === origin || sent === "https://chatgpt.com" ||
    (sent === "null" && request.headers.get("sec-fetch-site") === "same-origin");
}
export const PAGE_HEADERS: Record<string, string> = {
  "content-type": "text/html; charset=utf-8", "cache-control": "no-store", pragma: "no-cache", "x-content-type-options": "nosniff",
  "referrer-policy": "no-referrer", "x-frame-options": "DENY",
  "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
};

function page(title: string, body: string, init: { status?: number; headers?: Headers; refresh?: number } = {}): Response {
  const headers = new Headers(init.headers);
  for (const [key, value] of Object.entries(PAGE_HEADERS)) headers.set(key, value);
  const refresh = init.refresh ? `<meta http-equiv="refresh" content="${init.refresh}">` : "";
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">${refresh}
<title>${title}</title><style>body{font:16px system-ui,sans-serif;background:#111;color:#eee;max-width:32rem;margin:3rem auto;padding:0 1rem}
button{font:inherit;padding:.7rem 1.2rem;border-radius:.5rem;border:1px solid #888;background:#eee;color:#111}code{font-size:2rem;letter-spacing:.3rem}</style>
</head><body><h1>${title}</h1>${body}</body></html>`, { status: init.status ?? 200, headers });
}

const redirect = (to: string, headers?: Headers): Response => {
  const out = new Headers(headers);
  out.set("location", to); out.set("cache-control", "no-store"); out.set("referrer-policy", "no-referrer");
  return new Response(null, { status: 303, headers: out });
};

const stub = (env: Env) => env.BRIDGE.get(env.BRIDGE.idFromName("owner"));

function cookie(request: Request, name: string): string | null {
  for (const part of (request.headers.get("cookie") ?? "").split(";")) {
    const [key, ...value] = part.trim().split("=");
    if (key === name) return value.join("=");
  }
  return null;
}

/** Validates the OAuth request strictly; error redirects carry iss (RFC 9207). */
async function validate(request: Request, env: Env, origin: string): Promise<AuthRequest | Response> {
  const config = await oauthConfig(env);
  if (!config) return page("Not available", "<p>OAuth is not configured on this instance.</p>", { status: 503 });
  await ensureClient(env, origin, config);
  let auth: AuthRequest;
  try { auth = await authorizationServer(origin).getOAuthApi(env).parseAuthRequest(request); }
  catch (error) {
    if (error instanceof AuthorizationError && error.redirectTo) return redirect(error.redirectTo);
    return page("Request rejected", "<p>This authorization request is not valid.</p>", { status: 400 });
  }
  const fail = (description: string) => redirect(authorizationErrorRedirect(auth, "invalid_request", description));
  if (auth.clientId !== config.clientId || auth.redirectUri !== config.redirectUri) {
    return page("Request rejected", "<p>This authorization request is not valid.</p>", { status: 400 });
  }
  if (auth.responseType !== "code") return redirect(authorizationErrorRedirect(auth, "unsupported_response_type"));
  if (!auth.codeChallenge || auth.codeChallengeMethod !== "S256") return fail("PKCE with S256 is required.");
  if ((auth.resource ?? mcpResource(origin)) !== mcpResource(origin)) return redirect(authorizationErrorRedirect(auth, "invalid_target"));
  if (!auth.scope.every(isScope)) return redirect(authorizationErrorRedirect(auth, "invalid_scope"));
  return auth;
}

export async function handleAuthorize(request: Request, env: Env, origin: string): Promise<Response> {
  const url = new URL(request.url);
  const route = `${request.method} ${url.pathname}`;
  if (route === "GET /authorize") {
    if (!(await stub(env).mcpAllow())) return page("Try later", "<p>Too many requests.</p>", { status: 429 });
    const auth = await validate(request, env, origin);
    if (auth instanceof Response) return auth;
    const { handle, headers } = await authorizationServer(origin).getOAuthApi(env).beginConsent(auth);
    return page("Connect Health Bridge", `<p>An app is asking to connect to your Health Bridge. Approval happens only on your paired phone,
with screen lock confirmation. Keep this page open.</p><form method="post" action="/authorize/link"><input type="hidden" name="handle" value="${escapeHtml(handle)}">
<button type="submit">Send approval request to my phone</button></form>
<form method="post" action="/authorize/deny"><input type="hidden" name="handle" value="${escapeHtml(handle)}"><button type="submit">Cancel</button></form>`, { headers });
  }
  if (route === "POST /authorize/link" || route === "POST /authorize/deny") {
    if (!isAllowedConsentOrigin(request, origin)) return page("Request rejected", "<p>Cross-site form submission refused.</p>", { status: 403 });
    if (request.headers.get("content-type")?.split(";")[0]?.trim() !== "application/x-www-form-urlencoded") return page("Request rejected", "", { status: 415 });
    const text = await readBoundedText(request.body, 4096);
    if (text === "too_large" || text === "malformed") return page("Request rejected", "", { status: 400 });
    const handle = new URLSearchParams(text).get("handle") ?? "";
    const api = authorizationServer(origin).getOAuthApi(env);
    if (!(await oauthConfig(env))) return page("Not available", "", { status: 503 });
    try {
      if (route === "POST /authorize/deny") {
        const denied = await api.denyConsent(request, handle);
        return redirect(denied.redirectTo, denied.headers);
      }
      const preflight = await stub(env).linkPreflight();
      if (preflight !== "ok") {
        const message = preflight === "offline" ? "Your phone bridge is not connected. Open Health Bridge on the phone, then reload this page."
          : preflight === "busy" ? "Another approval request is already waiting. Finish or let it expire, then reload this page." : "Too many approval requests. Try again later.";
        return page("Cannot send request", `<p>${message}</p>`, { status: preflight === "offline" ? 503 : 429 });
      }
      const approved = await api.approveConsent(request, handle);
      const secret = randomToken(32);
      const created = await stub(env).createLink(approved.request, await sha256Hex(secret));
      if ("error" in created) return redirect(authorizationErrorRedirect(approved.request, "temporarily_unavailable", "The phone could not be asked."), approved.headers);
      const headers = new Headers(approved.headers);
      headers.append("set-cookie", `${LINK_COOKIE}=${secret}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=300`);
      return redirect(`/authorize/wait?link=${encodeURIComponent(created.id)}`, headers);
    } catch (error) {
      if (error instanceof AuthorizationError) return page("Request expired", "<p>Start the connection again from the app.</p>", { status: 400 });
      throw error;
    }
  }
  if (route === "GET /authorize/wait") {
    const id = url.searchParams.get("link") ?? "";
    const secret = cookie(request, LINK_COOKIE);
    if (!/^[0-9a-f-]{36}$/.test(id) || !secret || secret.length > 128) return page("Request expired", "<p>Start the connection again from the app.</p>", { status: 400 });
    const view: LinkView = await stub(env).linkStatus(id, await sha256Hex(secret));
    const clear = new Headers({ "set-cookie": `${LINK_COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=0` });
    if (view.state === "redirect") return redirect(view.to, clear);
    if (view.state === "closed") return redirect(authorizationErrorRedirect(view.request, "access_denied", "Not approved on the phone."), clear);
    if (view.state === "unknown") return page("Request expired", "<p>Start the connection again from the app.</p>", { status: 400, headers: clear });
    return page("Approve on your phone", `<p>Open Health Bridge on your paired phone and confirm the request showing this code:</p>
<p><code>${view.code}</code></p><p>This page refreshes automatically. The request expires in two minutes.</p>`, { refresh: 3 });
  }
  return page("Not found", "", { status: 404 });
}

function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}
