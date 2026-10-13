# Backend: private single-owner instance

Cloudflare Worker + one SQLite-backed Durable Object (`BridgeObject`) using the
WebSocket Hibernation API. TypeScript strict mode. One instance per owner.

- `src/index.ts`: Worker entry; host/Origin/path/size gates; routes to the single object.
- `src/bridge.ts`: pairing, device sessions, token fingerprint registry, rate limits,
  security events, Actions relay, reset.
- `src/auth.ts`: ES256 JWS proof verification with `jose`; RFC 7638 thumbprints.
- `src/protocol.ts`: limits, operation envelope validation, routes, error categories.
- `src/mcp.ts`: stateless Streamable HTTP MCP server (`@modelcontextprotocol/server` via
  `createMcpHandler`); `bridge_connection_check` plus scoped health tools relayed to the phone.
- `src/oauth.ts`, `src/authorize.ts`, `src/scopes.ts`: OAuth 2.1 authorization code + PKCE
  S256 with one predefined confidential client (`@cloudflare/workers-oauth-provider`, KV
  `OAUTH_KV`), phone-approved consent, protected-resource and AS metadata.

```bash
npm ci
npm run typecheck
npm test          # vitest + @cloudflare/vitest-plugin (local workerd, no live services)
```

Stored state: device public JWK and thumbprint, pairing/session generations,
SHA-256 of used bootstrap secrets, client IDs with token fingerprints and expiry,
short-lived challenges/nonces, rate counters, and up to 100 security events
(type, minute, count). No raw tokens, health bodies, IP addresses or record IDs are
stored or logged; observability is disabled in `wrangler.jsonc`. OAuth adds short-lived
link requests (binding hash, scopes, expiry) in the object and provider grant/token
records in `OAUTH_KV` (tokens hashed, grant props encrypted by the provider).

Setup: [../docs/standalone-setup.md](../docs/standalone-setup.md).
Protocol: [../contracts/relay-protocol.md](../contracts/relay-protocol.md).
Private config names are in `.dev.vars.example` (placeholders only).
