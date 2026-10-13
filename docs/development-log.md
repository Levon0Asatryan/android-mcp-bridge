# Development log

How Health Bridge was built, what was decided at each step, and why. Newest
decisions sit at the bottom. This is a personal, single-owner project: no
accounts, no hosted service, no analytics, no third-party identity provider.

Contents: [1](#1-first-android-app) · [2](#2-chatgpt-reach) · [3](#3-removing-local-mode) ·
[4](#4-private-mcp--oauth) · [5](#5-oauth-origin-fixes) · [6](#6-workflow-polish) ·
[7](#7-publishing) · [8](#8-expanded-health-tools) · [9](#9-permissions-checklist) ·
[10](#10-known-gaps)

## 1. First Android app

**Goal.** A small personal Kotlin/Compose app that reads, adds and deletes
Health Connect records from a private ChatGPT conversation.

- Explicitly **not** an enterprise or work project. Kept one Android module,
  manual dependencies, no DI framework, no cloud health database.
- Started with nutrition, weight and steps. Tested on a real Galaxy S24
  (Android 16): wrote a meal, read it back (calorie and sodium conversions
  checked), deleted it.
- Security from day one, enforced **inside the app**: per-client tokens stored
  only as Keystore-backed HMAC verifiers, separate read/write/delete grants,
  token expiry, durable idempotency receipts plus Health Connect client record
  IDs, audit log without health payloads, deletes limited to records the app
  wrote itself.
- UI went through several rejected designs (teal/blue, clinical icon, generic
  cards). Final: monochrome grouped surfaces, bottom tabs, black linked-loop
  icon on white. The icon is approved and must be preserved.

## 2. ChatGPT reach

- An early local MCP server was built, then removed: ChatGPT cannot call a
  phone's localhost, and the user wanted ChatGPT on the phone, not a desktop
  client.
- Replaced by ChatGPT custom Actions (REST + OpenAPI) behind HTTPS, first via
  a Mac/USB tunnel (`cloudflared`).
- "No external tools" meant **no Auth0**, not no transport provider.
- Hardening added: 24 h token expiry, secure lock required, health operations
  refused while locked, native biometric/PIN approval for deletes, screenshot
  protection. Writes were later changed to run without a phone prompt (owner
  decision); **deletes still always need biometric approval**.
- Renamed to *Health Bridge*; application ID `dev.androidmcp.bridge` kept so the
  install, data and Health Connect grants survived.

## 3. Removing local mode

The standalone design (see [standalone-backend-plan.md](standalone-backend-plan.md)):

- **Backend**: one Cloudflare Worker plus one SQLite Durable Object, single
  owner, Free plan target. It stores no health data; it only relays.
- **Phone**: outbound WebSocket to the Worker, so no tunnel, USB or Mac is
  needed in normal use. Pairing uses a device key.
- The old loopback server, USB guide and related settings were deleted.
- The backend checks a token fingerprint; the phone **independently** checks
  credential, client grant, lock state, battery saver and Health Connect grant.

## 4. Private MCP + OAuth

The user could not find the GPT "Configure" screen and asked whether MCP could
replace Actions. After checking current OpenAI guidance, the project moved to
a **private remote Streamable HTTP MCP plugin** at `/mcp`.

- Caveats recorded honestly: OpenAI lists full MCP write support as beta for
  some plans, and mobile support is unclear. A Plugins menu on mobile does not
  prove custom tools work there.
- OAuth 2.1 authorization code + PKCE (`S256`) via
  `@cloudflare/workers-oauth-provider`. No public client registration; exact
  callback match. Access tokens ~15 min, rotating refresh tokens, grants max
  90 days. OAuth props are encrypted in KV by the provider (verified in the
  installed code, not assumed).
- Phone approves each link and the chosen scopes with biometric/PIN. Backend
  and phone both enforce scopes; a valid bearer cannot bypass the phone.
- Room schema moved to v4 for linked clients.
- First MCP tool set (10 tools): connection check, status, permissions,
  nutrition read/add/delete, weight read/add/delete, steps read.

## 5. OAuth origin fixes

Symptom: *"Cross-origin request refused."* when approving on the phone.

1. First fix allowed `Origin: https://chatgpt.com` for `/authorize*` and
   `/mcp`. Verified live: `/mcp` initialised; `/authorize/link` with a fake
   handle returned "Request expired", proving the origin check passed.
2. Still a 403. DevTools showed the consent form POST carried
   **`Origin: null`** with `Sec-Fetch-Site: same-origin` (browsers send an
   opaque origin for same-origin form navigations). This matched the user's
   guess that the Worker refused a call from itself.
3. Decision: allow `null` **only** when `Sec-Fetch-Site: same-origin`; never
   allow `null` generally. The provider's transaction cookie, one-use handle
   and content-type checks stay in place. Applied consistently in both
   `index.ts` (`isAllowedOAuthRequest`) and `authorize.ts`.
4. A DevTools paste had included a short-lived transaction cookie, so that
   pending OAuth attempt was abandoned and a fresh one started.

## 6. Workflow polish

- Logo exported as a 128×128 PNG (618 bytes, under the 10 KB limit) and saved
  to Downloads.
- `npm run deploy` in `backend/` replaces the long Wrangler command: it
  loads `.env` and runs `wrangler deploy --var PUBLIC_ORIGIN:… --var
  PAIRING_RESET_EPOCH:…`.

## 7. Publishing

- Public repo on the personal GitHub account, `Levon0Asatryan/health-bridge`.
  `gh auth status` confirmed the personal account. The machine's SSH key
  belonged to an unrelated work account, so git was switched to HTTPS with
  `gh auth setup-git`.
- `.gitignore` covers env files, secrets, keys, build output, `.wrangler`,
  `node_modules`. `backend/.env.example`, an MIT `LICENSE` and a full
  `README.md` were added. Commits use the personal name and email.
- A GitHub OAuth token was accidentally printed once while inspecting `gh`
  config; rotate it (`gh auth refresh` / re-login) if that matters to you.

## 8. Expanded health tools

Requested: water, sleep, workouts, heart rate, active calories, body
measurements, nutrition editing, daily summary, source identification, bulk
nutrition, and a data-freshness report — all with phone support.

New MCP tools (14 added, 24 total including the connection check):
`get_hydration`, `add_hydration`, `get_sleep`, `add_sleep`, `get_workouts`,
`add_workout`, `get_heart_rate`, `get_active_calories`,
`get_body_measurements`, `add_body_measurement`, `update_nutrition`,
`add_nutrition_batch`, `get_daily_summary`, `get_data_freshness`.

Decisions and how each was done:

- **Scopes.** Eleven new ones: `hydration.read|write`, `sleep.read|write`,
  `workouts.read|write`, `heart_rate.read`, `active_calories.read`,
  `body.read|write`, `summary.read`. 19 scopes total, so the OAuth
  registration limit rose from 16 to 32. Operation names in `protocol.ts`,
  `scopes.ts`, `mcp.ts` and `HealthActions.kt` must match exactly.
- **MCP-only.** The new tools are not added to the legacy Actions REST routes
  or `tools/chatgpt-actions.yaml`.
- **No waist circumference.** `androidx.health.connect:connect-client:1.1.0`
  has no waist record type. Body measurements are body fat (%) and lean body
  mass (kg). The tool description says so.
- **`update_nutrition`.** Reads the record, requires it to be owned by this
  app and to have a client record ID, merges the changes, validates, and
  re-inserts with the **same client record ID and version + 1**. Health
  Connect upserts in place, so no duplicate appears. Records from other apps
  (Samsung Health) cannot be edited. Needs `nutrition.write`, no biometric.
  `update_` counts as a mutating operation on both sides.
- **`add_nutrition_batch`.** 1–20 meals. The whole call is rejected if
  idempotency keys repeat inside it. Each meal goes through the same receipt
  path as a single `add_nutrition` (same operation name), so a retry never
  duplicates. Returns one result per meal in order; one bad meal does not
  block the others. Lock, battery saver and expiry are re-checked before each.
- **`get_daily_summary(date)`.** Date is interpreted in the phone's time zone;
  future dates and malformed input are rejected; today ends at "now". Every
  section is gated on the client's own scope **and** the Health Connect
  permission, reporting `ok`, `not_permitted`, `health_permission_not_granted`
  or `error` rather than failing the whole call. Nutrition totals are grouped
  by source app.
- **Source identification.** Every record carries `source_application` (the
  data-origin package). Aggregates (steps, heart rate, calories, hydration)
  list contributing sources, so Samsung Health vs Health Bridge is visible and
  double counting can be spotted.
- **`get_data_freshness`.** Per type, scans the last 30 days (newest first,
  200 records), groups by source app and reports the latest record time and
  last-modified time. Samsung Health's package is `com.sec.android.app.shealth`.
  Types the client may not read are reported as `not_permitted`.
- **Android gating.** Lock and battery-saver checks apply to every health
  operation, including summary and freshness. Health Connect permission
  requirements became "any of a set" (body reads accept fat **or** lean mass).
- **Compatibility.** New `HealthRepository` methods have throwing defaults, so
  existing test fakes still compile.
- **Heart rate** uses aggregates (avg/min/max, plus resting average if that
  permission is granted). **Hydration** returns de-duplicated aggregate total
  plus records. **Workouts** use a fixed exercise-type list shared by backend
  and phone (`strength_training`, `running`, `walking`, `cycling`, `swimming`,
  `yoga`, `pilates`, `hiit`, `hiking`, `rowing`, `elliptical`,
  `stair_climbing`, `stretching`, `dancing`, `calisthenics`, `other`).
- **Delete tools** for the new types are intentionally not included; only
  nutrition and weight can be deleted.

Verification: backend typecheck and 34 tests; Android debug build; Kotlin unit
tests including a new `ExtendedActionsTest` (batch, duplicate keys, update
permission, summary gating, bad dates). Not verified on a real phone or against
real Samsung Health data.

## 9. Permissions checklist

"Update all permissions so no skill is missed" — everything the tools need is
now declared in `AndroidManifest.xml` **and** requested at runtime through
`HealthConnectManager.permissions`:

| Capability | Health Connect permissions |
|---|---|
| Nutrition | `READ_NUTRITION`, `WRITE_NUTRITION` |
| Weight | `READ_WEIGHT`, `WRITE_WEIGHT` |
| Steps | `READ_STEPS` |
| Water | `READ_HYDRATION`, `WRITE_HYDRATION` |
| Sleep | `READ_SLEEP`, `WRITE_SLEEP` |
| Workouts | `READ_EXERCISE`, `WRITE_EXERCISE` |
| Heart rate | `READ_HEART_RATE`, `READ_RESTING_HEART_RATE` |
| Active calories | `READ_ACTIVE_CALORIES_BURNED` |
| Body measurements | `READ_BODY_FAT`, `WRITE_BODY_FAT`, `READ_LEAN_BODY_MASS`, `WRITE_LEAN_BODY_MASS` |
| Older dates | `READ_HEALTH_DATA_HISTORY` |

The history permission lets summaries reach back past the default 30 days
before the app was first granted access.

Phone-side client permissions (`ClientPermission`) mirror the OAuth scopes, so
the Apps screens and link-approval dialog list all of them automatically.

**After updating you must:** rebuild and reinstall the app (`adb install -r`),
open it and grant the new Health Connect permissions, redeploy the backend
(`cd backend && npm run deploy`), then remove and re-add the ChatGPT plugin so
the new scopes are requested and approved on the phone.

## 10. Detail data, modules and approvals

- **Approvals tab** lists the pending ChatGPT link and deletion requests with Approve/Deny. The backend replays pending links to a phone that reconnects.
- **Clock skew fix.** The phone's grant expiry could exceed the worker's maximum by a few seconds, so `link_result` failed as "malformed". The backend now allows 1 hour of slack and reports a specific reason.
- **Removed** manual meal import.
- **Nutrition detail:** 34 optional fields (fat types, cholesterol, 13 minerals, 14 vitamins, caffeine). Units are in the field names. Reads return them under `nutrients`; update merges them with the old record.
- **New tools:** `get/add_oxygen_saturation`, `get_vitals` (HRV, respiratory rate), `get_activity_detail` (distance, floors, elevation, speed, total and basal calories), `get/add_mindfulness`. Body measurements also cover height, bone mass and body water mass. Daily summary and freshness include the new types.
- **New scopes:** `oxygen.read|write`, `vitals.read`, `activity.read`, `mindfulness.read|write`.
- **Modules:** MCP tools are now `health_<tool>`; core tools are `bridge_status`, `bridge_permissions`, `bridge_connection_check`. See `docs/mcp-bridge-plan.md`.
- Not added: blood pressure, glucose, temperature, cycle tracking (no devices or not wanted); stress and waist are not in Health Connect.

- **Rename:** app "Android MCP Bridge", package `dev.androidmcp.bridge`, worker and repo `android-mcp-bridge`, token prefix `amb.`, MCP server name `android-mcp`. Scopes are `health.*`; Android has a `BridgeModule` interface (`HealthActions` is the `health` module). New adaptive launcher icon (bridge arch with a node). New install, so pair again; new worker has new secrets (kept in gitignored `backend/.secrets.local`).

**After updating:** redeploy, reinstall, grant the new Health Connect permissions, tap Start in the gateway panel, then delete and re-create the ChatGPT connector (new tool names and scopes).

## 11. Known gaps

- No waist circumference (not in Health Connect 1.1.0).
- New tools are MCP-only; the legacy Actions API is unchanged.
- No delete for hydration, sleep, workouts or body measurements.
- Samsung Health write visibility is reported as `not_verified`.
- Mobile ChatGPT support for custom MCP tools is unconfirmed.
- Extended reads and writes have not been run against a real phone yet.
