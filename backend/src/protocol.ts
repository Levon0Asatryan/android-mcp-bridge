// Shared relay contract. Keep in sync with contracts/relay-protocol.md and the Android RelayProtocol.kt.
export const PROTOCOL_VERSION = 1;

export const LIMITS = {
  inputBytes: 65_536,
  outputBytes: 65_536,
  outputChars: 90_000,
  relayDeadlineMs: 30_000,
  deleteApprovalMs: 20_000,
  inFlight: 4,
  pendingDeletes: 1,
  clientCallsPerMinute: 60,
  authFailuresPerMinute: 30,
  unauthenticatedPerMinute: 120,
  maxClients: 20,
  maxEvents: 100,
  pairChallenges: 4,
  pairChallengeTtlMs: 300_000,
  connectNonces: 8,
  connectNonceTtlMs: 60_000,
  proofLifetimeS: 300,
  clockSkewS: 60,
  deviceMessageBytes: 131_072,
} as const;

export const OPERATIONS = [
  "get_gateway_status", "get_permissions", "get_nutrition", "get_weight", "get_steps",
  "add_nutrition", "add_weight", "delete_nutrition", "delete_weight",
  "update_nutrition", "add_nutrition_batch", "get_hydration", "add_hydration", "get_sleep", "add_sleep",
  "get_workouts", "add_workout", "get_heart_rate", "get_active_calories", "get_body_measurements",
  "add_body_measurement", "get_daily_summary", "get_data_freshness",
] as const;
export type Operation = (typeof OPERATIONS)[number];

export const isWrite = (op: Operation) => op.startsWith("add_") || op.startsWith("update_");
export const isDelete = (op: Operation) => op.startsWith("delete_");

export type ErrorCategory =
  | "unauthorized" | "permission_denied" | "health_permission_denied" | "phone_locked" | "power_saving"
  | "phone_unavailable" | "approval_required" | "approval_denied" | "approval_pending" | "expired_request"
  | "idempotency_conflict" | "result_too_large" | "rate_limited" | "outcome_unknown" | "invalid_input"
  | "health_unavailable" | "not_found" | "protocol_error" | "not_configured" | "already_paired" | "not_paired";

export const STATUS: Record<ErrorCategory, number> = {
  unauthorized: 401, permission_denied: 403, health_permission_denied: 403, phone_locked: 403,
  power_saving: 403, approval_required: 403, approval_denied: 403, phone_unavailable: 503,
  approval_pending: 409, expired_request: 504, idempotency_conflict: 409, result_too_large: 422,
  rate_limited: 429, outcome_unknown: 504, invalid_input: 400, health_unavailable: 503,
  not_found: 404, protocol_error: 502, not_configured: 503, already_paired: 409, not_paired: 409,
};

export function statusFor(category: unknown): number {
  return typeof category === "string" && category in STATUS ? STATUS[category as ErrorCategory] : 502;
}

export function failure(category: ErrorCategory, message: string): Record<string, unknown> {
  return { success: false, error_category: category, message };
}

/** Byte and UTF-16 character budget check for a serialized JSON value. */
export function withinOutputBudget(serialized: string): boolean {
  return serialized.length <= LIMITS.outputChars && new TextEncoder().encode(serialized).byteLength <= LIMITS.outputBytes;
}

export const TOKEN_PATTERN = /^hb\.[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/;
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/;
const MEAL_TYPES = new Set(["breakfast", "lunch", "dinner", "snack", "unknown"]);
export const EXERCISE_TYPES = [
  "strength_training", "running", "walking", "cycling", "swimming", "yoga", "pilates", "hiit", "hiking", "rowing",
  "elliptical", "stair_climbing", "stretching", "dancing", "calisthenics", "other",
] as const;
export const MAX_BATCH = 20;
const NUTRITION_FIELDS = ["meal_name", "meal_type", "timestamp", "calories_kcal", "protein_g", "carbs_g", "fat_g", "sugar_g", "fiber_g", "sodium_mg"];
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

type Json = Record<string, unknown>;
const isObject = (value: unknown): value is Json =>
  typeof value === "object" && value !== null && !Array.isArray(value);

function timestamp(value: unknown): boolean {
  return typeof value === "string" && value.length <= 64 && TIMESTAMP.test(value) && !Number.isNaN(Date.parse(value));
}
function measure(value: unknown, min: number, max: number, exclusiveMin = false): boolean {
  return typeof value === "number" && Number.isFinite(value) && (exclusiveMin ? value > min : value >= min) && value <= max;
}
function text(value: unknown, max: number): boolean {
  return typeof value === "string" && value.trim().length > 0 && value.length <= max;
}
function exactKeys(args: Json, required: string[], optional: string[] = []): boolean {
  const allowed = new Set([...required, ...optional]);
  return required.every((key) => key in args) && Object.keys(args).every((key) => allowed.has(key));
}


/** Fields shared by add_nutrition and update_nutrition changes; only present fields are checked. */
function validateNutritionFields(args: Json): string | null {
  if ("meal_name" in args && !text(args.meal_name, 200)) return "Meal name must contain 1-200 characters.";
  if ("meal_type" in args && (typeof args.meal_type !== "string" || !MEAL_TYPES.has(args.meal_type))) return "Unsupported meal type.";
  if ("timestamp" in args && !timestamp(args.timestamp)) return "Use an ISO 8601 timestamp with an explicit UTC offset.";
  for (const key of ["calories_kcal", "protein_g", "carbs_g", "fat_g"]) {
    if (key in args && !measure(args[key], 0, 100_000)) return `${key} must be a finite number from 0 to 100000.`;
  }
  for (const key of ["sugar_g", "fiber_g", "sodium_mg"]) {
    if (args[key] !== undefined && args[key] !== null && !measure(args[key], 0, 100_000)) return `${key} must be 0-100000.`;
  }
  return null;
}

function validateNutritionEntry(args: Json): string | null {
  const required = ["meal_name", "meal_type", "timestamp", "calories_kcal", "protein_g", "carbs_g", "fat_g", "idempotency_key"];
  if (!exactKeys(args, required, ["sugar_g", "fiber_g", "sodium_mg"])) return "Unexpected or missing nutrition fields.";
  const invalid = validateNutritionFields(args);
  if (invalid) return invalid;
  return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
}

function validateSpan(args: Json): string | null {
  if (!timestamp(args.start_time) || !timestamp(args.end_time)) return "Use ISO 8601 timestamps with an explicit UTC offset.";
  const start = Date.parse(args.start_time as string);
  const end = Date.parse(args.end_time as string);
  if (start >= end) return "start_time must precede end_time.";
  return end - start > 86_400_000 ? "A session can last at most 24 hours." : null;
}

/** Backend pre-validation. The phone revalidates every field before touching Health Connect. */
export function validateArguments(op: Operation, args: unknown): string | null {
  if (!isObject(args)) return "Arguments must be a JSON object.";
  switch (op) {
    case "get_gateway_status":
    case "get_permissions":
    case "get_data_freshness":
      return Object.keys(args).length === 0 ? null : "No arguments are accepted.";
    case "get_nutrition":
    case "get_weight":
    case "get_steps":
    case "get_hydration":
    case "get_sleep":
    case "get_workouts":
    case "get_heart_rate":
    case "get_active_calories":
    case "get_body_measurements": {
      if (!exactKeys(args, ["start_time", "end_time"])) return "Provide exactly start_time and end_time.";
      if (!timestamp(args.start_time) || !timestamp(args.end_time)) return "Use ISO 8601 timestamps with an explicit UTC offset.";
      const start = Date.parse(args.start_time as string);
      const end = Date.parse(args.end_time as string);
      if (start >= end) return "start_time must precede end_time.";
      if (end - start > 366 * 86_400_000) return "Query at most 366 days at a time.";
      return null;
    }
    case "add_nutrition":
      return validateNutritionEntry(args);
    case "add_nutrition_batch": {
      if (!exactKeys(args, ["entries"])) return "Provide exactly entries.";
      const entries = args.entries;
      if (!Array.isArray(entries) || entries.length < 1 || entries.length > MAX_BATCH) return `entries must contain 1-${MAX_BATCH} meals.`;
      const keys = new Set<unknown>();
      for (const [index, entry] of entries.entries()) {
        if (!isObject(entry)) return `Entry ${index + 1} must be an object.`;
        const invalid = validateNutritionEntry(entry);
        if (invalid) return `Entry ${index + 1}: ${invalid}`;
        if (keys.has(entry.idempotency_key)) return "Each entry needs its own idempotency_key.";
        keys.add(entry.idempotency_key);
      }
      return null;
    }
    case "update_nutrition": {
      if (!exactKeys(args, ["record_id", "changes"])) return "Provide exactly record_id and changes.";
      if (!text(args.record_id, 200)) return "record_id must contain 1-200 characters.";
      const changes = args.changes;
      if (!isObject(changes) || Object.keys(changes).length === 0) return "changes must be an object with at least one field.";
      if (!Object.keys(changes).every((key) => NUTRITION_FIELDS.includes(key))) return "changes contains an unsupported field.";
      return validateNutritionFields(changes);
    }
    case "add_hydration": {
      if (!exactKeys(args, ["volume_ml", "timestamp", "idempotency_key"])) return "Provide exactly volume_ml, timestamp and idempotency_key.";
      if (!measure(args.volume_ml, 0, 10_000, true)) return "volume_ml must be greater than 0 and at most 10000.";
      if (!timestamp(args.timestamp)) return "Use an ISO 8601 timestamp with an explicit UTC offset.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "add_sleep": {
      if (!exactKeys(args, ["start_time", "end_time", "idempotency_key"], ["title"])) return "Unexpected or missing sleep fields.";
      const span = validateSpan(args);
      if (span) return span;
      if (args.title !== undefined && args.title !== null && !text(args.title, 200)) return "title must contain 1-200 characters.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "add_workout": {
      if (!exactKeys(args, ["exercise_type", "start_time", "end_time", "idempotency_key"], ["title", "notes"])) return "Unexpected or missing workout fields.";
      if (typeof args.exercise_type !== "string" || !(EXERCISE_TYPES as readonly string[]).includes(args.exercise_type)) return "Unsupported exercise_type.";
      const span = validateSpan(args);
      if (span) return span;
      if (args.title !== undefined && args.title !== null && !text(args.title, 200)) return "title must contain 1-200 characters.";
      if (args.notes !== undefined && args.notes !== null && !text(args.notes, 1000)) return "notes must contain 1-1000 characters.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "add_body_measurement": {
      if (!exactKeys(args, ["timestamp", "idempotency_key"], ["body_fat_percent", "lean_body_mass_kg"])) return "Unexpected or missing body measurement fields.";
      if (!timestamp(args.timestamp)) return "Use an ISO 8601 timestamp with an explicit UTC offset.";
      const fat = args.body_fat_percent ?? null;
      const lean = args.lean_body_mass_kg ?? null;
      if (fat === null && lean === null) return "Provide body_fat_percent or lean_body_mass_kg.";
      if (fat !== null && !measure(fat, 0, 100, true)) return "body_fat_percent must be greater than 0 and at most 100.";
      if (lean !== null && !measure(lean, 0, 1000, true)) return "lean_body_mass_kg must be greater than 0 and at most 1000.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "get_daily_summary": {
      if (!exactKeys(args, ["date"])) return "Provide exactly date.";
      const date = args.date;
      return typeof date === "string" && DATE_ONLY.test(date) && !Number.isNaN(Date.parse(`${date}T00:00:00Z`)) &&
        new Date(`${date}T00:00:00Z`).toISOString().startsWith(date) ? null : "date must be a valid YYYY-MM-DD calendar date.";
    }
    case "add_weight": {
      if (!exactKeys(args, ["weight_kg", "timestamp", "idempotency_key"])) return "Provide exactly weight_kg, timestamp and idempotency_key.";
      if (!measure(args.weight_kg, 0, 1000, true)) return "weight_kg must be greater than 0 and at most 1000.";
      if (!timestamp(args.timestamp)) return "Use an ISO 8601 timestamp with an explicit UTC offset.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "delete_nutrition":
    case "delete_weight":
      if (!exactKeys(args, ["record_id"])) return "Provide exactly record_id.";
      return text(args.record_id, 200) ? null : "record_id must contain 1-200 characters.";
  }
}

export interface ActionRoute { operation: Operation; arguments: Json | null }

/** Maps the ChatGPT Actions HTTP surface onto the transport-neutral operation enum. Body is supplied later for writes. */
export function routeAction(method: string, url: URL): ActionRoute | null {
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts[0] !== "v1") return null;
  const path = parts.slice(1);
  const params = [...url.searchParams.keys()];
  const kind = path[0];
  if (method === "GET" && path.length === 1 && (kind === "status" || kind === "permissions")) {
    return params.length === 0 ? { operation: kind === "status" ? "get_gateway_status" : "get_permissions", arguments: {} } : null;
  }
  if (method === "GET" && path.length === 1 && (kind === "nutrition" || kind === "weight" || kind === "steps")) {
    const start = url.searchParams.getAll("start_time");
    const end = url.searchParams.getAll("end_time");
    if (params.length !== 2 || start.length !== 1 || end.length !== 1) return { operation: `get_${kind}`, arguments: { invalid: true } };
    return { operation: `get_${kind}`, arguments: { start_time: start[0], end_time: end[0] } };
  }
  if (method === "POST" && path.length === 1 && (kind === "nutrition" || kind === "weight")) {
    return params.length === 0 ? { operation: `add_${kind}`, arguments: null } : null;
  }
  if (method === "DELETE" && path.length === 2 && (kind === "nutrition" || kind === "weight")) {
    let id: string;
    try { id = decodeURIComponent(path[1] as string); } catch { return { operation: `delete_${kind}`, arguments: { invalid: true } }; }
    return params.length === 0 ? { operation: `delete_${kind}`, arguments: { record_id: id } } : null;
  }
  return null;
}

/** Reads a request body under a byte cap and rejects malformed UTF-8 instead of replacing characters. */
export async function readBoundedText(body: ReadableStream<Uint8Array> | null, maxBytes: number): Promise<string | "too_large" | "malformed"> {
  if (!body) return "";
  const reader = body.getReader();
  const chunks: Uint8Array[] = [];
  let size = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new BodyReadTimeout()), 10_000);
  });
  try {
    for (;;) {
      const { done, value } = await Promise.race([reader.read(), deadline]);
      if (done) break;
      size += value.byteLength;
      if (size > maxBytes) { await reader.cancel(); return "too_large"; }
      chunks.push(value);
    }
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    await reader.cancel();
  }
  const joined = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { joined.set(chunk, offset); offset += chunk.byteLength; }
  try { return new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(joined); } catch { return "malformed"; }
}

export class BodyReadTimeout extends Error {}

export function parseObject(text: string): Json | null {
  try {
    const value: unknown = JSON.parse(text);
    return isObject(value) ? value : null;
  } catch { return null; }
}

export { isObject };
