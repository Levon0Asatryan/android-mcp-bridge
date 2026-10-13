import { OAuthAuthorizationServer } from "@cloudflare/workers-oauth-provider";
import type { Env } from "./bridge";
import { SCOPES } from "./scopes";

export const OWNER_USER = "owner";
export const ACCESS_TOKEN_TTL_S = 900;
export const GRANT_TTL_S = 90 * 86_400;

/** Encrypted into the provider's token-wrapped props. Never returned to MCP clients or logged. */
export interface GrantProps {
  v: 1;
  client_id: string;
  grant_secret: string;
  oauth_client_id: string;
  pairing_generation: number;
  grant_version: number;
}

export interface OAuthConfig { clientId: string; secretHash: string; redirectUri: string }

const CLIENT_ID = /^[A-Za-z0-9._~-]{8,128}$/;

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Operator-provided predefined client. Exact HTTPS redirect URI, no wildcards, no dynamic registration. */
export async function oauthConfig(env: Env): Promise<OAuthConfig | null> {
  const id = env.OAUTH_CLIENT_ID; const secret = env.OAUTH_CLIENT_SECRET; const redirect = env.OAUTH_REDIRECT_URI;
  if (!env.OAUTH_KV || !id || !secret || !redirect || !CLIENT_ID.test(id) || secret.length < 32) return null;
  try {
    const url = new URL(redirect);
    if (url.protocol !== "https:" || url.hash || url.username || url.password || url.href !== redirect) return null;
  } catch { return null; }
  return { clientId: id, secretHash: await sha256Hex(secret), redirectUri: redirect };
}

export const mcpResource = (origin: string) => `${origin}/mcp`;
export const resourceMetadataUrl = (origin: string) => `${origin}/.well-known/oauth-protected-resource/mcp`;

export function authorizationServer(origin: string): OAuthAuthorizationServer<Env> {
  return new OAuthAuthorizationServer<Env>({
    issuer: origin,
    resources: [mcpResource(origin)],
    defaultResource: mcpResource(origin),
    authorizeEndpoint: `${origin}/authorize`,
    tokenEndpoint: `${origin}/oauth/token`,
    scopesSupported: [...SCOPES],
    accessTokenTTL: ACCESS_TOKEN_TTL_S,
    refreshTokenTTL: GRANT_TTL_S,
    disallowPublicClientRegistration: true,
  });
}

/** Writes the predefined client record in the provider's documented KV schema; replaces a previously configured client. */
export async function ensureClient(env: Env, origin: string, config: OAuthConfig): Promise<void> {
  const kv = env.OAUTH_KV as KVNamespace;
  const record = {
    clientId: config.clientId, clientSecret: config.secretHash, redirectUris: [config.redirectUri], clientName: "ChatGPT",
    grantTypes: ["authorization_code", "refresh_token"], responseTypes: ["code"], tokenEndpointAuthMethod: "client_secret_post",
    registrationDate: 0,
  };
  const serialized = JSON.stringify(record);
  if (await kv.get(`client:${config.clientId}`) === serialized) return;
  const previous = await kv.get("config:client_id");
  if (previous && previous !== config.clientId) await authorizationServer(origin).getOAuthApi(env).deleteClient(previous);
  await kv.put(`client:${config.clientId}`, serialized);
  await kv.put("config:client_id", config.clientId);
}

export function protectedResourceMetadata(origin: string): Record<string, unknown> {
  return {
    resource: mcpResource(origin), authorization_servers: [origin], scopes_supported: [...SCOPES],
    bearer_methods_supported: ["header"], resource_name: "Android MCP Bridge",
  };
}

export function bearerChallenge(origin: string, error?: "invalid_token" | "insufficient_scope", scope?: string): string {
  let value = `Bearer resource_metadata="${resourceMetadataUrl(origin)}"`;
  if (error) value += `, error="${error}"`;
  if (scope) value += `, scope="${scope}"`;
  return value;
}
