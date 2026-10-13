# Android MCP Bridge: generalization plan

Status: **phases 1-3 done**. Scope prefixes (`health.*`), the `BridgeModule` interface, the rename and the new logo are implemented. Module-prefixed wire operations and per-module backend files remain for later.

## Names (all applied)

| Thing | Now | Target |
| --- | --- | --- |
| Android app | Health Bridge | **Android MCP Bridge** (done) |
| ChatGPT connector / MCP server name | Health Bridge | **Android MCP** |
| Repository | `health-bridge` | `android-mcp-bridge` |
| Worker | `health-bridge` | `android-mcp-bridge` |
| Package id | `dev.androidmcp.bridge` | `dev.androidmcp.bridge` |
| Token prefix | `hb.` | `amb.` |

## Modules

Health becomes one module among others. A module owns tools, OAuth scopes, phone permissions and a phone-side action class.

| Module | Tools | Status |
| --- | --- | --- |
| `core` | `bridge_status`, `bridge_permissions`, `bridge_connection_check` | done |
| `health` | `health_get_nutrition`, `health_add_nutrition`, ... | done (tool names) |
| future | `notifications_*`, `calendar_*`, `location_*`, `files_*`, `contacts_*` | not started |

Done now: backend `MODULES` in `mcp.ts`; every health tool is exposed as `health_<tool>`.

Still to do for full modularity:
1. Scope names get the module prefix: `health.nutrition.read` (now `nutrition.read`). `ClientPermission.scope`, `scopes.ts` and tests change together.
2. Backend: one file per module (`modules/health.ts`) exporting its tools, operations, validators and `SCOPE_FOR` entries; `mcp.ts`, `protocol.ts` and `scopes.ts` assemble them from a registry.
3. Android: a `BridgeModule` interface (`id`, `operations`, `permissions`, `perform`). `HealthActions` becomes the `health` implementation; the relay dispatches by operation prefix.
4. UI: Apps/Approvals/Home group permissions by module, with a per-module on/off switch.
5. Operation names get the same prefix on the wire (`health.get_nutrition`); bump `PROTOCOL_VERSION`.

## Other MCP clients

The `/mcp` endpoint is plain OAuth 2.1 + PKCE Streamable HTTP, so Claude, Cursor and similar clients can use it as is. The legacy Actions REST adapter stays ChatGPT-only and can be removed once the connector is the only path.

## Rename impact (breaking)

- New package id means a new app install: Health Connect permissions, pairing and all client grants are lost. Pair again.
- New Worker name means a new `workers.dev` URL (or keep the old route). Update `PUBLIC_ORIGIN`, re-pair the phone.
- New token prefix invalidates existing tokens (`TOKEN_PATTERN`).
- ChatGPT: new scopes, tool names and server name need a **new connector** (ChatGPT caches tool and scope lists).
- Repo rename on GitHub redirects the old URL.

## Phases

1. **Now (done):** module-prefixed tool names; new health data types.
2. **Refactor, no rename:** scope prefixes, per-module backend files, `BridgeModule` on Android, per-module UI switches.
3. **Rename:** package id, Worker, repo, token prefix, app label and icon, docs. One re-pair, one new connector.
4. **New modules:** start with notifications (read) and calendar (read), each behind its own scope and approval.

Order matters: refactor before rename, so the break (re-pair, new connector) happens once.
