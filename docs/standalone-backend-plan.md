# Standalone health bridge: investigation and implementation plan

Status: implemented locally (backend Worker + Durable Object, Android relay,
pairing, Actions adapter) and covered by local tests; not externally verified.
Current behavior, limits and deviations are documented in
[standalone-setup.md](standalone-setup.md) and
[../contracts/relay-protocol.md](../contracts/relay-protocol.md); those take
precedence over this plan. Deviations: authorized adds no longer require a
phone prompt (owner-approved; deletions still do), pagination is deferred
(`result_too_large`), OAuth/MCP are deferred. The release gates below remain
open.
Research date: 2026-10-13. Provider features and limits must be rechecked before
implementation. Examples are generic; this document contains no account,
device, credential, or live-instance identifiers.

## 1. Agreed product requirements

Each backend instance has one owner and one paired Android phone. The repository
may be public, but it does not provide a shared hosted service. Other users run
their own independent instances. There is no signup, account directory,
administrator approval queue, or access to another user's phone.

Normal use needs only the Android bridge, the health apps, ChatGPT on mobile,
and internet access. No computer, USB connection, extra tunnel app, or external
authentication provider is required during normal use.

| Requirement | Decision |
|---|---|
| Backend placement | Same repository, separate `backend/` directory |
| Hosting budget | At most $2/month; target the Cloudflare Free plan |
| Data | Read nutrition/weight/steps; add nutrition/weight |
| Deletion | Only bridge-owned nutrition/weight records |
| Reads | Secure screen lock configured; phone must be unlocked |
| Writes | Prior per-client permission; no per-write phone prompt while unlocked |
| Deletes | Separate permission and fresh biometric/PIN approval |
| Background use | Explicitly enabled, visible foreground notification; pause in power-saving mode |
| Health storage | On phone only; no backend cache or deferred health operations |
| Credentials | Distinct device, health-client, and bootstrap credentials |
| Client lifetime | 90 days for manually configured health-client tokens |
| Alerts | Newly authorized client, new device authentication/session, and suspicious attempts |
| Recovery | Reset pairing through the hosting account; no extra recovery account |
| ChatGPT adapter | Investigate Actions and MCP; mobile read/write is the release gate |

The unlocked-phone write policy intentionally weakens the current prototype's
per-write approval policy. A stolen valid write token can add records while the
phone is unlocked. The owner accepts this tradeoff; do not advertise writes as
requiring biometrics after this change.

## 2. Recommendation

Use a TypeScript Cloudflare Worker and one SQLite-backed Durable Object as a
small authenticated relay. Use outbound WSS from the Android app. Keep the
existing Kotlin Health Connect integration as the health-data authority.

Use app-generated, non-exportable device identity plus one-time owner pairing.
Do not add passwords, email delivery, passkeys, a user database, or Auth0 merely
to support a single owner. Add OAuth only if the selected ChatGPT MCP integration
requires it; a private Actions integration can use a scoped bearer credential.

Do not choose Actions or MCP permanently until an actual mobile capability
test succeeds. Recent OpenAI documentation introduces a significant lifecycle
risk for custom GPTs. Preserve a transport-neutral health command interface so
the adapter can change without rewriting phone authorization or Health Connect.

## 3. What the research establishes

### Health Connect versus Samsung Health SDK

Health Connect is an on-device datastore and permissions API. On Android 14+
it is part of the framework; older supported systems require its separate app.
Samsung Health synchronization must be enabled separately and tested by data
type. A successful Health Connect write does not prove Samsung Health displays
the record. [S1, S2]

Samsung's Health Data SDK accesses Samsung Health's datastore on Android, not
a documented public server-to-server API. The SDK supports nutrition and body
composition, among other types. Production distribution requires partnership
approval; writing requires an access code obtained after approval. Developer
mode is for testing, not a production onboarding requirement. [S3, S4]

**Decision:** retain Health Connect for v1. Do not implement private Samsung
cloud endpoints or request Samsung account passwords. Consider a Samsung SDK
adapter later only if a necessary synchronization capability is missing and
the partnership requirements can be met. Do not bundle proprietary SDK assets
without checking redistribution rights.

### ChatGPT Actions

Actions support API-key and OAuth authentication. A private, single-owner GPT
can use a bearer credential entered in the GPT editor, never in chat or the
OpenAPI document. A shared GPT with one embedded API key would not isolate
users and is explicitly out of scope. [S5]

Actions need public HTTPS with a valid certificate, TLS 1.2+, and port 443.
Documented limits include 45-second request timeouts, text-only payloads below
100,000 characters, and no arbitrary custom request headers. Idempotency keys
therefore belong in JSON arguments, not a required custom header. The
consequential flag can require ChatGPT-side confirmation; it is not a substitute
for phone authorization. Keep it enabled for deletes and initially for writes.
ChatGPT confirmation and phone biometric approval are different controls. [S6]

**Lifecycle warning:** the current OpenAI help page announces custom-GPT
retirement for affected Enterprise workspaces on December 11, 2026 and says
other plans are expected to follow the timeline, subject to account notices.
It recommends migration to plugins. Do not infer a confirmed retirement date
for every personal account, or promise Actions will remain supported. [S7]

### MCP and plugins

OpenAI documents custom MCP servers with read/write tools, streamable HTTP or
SSE, and OAuth/no-auth connection options. Its connection instructions specify
ChatGPT on the web. These instructions do not establish that custom read/write
tools work in the native Android app for every personal account. Secondary
reports are inconsistent; they are not sufficient to promise mobile support.
Test native Android explicitly, including writes, before choosing MCP. [S8, S9]

The HTTP MCP authorization specification requires protected-resource discovery,
authorization-server metadata, and OAuth security measures when implemented.
OpenAI's documented OAuth connector is not the same as pasting an arbitrary
bearer token into an Actions editor. Do not expose unauthenticated health tools
to avoid building OAuth. Use the official MCP SDK rather than a handwritten
JSON-RPC server if MCP is selected. [S8, S10, S11]

OpenAI also now documents Secure MCP Tunnel: an outbound client connects private
MCP servers to supported OpenAI products without public inbound access. It needs
a runtime API key, tunnel identity, account/workspace permissions, and a running
`tunnel-client`. Android support, native-mobile feature availability, and cost
within this project's budget are not established by that documentation. It is
an investigated alternative, not a ready replacement for an Android relay or
a reason to add another required phone app. [S12]

### Public address versus private access

For the Cloudflare relay path, the HTTPS address is reachable from the internet.
Health and management capabilities are private because they require distinct
credentials and grants. An obscure hostname, a private GPT, or an IP address
allowlist is not authentication. No new user can register or claim the instance.

Cloudflare Access login pages cannot simply be placed in front of all Actions
routes: ChatGPT cannot be assumed to complete an interactive login or supply
Access service-token headers. Keep authenticated API ingress compatible with
the selected ChatGPT adapter. Owner-only infrastructure administration remains
protected by the hosting account's authentication and MFA.

## 4. Hosting and cost

Cloudflare Free supports Workers and SQLite-backed Durable Objects. It includes
100,000 Worker requests/day, 100,000 Durable Object requests/day, and 13,000
GB-seconds/day of Durable Object duration. Each dimension is a separate limit;
other applications in the same account can consume account quotas. Exhausting
free limits fails requests, rather than silently creating paid overages. [S13, S14]

Use WebSocket hibernation, not a permanently active standard WebSocket object.
Hibernation keeps the connection while the object is idle; eligible idle time
does not accrue duration. The object can reconstruct non-sensitive session
metadata from WebSocket attachments after waking. [S14, S15]

Approximate duration examples at the documented 128 MB allocation:

| Traffic pattern | Approximate DO duration |
|---|---|
| One non-hibernating object active all day | 10,800 GB-seconds/day |
| 100 requests each active for 30 seconds, no overlap | 375 GB-seconds/day |
| Eligible idle hibernating WebSocket | No idle duration charge |

These are sizing illustrations, not measured bills. Request counts, incoming
message billing, CPU, storage, and account usage also matter. Waiting on a
phone or a delete approval consumes active duration.

The Workers Paid plan starts at $5/month before overages, above the stated
budget. Do not auto-upgrade it. Use a provider-issued hostname initially so no
paid domain is required. Avoid paid databases, email services, cron polling,
and a permanently running VM. Render Free sleeps after 15 minutes without
traffic and takes about a minute to wake; it is a poor fit. [S13, S16]

Free quotas and request limits constrain availability. Neither a $0 relay nor
the phone can offer a 24/7 guarantee. If free quotas are insufficient, stop and
revisit budget or requirements; do not hide a recurring cost.

## 5. Repository and technology choices

```text
app/                         Kotlin/Compose Android application
backend/                     TypeScript Worker and Durable Object
  src/                       Authentication, relay, optional adapters
  test/                      Unit and local runtime tests
  package.json               Pinned dependencies and validation scripts
  wrangler.jsonc             Generic configuration only
contracts/                   Versioned schemas and synthetic fixtures
docs/                        Architecture, security, usage, maintenance
tools/                       Optional development helpers
```

The tree is a target, not a claim that these components exist.

One repository keeps protocol changes, Android code, schemas, and tests in the
same review. Separate build jobs and directories are enough isolation. A
separate repository would add cross-repository version coordination with no
current benefit. Reconsider only if the backend becomes independently maintained.

Use TypeScript strict mode, Wrangler's local Worker runtime, and runtime schema
validation (for example Zod). Use an established JOSE library for signed device
proofs and the official MCP SDK only if MCP is selected. Evaluate library
compatibility and CPU cost on Workers Free before locking versions.

Android needs an outbound WSS client, preferably Ktor client CIO/WebSockets to
match existing networking patterns, plus `ConnectivityManager` integration.
Do not forward relay traffic through the phone's loopback HTTP server. Reuse
the transport-independent `HealthActions`/`HealthRepository` boundary instead.
The Android app supports only the outbound private-instance connection.
The former loopback/USB transport and its settings have been removed.

## 6. Architecture and trust boundaries

```text
ChatGPT mobile
    | Provider executes authenticated tool call
    v
HTTPS Worker: bounded input + client credential check
    |
Single-owner Durable Object: authenticated device session + correlation
    ^                                  |
    | outbound WSS initiated by phone  | command / response
    +----------------------------------+
Android bridge: credential + grants + unlock/power policy + approval
    |
Health Connect <-> Samsung Health synchronization
```

The backend is a transport and credential gate, not a health datastore. The
phone independently authorizes every health operation. Backend authentication
alone must never bypass local permission checks.

Cloudflare terminates TLS and can process request/response plaintext. OpenAI
processes returned health data and may retain it according to account settings.
There is no provider-blind end-to-end encryption in the standard Actions/MCP
flow. Public repository visibility does not change this trust model.

## 7. Identity, pairing, and recovery

### Authentication alternatives

| Option | Benefits | Costs/risks | Decision |
|---|---|---|---|
| Email/password | Familiar login | Password storage, resets, email provider, abuse handling | Reject for one owner |
| Passkeys | Phishing-resistant browser identity | WebAuthn RP, domain association, recovery and browser flows | Useful if browser/OAuth management is needed later |
| External identity service | Managed account system | External service dependency and setup | Not required |
| Long-lived phone bearer secret | Simple transport authentication | Copyable secret can impersonate the phone | Baseline alternative, not preferred |
| Keystore device key + one-time pairing | No account signup; non-exportable identity | Key interoperability and reset handling need tests | Recommended |
| OAuth | Compatible delegated MCP authorization | Authorization server, consent, refresh and redirect protections | Only if selected adapter requires it |

### Owner bootstrap

The instance starts unpaired. An owner supplies a high-entropy bootstrap
credential through private hosting configuration; it must never enter source,
examples, command output, or client health grants. There is no default password
and no unauthenticated "first visitor becomes owner" endpoint.

The app generates an ECDSA P-256 signing key in Android Keystore. Prefer hardware
backing where available; show the actual security level rather than asserting
every device provides StrongBox. TLS verification remains mandatory. [S17]

Pairing is a short-lived, single-use challenge exchange authenticated by the
bootstrap credential. Bind the challenge to the exact instance origin, device
public key, protocol version, and purpose. Complete it only after local
biometric/PIN confirmation. Bound attempts and expire unused challenges.

After pairing, store only the public device key and pairing generation on the
backend. Remove/disable bootstrap authority; it cannot become a health-client
credential. A second public key is rejected, not silently substituted.

### Reconnect authentication

The phone obtains a random, short-lived server challenge and returns a standard
ES256 signed proof bound to the exact audience/origin, challenge, expiry, and
unique proof ID. Consume the nonce atomically before accepting the WSS session.
Store only replay-control metadata. Do not invent encryption or signature
algorithms; JOSE/Keystore signature-format interoperability must be tested.

A successful connection produces a new session generation. Every command and
reply is tied to that generation. An old socket must not complete requests
belonging to its replacement. A challenge endpoint is authentication machinery,
not an unauthenticated management or health API.

Pairing material is entered through a protected input or scanned locally; never
put credentials in query strings, share links, analytics, or notification text.
Bound unpaired challenge creation and replay-state storage so internet requests
cannot exhaust the instance before authentication.

Use normal certificate validation; reject cleartext, userinfo, arbitrary
redirects, and unapproved origins. Endpoint changes require explicit user
confirmation. Do not add brittle certificate pins without a safe rotation
design. Mutual TLS is an alternative, not mandatory; Cloudflare certificate
provisioning and lifecycle would add setup without removing the need for client
health authorization.

### Recovery

If the phone is lost or app data/Keystore keys are cleared, the owner resets
the pairing through the hosting account. Reset increments the pairing
generation, closes existing sessions, revokes client registrations, and starts
a new one-time bootstrap cycle. No automatic trust transfer from a claimed
device name. Reinstalling the app must not revive the old device identity.

## 8. Health-client credentials and local policy

Generate independent 256-bit random tokens in the app. The Actions editor holds
the token; the schema and chat never contain it. Tokens expire 90 days after
creation/rotation, without silently extending earlier credentials.

Keep the current Keystore HMAC verifier on the phone. Register a SHA-256 token
fingerprint, expiry, and client identifier with the backend over the authenticated
device channel. A digest of a uniformly random 256-bit secret is suitable for
lookup/verification; do not use password-style low-entropy tokens. Backend
state must never store the raw token. The raw token arrives transiently with
requests and is forwarded over WSS for independent phone verification.

Backend registration is not the authority for health grants. The phone owns
those grants and checks them on every operation. New registrations remain
inactive until an authenticated acknowledgement succeeds. Revoke locally
immediately; revoke remotely when connected. If an offline backend still accepts
an old token, the phone must reject it before touching health data.

Device credentials cannot invoke health APIs; client credentials cannot pair,
replace a phone, change policy, or enroll another client. An owner bootstrap
credential cannot act as either. Use explicit endpoint separation and tests.

For MCP, issue short-lived audience-bound OAuth access credentials, with rotating
refresh credentials and revocation/reuse detection, instead of pretending that
a 90-day Actions bearer token is an OAuth session. The backend-to-phone grant
binding must include authorized client identity and scopes; phone consent is
still required. OAuth is an additional adapter-specific design gate.

## 9. Connection notifications and what they can prove

The simplest initial alert channel is Android notifications plus an in-app
security activity view. No email address, mail provider, or push provider is
needed. Persist a bounded security-event history so alerts survive disconnects.
This is metadata storage, not queued health operations.

Notify on first successful use of a newly authorized client, credential rotation,
pairing/reset, and successful device authentication. Collapse routine reconnects
into a visible connection/reconnection event with a count, rather than alarming
on every short network interruption. Suspicious failures get a bounded summary.
Offer a verbose session-alert option if desired.

A request using an existing stolen bearer token is not distinguishable from its
legitimate holder with certainty. IP/User-Agent changes are signals, not identity
proof; OpenAI infrastructure can legitimately vary them. Do not claim a "new
person connected" alert, and do not block legitimate traffic solely on IP changes.

Unknown credentials never create accounts or pairing requests. Rate-limit failed
attempts before storage/notification; otherwise anyone could flood alerts.
When the phone is offline or notifications are denied, alerts arrive in-app on
reconnect/open rather than instantly. If immediate offline alerts become a
requirement, separately evaluate email or push and their cost/privacy tradeoffs.

## 10. Relay protocol and operation semantics

Use a versioned command envelope, not arbitrary forwarded URLs:

```json
{
  "version": 1,
  "session_id": "example-session",
  "request_id": "example-request",
  "expires_at": "2030-01-01T12:00:30Z",
  "operation": "get_steps",
  "client_token": "<runtime-only credential>",
  "arguments": {
    "start_time": "2030-01-01T00:00:00Z",
    "end_time": "2030-01-01T12:00:00Z"
  }
}
```

The placeholder is explanatory, never a usable fixture credential. Tests create
their own synthetic keys/tokens.

Validate envelope version, session generation, bounded request ID, deadline,
operation enum, argument schema, and credential on both ends. Do not allow
arbitrary URLs, filesystem actions, code execution, query tunneling, or free-form
tool names. A valid relay session is not authority to perform an operation.

Reject unexpected browser Origins on health/device endpoints. If an OAuth
browser flow is introduced, give it its own exact-origin/redirect allowlists,
CSRF/state protections, PKCE S256, single-use codes, and audience-bound tokens.
Never use wildcard redirect URIs or implement unauthenticated consent.

Responses include correlation ID, structured outcome, and actual phone result.
Discard duplicates, unknown IDs, obsolete sessions, and late responses. Never
attach a late response to a later request.

### Limits and lifecycle

Initial limits, subject to compatibility tests:

- 30-second overall relay deadline within Actions' 45-second timeout.
- 20-second delete approval deadline within the operation deadline.
- Health JSON input at most 64 KiB; serialized output at most 64 KiB and below
  90,000 characters. Enforce both byte and character budgets on Unicode.
- At most four in-flight requests; at most one pending destructive approval.
- Independent health-client, device-authentication, and unauthenticated ingress
  limits; initial authenticated health limit 60 calls/minute.
- Exact operation-specific fields; UTC offsets required; finite measurements.
- No arbitrary response-header forwarding, redirects, CORS wildcard, or caches.

These smaller payload limits avoid the existing prototype's potential mismatch
with Actions' 100,000-character limit. Use explicit pagination/cursors and
summary queries for large reads; never silently truncate. Cursors must be
authenticated, scoped to client/query, expire, and contain no exposed record
data. Follow Health Connect's history restrictions.

Only relay accepted work while a matching phone session is connected. If
unavailable, fail promptly; do not durably queue commands or retry writes.
In-memory work expires when its deadline/session ends.

### Idempotency and uncertain outcomes

Require an idempotency key on every remote insert. Preserve the existing durable
phone receipt/native Health Connect client record ID strategy. Include canonical
operation/payload binding; changed arguments under an old key are a conflict.
Never mint a new key automatically after a timeout.

Use request-ID replay protection separately from insert idempotency. Reconnect
does not replay health commands. A timeout/disconnect may occur after a native
write completed: return an uncertain outcome, not "no change was made." Read
back or retry only with the same idempotency key.

Deletes bind fresh biometric approval to client, record ID, record type, request
and session generation, and deadline. Recheck credential, unlock state, grants,
ownership, and live request immediately before deletion. A reconnect invalidates
approval. If the native call already began, cancellation cannot promise rollback.
Define same-client deletion retry receipts without bypassing fresh approval for
new destructive intent.

Use structured categories such as `unauthorized`, `permission_denied`,
`phone_locked`, `power_saving`, `phone_unavailable`, `approval_required`,
`approval_denied`, `expired_request`, `idempotency_conflict`, `result_too_large`,
`rate_limited`, and `outcome_unknown`. Preserve their meaning across Actions and
MCP adapters. Lock changes during execution must not be mislabeled as missing
Health Connect permission.

## 11. Android availability and health permissions

An explicitly started foreground service maintains the outbound connection.
The notification exposes connected/reconnecting/paused/error and a Stop action.
Do not auto-start at boot, secretly restart after force stop, request permanent
wake locks, or promise survival under every manufacturer policy.

Observe internet changes, screen-lock state, and power saver. On lock/power saver,
stop accepting health operations and invalidate pending approvals; close or mark
the channel paused. On unlock/power-saver exit, reconnect only if the owner had
left bridge access enabled. Recheck state before each native operation even if
connection state says ready. Explicit stop revokes runtime availability.

Recheck lock/power policy before sending health results too. If a lock occurs
after a read, suppress the payload. If a write already completed before a pause,
report only a safe structured outcome or an uncertain outcome; do not claim
rollback or leak a record payload after the policy stopped allowing access.

Reconnect with capped exponential backoff and jitter, reset after a stable
connection, and stop retries on authentication/revocation errors. Network change
may reconnect but never resubmit health commands. Use protocol pings or provider
auto-response mechanisms sparingly; avoid frequent application timers that defeat
Durable Object hibernation.

Android Doze can suspend network even with application intentions. FCM is the
recommended platform mechanism for some background messaging, but is not needed
for v1 because operations require an unlocked phone. Do not misuse high-priority
FCM for silent health access. WorkManager is not a low-latency socket service.
Battery exemptions may help but are not a guaranteed or default requirement.
Foreground-service type eligibility and distribution policy must be reviewed
for the final design; `specialUse` is not permission to evade restrictions. [S18]

**Important gap:** unlocked does not mean our app is foregrounded; ChatGPT can
be the foreground app. Verify foreground-service Health Connect reads on every
supported configuration. Feature-detect background-read capability and request
the dedicated background permission if needed. If unavailable or denied, explain
that the bridge must be opened, rather than bypassing the requirement. Request
historical-read permission only if needed; a 366-day query cap does not grant
366-day history access. [S2]

## 12. Storage and privacy boundaries

| Location | Allowed persistent state | Forbidden persistent state |
|---|---|---|
| Phone | Client grants/verifiers, device key, local audit, retry receipts | Copied health database or raw tokens |
| Backend DO | Public device key, pairing generation, client token digests/expiry, bounded security events and replay/rate-control metadata | Health records, raw client tokens, health command/response bodies |
| Public repository | Source, schemas, synthetic fixtures, generic docs/config templates | Credentials, actual instance addresses/IDs, device identifiers, health exports, local test evidence |

Use one SQLite-backed DO for the small instance; no separate D1/KV/R2 service
initially. Use durable atomic state for pairing, generation changes, registrations
and bounded auth rate controls. Do not rely solely on an in-memory limiter that
resets during hibernation. Keep health payloads out of WebSocket attachments too.

Disable API response caching and payload logging/tracing. Security logs contain
event type, coarse time, safe credential label/fingerprint, outcome, and session
generation, not tokens, query parameters, meal names, weights, record IDs, or
full IP addresses. Bound event retention/count and explain that the hosting
provider can still process its own operational metadata.

Disable phone backups of credentials. Clear transient UI tokens after dismissal.
Keep screenshot protection on sensitive views. A Keystore key cannot make a
compromised running app trustworthy; document rooted-device and compromised
hosting/ChatGPT-account limitations. [S17]

## 13. Threat model and controls

| Threat | Required control | Remaining limit |
|---|---|---|
| Internet scans / guessed credentials | High-entropy tokens, authentication before phone dispatch, bounded rate/payload limits | Public ingress can still consume provider quota |
| Unauthorized phone replacement | One-time owner bootstrap, registered public key, generation-bound sessions | Hosting-account compromise can reset trust |
| Replay of authentication or operations | Single-use nonce, proof expiry/audience, session binding, phone idempotency | Native writes already started cannot be rolled back |
| Stolen ChatGPT token | Narrow grants, 90-day expiry, phone revocation, unlock policy, delete biometric | Authorized reads/writes possible while unlocked |
| Prompt injection / mistaken model output | Strict schemas, least privilege, no arbitrary execution, deletes approved locally | Prior-permission writes still require trust in intent |
| Compromised relay | Independent phone token/grant/lock checks; no stored raw tokens | Relay sees active bearer tokens/payloads and could misuse them; TLS is not E2EE |
| Late approval or socket takeover | Bind request/record/session; reauthenticate before deletion | Cannot undo a completed operation |
| Repository credential leak | Ignore rules, synthetic fixtures, pre-publication scan, no actual addresses | Ignore rules alone do not sanitize existing history |
| Alert flood / resource exhaustion | Authenticate or aggregate events, bound storage, strict ingress/concurrency limits | Free-tier denial of service remains possible |
| Rooted phone / malicious app process | Keystore, release signing, no debug exposure, permission checks | No claim of complete protection on compromised devices |

No health endpoint accepts a caller-supplied user/phone selection. There is only
the paired device in this instance. Never trust record IDs as authorization;
verify origin ownership using Health Connect before deleting.

## 14. Current implementation versus planned changes

The current Android prototype has Health Connect operations, per-client grants,
90-day newly issued credentials, local audit and insert retry receipts, timed
phone approvals, and a loopback Actions server.

It does not have a standalone outbound phone connection, hosted backend,
device pairing, backend token registrations, connection alerts, an OAuth server,
or an MCP adapter. Current writes still require phone approval. Existing
loopback/tunnel instructions are development instructions, not the planned
standalone user experience.

Rework the following rather than building duplicate authorization paths:

| Component | Planned change |
|---|---|
| `GatewayManager` / `GatewayTransport` | Outbound connection lifecycle, explicit pause/error states |
| `GatewayService` | Honest remote connection notification and user-controlled stop |
| `HealthActions` | Prior-permission writes; delete approval retained; precise lock/power errors |
| `ClientManager` | Required remote insert keys; authenticated registration/revocation synchronization |
| `SettingsRepository` | Generic instance URL and local pairing state, no raw credential storage |
| `PhoneApproval` / activity | Session-bound delete approval and timeout/disconnect invalidation |
| UI setup | Pair instance, show ready/locked/power-saving states, connect ChatGPT, security history |
| API contracts | Payload budgets, pagination, structured outcomes, synthetic cross-runtime fixtures |
| App release | Non-debuggable signing workflow, private signing material, compatibility policy |

No change to the accepted launcher icon is required by the backend work.

## 15. Implementation stages and acceptance gates

### Stage A: prove the mobile ChatGPT integration

Review current account-visible capabilities, then test one harmless status/read
and one labeled, consented write using Actions and/or MCP in native ChatGPT
Android. Test delete confirmation separately. Keep any live account or endpoint
details in private local configuration, never documentation.

Acceptance: an actual supported mobile path is established, confirmation and
authentication behavior recorded generically, and custom-GPT lifecycle risk
resolved. If only mobile web works, report the mismatch rather than presenting
it as native mobile support. Do not build a full OAuth service before this gate.

### Stage B: contract and local backend

Create `backend/`, pinned manifests, generic configuration, runtime schemas,
authentication, one-owner DO, hibernatable WSS, input/output budgets, and
structured failures. Test using the local Cloudflare runtime with synthetic
phone and health responses. No health persistence, account signup, or real
external infrastructure changes.

Acceptance: unpaired/unauthorized requests never dispatch; pair/reconnect replay
fails; incompatible versions fail; limits survive hibernation; spoofed/late
responses cannot satisfy active requests; shutdown resolves waiting requests.

### Stage C: Android outbound transport and policy

Add WSS and connectivity monitoring; integrate the existing health handler;
implement pause/unlock/power logic, pairing, grants sync, and prior-permission
writes. Handle local deletion approval and true native results.

Acceptance: no inbound phone listener is needed; lock/power saver denies reads
and writes; disconnect cancels pending deletes; force-stop remains stopped;
unlocked background reads meet Health Connect's actual permission rules.

### Stage D: security events and recovery

Add bounded persisted metadata events, notifications, in-app history, token
first-use alerts, pairing reset and invalidated-session handling.

Acceptance: events survive idle hibernation/reconnect; raw credentials/health
payloads never enter logs/state; denied-notification and offline behavior are
explicit; reset prevents old phone/client sessions from executing.

### Stage E: integration, publication hygiene, and release readiness

Run mobile end-to-end status/read/add/read-back/delete, revoke/rotate/expiry,
Wi-Fi/mobile transitions, lock, power saving, timeout-after-native-write,
process death, permission revocation, Health Connect history/background cases,
and actual Samsung Health visibility.

Test request-ID replay, invalid signatures/nonces/audiences, stolen client token
limits, malformed Unicode and oversize responses, concurrent requests, cursor
scope, free-tier limits, and alert floods. Use synthetic data in CI; no live
health/provider dependency in automated tests.

Publication gate: secrets and machine/device identifiers absent from source,
docs, artifacts and history; no signing keys; no live infrastructure identifiers;
license chosen for original work; third-party notices and SDK licenses reviewed.
Do not infer an open-source license merely from repository visibility.
Ignore rules help future commits but do not prove the workspace or prior history
is clean. Publish only selected source files, never a workspace/build archive.

## 16. Documentation deliverables

The root README remains the entry point and distinguishes current behavior
from planned behavior. Before the standalone release, add public-safe guides
for:

- Architecture and protocol, with versioning and trust boundaries.
- App usage, least-privilege grants, pairing, ChatGPT configuration and alerts.
- Security/privacy, credential lifetime, recovery and compromised-account limits.
- Health Connect/Samsung synchronization and unsupported record operations.
- Generic backend configuration and local development, without actual instance
  URLs, hosting account IDs, credentials or published infrastructure details.
- Testing, release signing, dependency maintenance and incident recovery.

No actual hosting setup, paid service activation, repository publication, or
external account configuration is part of this investigation task.

## 17. Decisions still gated by evidence

These are not unresolved product preferences; they are implementation checks:

1. Native ChatGPT mobile support for the chosen authenticated read/write adapter.
2. Custom-GPT migration/lifetime for the selected account capability.
3. OAuth/browser identity requirement if MCP is selected.
4. Health Connect foreground-service/background-read eligibility by device/API.
5. ES256 Keystore-to-Worker interoperability within Free CPU limits.
6. Actual idle duration, reconnect behavior, battery cost and free-tier usage.
7. Samsung Health display/synchronization for each supported data type.

Proceed with the single-owner relay design, but do not claim the product works
until these gates pass. If native ChatGPT supports neither path, changing the
backend cannot fix that client limitation.

## Sources

Official sources accessed for this investigation:

- S1: [Health Connect setup](https://developer.android.com/health-and-fitness/health-connect/get-started)
- S2: [Health Connect reads, background and history](https://developer.android.com/health-and-fitness/health-connect/read-data)
- S3: [Samsung Health Data SDK overview](https://developer.samsung.com/health/data/overview.html)
- S4: [Samsung app creation and partnership](https://developer.samsung.com/health/data/process.html)
- S5: [GPT Actions authentication](https://developers.openai.com/api/docs/actions/authentication)
- S6: [GPT Actions production limits](https://developers.openai.com/api/docs/actions/production)
- S7: [Creating/editing GPTs and retirement notice](https://help.openai.com/en/articles/8554397-creating-and-editing-gpts)
- S8: [Add a custom MCP server](https://developers.openai.com/api/docs/guides/custom-mcp-server)
- S9: [Connect/test a plugin](https://developers.openai.com/plugins/deploy/connect-chatgpt)
- S10: [MCP authorization specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)
- S11: [Build an MCP server using official SDKs](https://developers.openai.com/plugins/build/mcp-server)
- S12: [Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
- S13: [Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
- S14: [Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)
- S15: [Durable Object WebSocket hibernation](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
- S16: [Render Free limitations](https://render.com/docs/free)
- S17: [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- S18: [Android Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)
- S19: [Android Credential Manager / passkeys](https://developer.android.com/identity/credential-manager)

S19 supports the passkey comparison; passkeys remain optional, not required by
the recommended single-owner pairing design.
