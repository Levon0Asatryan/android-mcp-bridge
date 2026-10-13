# Relay protocol v1 (as implemented)

Contract between the Android app (`app/src/main/java/dev/healthbridge/relay/`) and
the private instance (`backend/src/`). Protocol version is `1` everywhere.
Health arguments and command envelopes use exact key sets; unknown fields are
rejected. Control messages are validated for their required fields and types.

## Device pairing (once)

| Step | Request | Auth | Response |
|---|---|---|---|
| 1 | `POST /device/pair/challenge` | `Bearer <owner bootstrap>` | `{challenge, purpose:"pair", audience:<PUBLIC_ORIGIN>, protocol_version, expires_at}` |
| 2 | `POST /device/pair/complete` body `{proof}` | `Bearer <owner bootstrap>` | `{paired:true, device_kid, pairing_generation, protocol_version}` |

- Challenge: 256-bit random, 5-minute TTL, at most 4 open.
- `proof` is a compact JWS, `alg: ES256`, header `typ: "hb-pair+jwt"` with the
  public P-256 `jwk` embedded. Claims: `{v:1, purpose:"pair", aud:<PUBLIC_ORIGIN>,
  nonce:<challenge>, iat, exp, jti}` with `exp - iat <= 300` s, 60 s clock skew.
- Completion is one SQLite transaction: rejects when a device is already paired
  (`409 already_paired`, and a `device_replacement_rejected` event); consumes the
  challenge exactly once; pins the public JWK and its RFC 7638 thumbprint
  (`device_kid`); records the bootstrap secret's SHA-256 as used. After this,
  the same bootstrap secret returns `401`. The bootstrap secret never grants
  health access.
- The phone checks `audience` equals the origin it typed and that
  `device_kid` equals its own key thumbprint.

## Session (each connect / reconnect)

1. `POST /device/challenge` (no auth; 120/min unauthenticated limit) returns
   `{nonce, purpose:"connect", audience:<PUBLIC_ORIGIN>/device/connect,
   pairing_generation, protocol_version, expires_at}`. 60-second TTL, at most 8
   open nonces (`429` plus `challenge_flood` event beyond that). `409 not_paired`
   if there is no device.
2. `GET /device/connect` with `Upgrade: websocket` and
   `Authorization: Bearer <JWS>`. Header `typ: "hb-connect+jwt"`,
   `kid: <device_kid>`, no embedded key. Claims add `purpose:"connect"`,
   `aud:<PUBLIC_ORIGIN>/device/connect`, `pairing_generation`. The nonce is
   consumed once; replay, foreign key, wrong audience or stale generation give `401`.
3. Accepting a connection increments `session_generation`, issues a fresh
   `session_id`, closes any older socket with code `4001`, and settles all
   in-flight requests of the old session (`outcome_unknown` for writes and deletes).
4. The instance sends `hello {session_id, session_generation, pairing_generation, limits}`.

Close codes: `4001` session replaced, `4003` pairing reset by the operator.
A text frame `ping` is answered `pong` by the runtime without waking the object.

## Messages

Phone to instance:

- `register_clients {request_id, clients:[{client_id, fingerprint, expires_at}]}`:
  full-set atomic replacement (max 20). `fingerprint` is lowercase hex
  SHA-256 of the raw client token; `expires_at` is epoch milliseconds. Sent after
  every connect and after each create, rotate, revoke, disable or delete.
  Answer: `ack {request_id, ok, registered}`.
  Duplicate client IDs or fingerprints are rejected. The phone reports
  Connected only after its initial registration receives a correlated success
  acknowledgement.
- `result {session_id, request_id, body}`: body at most 64 KiB and 90,000
  characters. Late, duplicate, unknown or wrong-session results are discarded.
- `register_clients` entries for OAuth-linked clients add
  `oauth {client_id, scopes[], pairing_generation, grant_version}`. A linked
  client missing from the set (revoked, disabled, deleted, expired) or whose
  binding changed has its OAuth grant revoked, including refresh tokens.
- `link_result {link_id, binding, approved:false}` or
  `{link_id, binding, approved:true, scopes[], client_id, grant_secret, fingerprint, expires_at, grant_version, pairing_generation}`.
  Sent only after biometric/PIN approval. `scopes` must be a subset of the
  request, `fingerprint` = SHA-256 of `grant_secret`, expiry at most 90 days,
  generation current. The instance stores only the fingerprint; the secret lives
  only inside the provider's encrypted grant props. Answer: `link_ack {link_id, ok, error?}`;
  on `ok:false` the phone deletes the grant it created.
- `events_ack {up_to}`.
  Acknowledgements cannot exceed the highest actual event sequence. The phone
  persists a per-instance sequence cursor with each event to avoid replaying
  notifications after reconnect.

Instance to phone:

- `command {session_id, request_id, expires_at (ISO-8601), operation, client_token, arguments}`.
  The phone validates every field, re-authenticates the token against its own
  verifier, then applies client grants, lock, battery saver and Health Connect checks.
  Deadlines cannot be more than 30 seconds in the future, arguments are capped
  at 64 KiB independently on the phone, and recent duplicate IDs are discarded.
  The phone rechecks deadline/cancellation and device policy after suspending
  permission/ownership checks and immediately before native health calls.
- MCP calls add `auth {kind:"oauth", oauth_client_id, client_id, grant_version, pairing_generation, scopes[]}`
  to `command`; `client_token` is the grant secret. The phone requires every
  field to match its own linked-client record and current pairing generation, and
  the operation scope in both the token scopes and its record. Token clients
  must not carry `auth`. The grant secret is rejected on `/v1/*`.
- `link_request {link_id, code, binding, oauth_client_id, redirect_host, scopes[], expires_at}`
  after the browser asks for approval (at most 1 pending, 6 per hour, 120 s TTL).
  `binding` = SHA-256 over link ID, client, redirect, resource, scopes, PKCE
  challenge and state; the phone echoes it unchanged. The phone shows the code,
  never auto-approves, and denies on timeout.
- `cancel {session_id, request_id}` when the deadline passes. The phone cancels
  the job, which withdraws a pending deletion prompt.
- `security_events {events:[{seq, at, type, detail, count}]}`. Types:
  `device_connected` (detail `replaced_previous` when it displaced a session),
  `client_first_use`, `auth_failures`, `challenge_flood`,
  `device_replacement_rejected`, `device_paired`, `pairing_reset`, `client_linked` (detail `oauth`). Times are minute-rounded,
  counts collapse repeats, at most 100 retained. No IP addresses, tokens, or
  health record identifiers.
- `error {error: malformed | unknown_type}`.

## Actions adapter (`/v1/*`)

`GET /v1/status`, `GET /v1/permissions`, `GET /v1/{nutrition|weight|steps}?start_time&end_time`,
`POST /v1/{nutrition|weight}`, `DELETE /v1/{nutrition|weight}/{record_id}`.

Order of gates before any command reaches the phone: host and Origin checks,
auth-failure rate limit (30/min), token format, fingerprint lookup with expiry
(`401` otherwise, logged as `auth_failures` without the token), per-client
limit (60/min), argument validation (64 KiB input, exact keys, `idempotency_key`
required for adds), live session (`503 phone_unavailable`), at most 4 in flight
(`429`), at most 1 pending deletion (`409 approval_pending`).

Deadline is 30 s (deletion approval at most 20 s on the phone). A timeout or
disconnect returns `504 outcome_unknown` for adds and deletes (retry adds with
the same `idempotency_key`), `504 expired_request` or `503 phone_unavailable` for reads.
Bodies above the output budget return `422 result_too_large`; there is no
pagination yet, so request shorter date ranges.
Receiving request bodies is limited to 10 seconds. Oversized Unicode device
frames are measured by UTF-8 bytes, not character count.

Status mapping: `unauthorized` 401; `permission_denied`, `health_permission_denied`,
`phone_locked`, `power_saving`, `approval_required`, `approval_denied` 403;
`approval_pending`, `idempotency_conflict`, `already_paired`, `not_paired` 409;
`invalid_input` 400; `not_found` 404; `result_too_large` 422; `rate_limited` 429;
`phone_unavailable`, `health_unavailable`, `not_configured` 503;
`expired_request`, `outcome_unknown` 504; `protocol_error` 502.

## MCP and OAuth (`/mcp`)

Stateless Streamable HTTP (POST JSON only, 64 KiB body). Without a bearer token
only `initialize`, `tools/list` and `bridge_connection_check` (returns
`{ok, protocol}`) work; health tools return an auth error with a
`WWW-Authenticate` challenge. A present but invalid bearer gets `401`. Access
tokens must have this instance's `/mcp` resource as audience, the predefined
client ID and the tool's scope. Scopes: `bridge.status`, `nutrition.read|write|delete`,
`weight.read|write|delete`, `steps.read`, `hydration.read|write`, `sleep.read|write`,
`workouts.read|write`, `heart_rate.read`, `active_calories.read`, `body.read|write`,
`summary.read`. Health calls then follow the same
gates and outcomes as the Actions adapter.

Extended operations (MCP only, not exposed by the Actions adapter):
`update_nutrition {record_id, changes}`, `add_nutrition_batch {entries[1..20]}`,
`get_hydration|get_sleep|get_workouts|get_heart_rate|get_active_calories|get_body_measurements {start_time, end_time}`,
`add_hydration`, `add_sleep`, `add_workout`, `add_body_measurement`,
`get_daily_summary {date}` and `get_data_freshness {}` (both need `summary.read`;
each section is additionally gated by its own scope and Health Connect grant).
`add_*` and `update_*` are mutating; only `delete_*` needs phone approval.
Design notes are in [../docs/development-log.md](../docs/development-log.md).
