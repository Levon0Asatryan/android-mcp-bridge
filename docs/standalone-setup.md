# Standalone setup (private single-owner instance)

This is the supported path. The phone connects **outward** to your own instance
over WSS. No computer, USB connection or tunnel is needed after setup. Each owner
runs an independent instance; there is no shared service or signup.

```text
ChatGPT custom GPT Action --HTTPS + client token--> your Worker
  --> one SQLite Durable Object (pairing, token fingerprints, relay)
  <==WSS, device-key authenticated== phone foreground service --> Health Connect
```

## 1. Configure and deploy the instance

Requirements: Node.js 20+, a Cloudflare account you control. Deployment and
account setup are your own steps; nothing in this repository contains account
identifiers or live endpoints.

```bash
cd backend
npm ci
npm run typecheck && npm test        # local only, no live services
```

Private configuration (never commit real values):

| Name | Kind | Value |
|---|---|---|
| `OWNER_BOOTSTRAP_TOKEN` | secret (`wrangler secret put OWNER_BOOTSTRAP_TOKEN`) | Fresh random string, at least 32 characters (43+ recommended). Single use. |
| `PUBLIC_ORIGIN` | var | Exact HTTPS origin of the deployed Worker, e.g. `https://<your-worker-host>`; no path or trailing slash. |
| `PAIRING_RESET_EPOCH` | var | `0`. Raise to reset pairing (see below). |
| `RELAY_DEADLINE_MS` | var, optional | Defaults to 30000; clamped to 100-30000. |
| `OAUTH_CLIENT_ID` | secret | OAuth client ID you choose (8-128 of `A-Za-z0-9._~-`). Entered in ChatGPT. |
| `OAUTH_CLIENT_SECRET` | secret | Fresh random string, 32+ characters. Entered in ChatGPT; stored on the instance only as a hash. |
| `OAUTH_REDIRECT_URI` | secret | Exact callback URL ChatGPT shows for the connection (`https://chatgpt.com/connector/oauth/<id>`), or the stable `https://chatgpt.com/connector_platform_oauth_redirect`. No wildcards. |
| `OAUTH_KV` | KV binding | `wrangler kv namespace create OAUTH_KV`, then put its `id` in `wrangler.jsonc` (OAuth grants and hashed tokens only; no health data). |

`wrangler.jsonc` holds only a placeholder origin. Set your real origin through
your own private deployment configuration (for example `wrangler deploy --var
PUBLIC_ORIGIN:https://<your-worker-host>` or a private, ignored config copy).
For local runs, copy `.dev.vars.example` to `.dev.vars`.

Requests whose `Host` differs from `PUBLIC_ORIGIN` get `421`. Routes: `/mcp`
(bearer only), OAuth (`/.well-known/oauth-protected-resource[/mcp]`,
`/.well-known/oauth-authorization-server`, `/authorize*`, `/oauth/token`),
`/v1/*` and `/device/*`. Browser `Origin` headers are accepted only same-origin on
`/authorize*` and ignored on bearer-only `/mcp`; elsewhere they get `403`.

## 2. Pair the phone

1. The phone must have a PIN, password or pattern screen lock.
2. Settings > Instance pairing. Enter the instance origin and the bootstrap
   secret. The secret field is a password field held only in memory; it is not
   saved, logged or put in the database. Its mutable request buffer is zeroed
   after use; transient JVM strings cannot be reliably erased.
3. Confirm with biometric or device credential.
4. The app creates a non-exportable P-256 key in Android Keystore (StrongBox
   when available; the screen shows the actual security level), signs the
   pairing challenge (ES256 JWS), and stores only the origin, pairing
   generation and the public key thumbprint.

The instance accepts exactly one device. A second pairing attempt is rejected
and reported as a security event.

## 3. Start access (and optional legacy token)

1. Optional, legacy Actions only: Apps > add an application with only the grants
   it needs and copy the token (shown once, 90-day expiry). The MCP plugin does
   not need this.
2. Home > Start health access. The foreground service connects, then registers
   the SHA-256 fingerprints and expiries of all active tokens. Creating,
   rotating, disabling, revoking or deleting a client re-registers immediately.
3. Tokens created before this version have no fingerprint and work only
   locally. Regenerate them to use them through the instance.

## 4. Add the private MCP plugin in ChatGPT

1. ChatGPT on the web: Plugins (`https://chatgpt.com/plugins`) > **+** > Add
   custom MCP server. URL: `https://<your-worker-host>/mcp`. Auth: OAuth, with
   the `OAUTH_CLIENT_ID` and `OAUTH_CLIENT_SECRET` values. If ChatGPT shows a
   per-connection callback URL, set `OAUTH_REDIRECT_URI` to it exactly and
   redeploy before connecting.
2. ChatGPT opens the instance's authorize page. It shows no account data. Press
   **Send approval request to my phone** (the phone must be running health
   access). The browser shows a 6-digit code.
3. On the phone, open the notification. Approve only if the code matches,
   select permissions and confirm with biometric/PIN. The request expires
   after 2 minutes; at most one is pending and a few per hour are accepted.
4. Acceptance order: run `bridge_connection_check` (no data) first, then
   `get_bridge_status`, then a small read, then a labeled test meal add/read/delete.

Access tokens last 15 minutes and refresh tokens rotate; the link itself ends
after 90 days (reconnect to renew). Revoke under Apps on the phone: the phone
refuses immediately and the instance revokes the OAuth grant including refresh.
Scopes are checked on the instance and again on the phone for every call.

Caveats: plugin and custom-MCP availability depend on your ChatGPT plan and
OpenAI may change it. A plugin appearing in the mobile app is not proof that
custom MCP calls work there; verify on the web first. The legacy Actions adapter
(`tools/chatgpt-actions.yaml`) remains only for transition.

## Runtime behavior

- **Adds** run without a phone prompt when the client is authorized, the phone
  is unlocked and battery saver is off. `idempotency_key` is required; the
  native Health Connect client record ID is derived from it, so retries are safe.
  If the outcome is unknown (timeout, disconnect, native failure) the API says
  `outcome_unknown`; retry with the same key and arguments.
- **Deletions** of Android MCP Bridge-owned records need biometric/PIN on the phone
  within 20 seconds (and before the request deadline). Only one may wait.
  Disconnect, cancel, lock, power saver or deadline withdraws a pending prompt.
  If deletion already started, cancellation cannot promise rollback; a lost
  response is reported as `outcome_unknown`.
- **Locked phone:** the connection stays up so requests get an honest
  `phone_locked`. If the phone locks during a read, the data is not returned.
- **Battery saver or no validated network:** the connection pauses; requests get
  `503 phone_unavailable`. It resumes automatically.
- **Reconnects:** exponential backoff with jitter, 1 s up to 5 min. A reconnect
  displaces the old session and resolves its in-flight requests.
- **Stop:** Home or the notification's Stop action closes the connection.
- **Alerts:** Settings > Security alerts lists instance and phone events;
  notable ones (first use of a token, rejected credentials, challenge floods,
  rejected device replacement, pairing reset, authenticated sessions) also notify.
  No email or push service is used; alerts arrive while connected.

## Reset and recovery

- Lost phone, new phone or broken pairing: in your private config, set a **new**
  `OWNER_BOOTSTRAP_TOKEN` and raise `PAIRING_RESET_EPOCH`. On the next request the
  instance forgets the device and client registrations, increments the pairing
  generation and closes the socket with `4003`. Used bootstrap secrets stay
  rejected. On the phone: Settings > Instance pairing > Forget pairing, then pair.
- If pairing completed on the instance but the phone never received the reply,
  the instance is paired with a key the phone did not record; use the reset above.
- "Forget pairing" on the phone deletes the device key and local pairing state
  only; the instance keeps rejecting other devices until reset.
- A pairing reset also revokes every OAuth grant (including refresh tokens) and
  pending phone approvals; reconnect the plugin afterwards. Changing
  `OAUTH_CLIENT_ID` replaces the registered OAuth client.
