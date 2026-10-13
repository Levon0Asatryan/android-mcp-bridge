import { calculateJwkThumbprint, compactVerify, EmbeddedJWK, importJWK, type JWK } from "jose";
import { LIMITS, PROTOCOL_VERSION, isObject } from "./protocol";

export interface PublicDeviceKey { kty: "EC"; crv: "P-256"; x: string; y: string }

export function randomToken(bytes = 32): string {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time comparison of two equal-length digests. */
export function digestsEqual(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  if (left.byteLength !== right.byteLength) return false;
  return crypto.subtle.timingSafeEqual(left, right);
}

function publicKey(value: unknown): PublicDeviceKey | null {
  if (!isObject(value) || value.kty !== "EC" || value.crv !== "P-256" || "d" in value) return null;
  const b64 = /^[A-Za-z0-9_-]{43}$/;
  if (typeof value.x !== "string" || typeof value.y !== "string" || !b64.test(value.x) || !b64.test(value.y)) return null;
  return { kty: "EC", crv: "P-256", x: value.x, y: value.y };
}

export const thumbprint = (key: PublicDeviceKey) => calculateJwkThumbprint(key as JWK, "sha256");

export interface ProofClaims { nonce: string; jti: string; pairing_generation?: number }

function checkClaims(payload: Uint8Array, purpose: "pair" | "connect", audience: string, nowS: number): ProofClaims | null {
  let claims: unknown;
  try { claims = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(payload)); } catch { return null; }
  if (!isObject(claims)) return null;
  const { v, aud, nonce, iat, exp, jti } = claims;
  if (v !== PROTOCOL_VERSION || claims.purpose !== purpose || aud !== audience) return null;
  if (typeof nonce !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(nonce)) return null;
  if (typeof jti !== "string" || jti.length < 16 || jti.length > 64) return null;
  if (!Number.isInteger(iat) || !Number.isInteger(exp)) return null;
  const issued = iat as number;
  const expires = exp as number;
  if (expires <= issued || expires - issued > LIMITS.proofLifetimeS) return null;
  if (nowS + LIMITS.clockSkewS < issued || nowS > expires) return null;
  const result: ProofClaims = { nonce, jti };
  if (purpose === "connect") {
    if (!Number.isInteger(claims.pairing_generation)) return null;
    result.pairing_generation = claims.pairing_generation as number;
  }
  return result;
}

/** Pairing proof: compact JWS (ES256) whose protected header embeds the new device's public JWK. */
export async function verifyPairingProof(jws: string, audience: string, nowS: number)
  : Promise<{ key: PublicDeviceKey; claims: ProofClaims } | null> {
  if (jws.length > 4096) return null;
  try {
    const { payload, protectedHeader } = await compactVerify(jws, EmbeddedJWK, { algorithms: ["ES256"] });
    if (protectedHeader.typ !== "hb-pair+jwt") return null;
    const key = publicKey(protectedHeader.jwk);
    const claims = key && checkClaims(payload, "pair", audience, nowS);
    return key && claims ? { key, claims } : null;
  } catch { return null; }
}

/** Reconnect proof: compact JWS (ES256) signed by the already registered device key. */
export async function verifyConnectProof(jws: string, key: PublicDeviceKey, kid: string, audience: string, nowS: number)
  : Promise<ProofClaims | null> {
  if (jws.length > 4096) return null;
  try {
    const { payload, protectedHeader } = await compactVerify(jws, await importJWK(key as JWK, "ES256"), { algorithms: ["ES256"] });
    if (protectedHeader.typ !== "hb-connect+jwt" || protectedHeader.kid !== kid || "jwk" in protectedHeader) return null;
    return checkClaims(payload, "connect", audience, nowS);
  } catch { return null; }
}
