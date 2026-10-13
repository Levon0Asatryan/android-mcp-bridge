// Synthetic device and client credentials generated per test. No real keys or tokens.
export const ORIGIN = "https://bridge.example.test";
export const BOOTSTRAP = "test-bootstrap-0123456789abcdefghijklmnopqrstuvwxyz";

export function b64u(input: Uint8Array | string): string {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export interface Device { key: CryptoKey; jwk: { kty: "EC"; crv: "P-256"; x: string; y: string } }
export async function newDevice(): Promise<Device> {
  const pair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]) as CryptoKeyPair;
  const jwk = await crypto.subtle.exportKey("jwk", pair.publicKey) as JsonWebKey;
  return { key: pair.privateKey, jwk: { kty: "EC", crv: "P-256", x: jwk.x as string, y: jwk.y as string } };
}

export async function sign(header: object, payload: object, key: CryptoKey): Promise<string> {
  const input = `${b64u(JSON.stringify(header))}.${b64u(JSON.stringify(payload))}`;
  const signature = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(input));
  return `${input}.${b64u(new Uint8Array(signature))}`;
}

export function claims(purpose: string, aud: string, nonce: string, extra: object = {}) {
  const now = Math.floor(Date.now() / 1000);
  return { v: 1, purpose, aud, nonce, iat: now, exp: now + 120, jti: crypto.randomUUID(), ...extra };
}

export function clientToken(): string {
  return `hb.${crypto.randomUUID()}.${b64u(crypto.getRandomValues(new Uint8Array(32)))}`;
}

export async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

type Message = Record<string, any>;
export class PhoneSocket {
  readonly messages: Message[] = [];
  private consumed = new Set<Message>();
  private waiters: (() => void)[] = [];
  closeCode: number | null = null;
  constructor(readonly ws: WebSocket) {
    ws.addEventListener("message", (event) => {
      this.messages.push(JSON.parse(event.data as string) as Message);
      this.waiters.splice(0).forEach((wake) => wake());
    });
    ws.addEventListener("close", (event) => {
      this.closeCode = event.code;
      this.waiters.splice(0).forEach((wake) => wake());
    });
  }
  count(type: string): number { return this.messages.filter((m) => m.type === type).length; }
  async next(type: string, timeoutMs = 2000): Promise<Message> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const found = this.messages.find((m) => m.type === type && !this.consumed.has(m));
      if (found) { this.consumed.add(found); return found; }
      if (Date.now() > deadline) throw new Error(`Timed out waiting for ${type}`);
      await new Promise<void>((resolve) => { this.waiters.push(resolve); setTimeout(resolve, 50); });
    }
  }
  async closed(timeoutMs = 2000): Promise<number> {
    const deadline = Date.now() + timeoutMs;
    while (this.closeCode === null) {
      if (Date.now() > deadline) throw new Error("Socket did not close");
      await new Promise<void>((resolve) => { this.waiters.push(resolve); setTimeout(resolve, 50); });
    }
    return this.closeCode;
  }
  send(message: object): void { this.ws.send(JSON.stringify(message)); }
  reply(command: Message, body: object): void {
    this.send({ type: "result", version: 1, session_id: command.session_id, request_id: command.request_id, body });
  }
}
