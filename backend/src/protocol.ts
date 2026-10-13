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
  "get_oxygen_saturation", "add_oxygen_saturation", "get_vitals", "get_activity_detail", "get_mindfulness", "add_mindfulness",
  "set_alarm", "dismiss_alarm", "get_next_alarm", "show_alarms", "set_timer", "send_notification", "speak",
] as const;
export type Operation = (typeof OPERATIONS)[number];

const ACTION_OPERATIONS: readonly string[] = ["set_alarm", "dismiss_alarm", "show_alarms", "set_timer", "send_notification", "speak"];
export const isWrite = (op: Operation) => op.startsWith("add_") || op.startsWith("update_") || ACTION_OPERATIONS.includes(op);
export const isDelete = (op: Operation) => op.startsWith("delete_");

export type ErrorCategory =
  | "unauthorized" | "permission_denied" | "health_permission_denied" | "phone_locked" | "power_saving"
  | "phone_unavailable" | "approval_required" | "approval_denied" | "approval_pending" | "expired_request"
  | "idempotency_conflict" | "result_too_large" | "rate_limited" | "outcome_unknown" | "invalid_input"
  | "health_unavailable" | "module_unavailable" | "not_found" | "protocol_error" | "not_configured" | "already_paired" | "not_paired";

export const STATUS: Record<ErrorCategory, number> = {
  unauthorized: 401, permission_denied: 403, health_permission_denied: 403, phone_locked: 403,
  power_saving: 403, approval_required: 403, approval_denied: 403, phone_unavailable: 503,
  approval_pending: 409, expired_request: 504, idempotency_conflict: 409, result_too_large: 422,
  rate_limited: 429, outcome_unknown: 504, invalid_input: 400, health_unavailable: 503, module_unavailable: 503,
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

export const TOKEN_PATTERN = /^amb\.[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/;
const TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/;
const MEAL_TYPES = new Set(["breakfast", "lunch", "dinner", "snack", "unknown"]);
export const EXERCISE_TYPES = [
  "strength_training", "running", "walking", "cycling", "swimming", "yoga", "pilates", "hiit", "hiking", "rowing",
  "elliptical", "stair_climbing", "stretching", "dancing", "calisthenics", "other",
] as const;
export const MAX_BATCH = 20;
/** Optional detail nutrients, all >= 0; the unit is part of the key. */
export const NUTRIENT_KEYS = [
  "sugar_g", "fiber_g", "sodium_mg",
  "saturated_fat_g", "unsaturated_fat_g", "monounsaturated_fat_g", "polyunsaturated_fat_g", "trans_fat_g", "cholesterol_mg",
  "potassium_mg", "calcium_mg", "iron_mg", "magnesium_mg", "zinc_mg", "phosphorus_mg", "copper_mg", "manganese_mg", "chloride_mg",
  "selenium_mcg", "chromium_mcg", "molybdenum_mcg", "iodine_mcg",
  "vitamin_a_mcg", "vitamin_b6_mg", "vitamin_b12_mcg", "vitamin_c_mg", "vitamin_d_mcg", "vitamin_e_mg", "vitamin_k_mcg",
  "thiamin_mg", "riboflavin_mg", "niacin_mg", "folate_mcg", "folic_acid_mcg", "biotin_mcg", "pantothenic_acid_mg", "caffeine_mg",
] as const;
export const WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"] as const;
export const MINDFULNESS_TYPES = ["meditation", "breathing", "music", "movement", "unguided", "other"] as const;
const NUTRITION_FIELDS: string[] = ["meal_name", "meal_type", "timestamp", "calories_kcal", "protein_g", "carbs_g", "fat_g", ...NUTRIENT_KEYS];
/** Body composition fields accepted by add_body_measurement, with their upper bounds. */
const BODY_FIELDS: [string, number][] = [
  ["body_fat_percent", 100], ["lean_body_mass_kg", 1000], ["height_cm", 300], ["bone_mass_kg", 100], ["body_water_mass_kg", 500],
];
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
  for (const key of NUTRIENT_KEYS) {
    if (args[key] !== undefined && args[key] !== null && !measure(args[key], 0, 100_000)) return `${key} must be 0-100000.`;
  }
  return null;
}

function validateNutritionEntry(args: Json): string | null {
  const required = ["meal_name", "meal_type", "timestamp", "calories_kcal", "protein_g", "carbs_g", "fat_g", "idempotency_key"];
  if (!exactKeys(args, required, [...NUTRIENT_KEYS])) return "Unexpected or missing nutrition fields.";
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
    case "get_next_alarm":
    case "show_alarms":
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
    case "get_body_measurements":
    case "get_oxygen_saturation":
    case "get_vitals":
    case "get_activity_detail":
    case "get_mindfulness": {
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
      if (!exactKeys(args, ["timestamp", "idempotency_key"], [...BODY_FIELDS.map(([key]) => key)])) return "Unexpected or missing body measurement fields.";
      if (!timestamp(args.timestamp)) return "Use an ISO 8601 timestamp with an explicit UTC offset.";
      let any = false;
      for (const [key, max] of BODY_FIELDS) {
        const value = args[key] ?? null;
        if (value === null) continue;
        any = true;
        if (!measure(value, 0, max, true)) return `${key} must be greater than 0 and at most ${max}.`;
      }
      if (!any) return `Provide at least one of ${BODY_FIELDS.map(([key]) => key).join(", ")}.`;
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "add_oxygen_saturation": {
      if (!exactKeys(args, ["percent", "timestamp", "idempotency_key"])) return "Provide exactly percent, timestamp and idempotency_key.";
      if (!measure(args.percent, 0, 100, true)) return "percent must be greater than 0 and at most 100.";
      if (!timestamp(args.timestamp)) return "Use an ISO 8601 timestamp with an explicit UTC offset.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "add_mindfulness": {
      if (!exactKeys(args, ["session_type", "start_time", "end_time", "idempotency_key"], ["title", "notes"])) return "Unexpected or missing mindfulness fields.";
      if (typeof args.session_type !== "string" || !(MINDFULNESS_TYPES as readonly string[]).includes(args.session_type)) return "Unsupported session_type.";
      const span = validateSpan(args);
      if (span) return span;
      if (args.title !== undefined && args.title !== null && !text(args.title, 200)) return "title must contain 1-200 characters.";
      if (args.notes !== undefined && args.notes !== null && !text(args.notes, 1000)) return "notes must contain 1-1000 characters.";
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
    case "set_alarm": {
      if (!exactKeys(args, ["hour", "minute", "idempotency_key"], ["label", "days"])) return "Provide hour, minute and idempotency_key, optionally label and days.";
      if (!Number.isInteger(args.hour) || (args.hour as number) < 0 || (args.hour as number) > 23) return "hour must be an integer 0-23.";
      if (!Number.isInteger(args.minute) || (args.minute as number) < 0 || (args.minute as number) > 59) return "minute must be an integer 0-59.";
      if (args.label !== undefined && args.label !== null && !text(args.label, 100)) return "label must contain 1-100 characters.";
      if (args.days !== undefined && args.days !== null) {
        const days = args.days;
        if (!Array.isArray(days) || days.length < 1 || days.length > 7 || new Set(days).size !== days.length ||
          !days.every((d) => typeof d === "string" && (WEEKDAYS as readonly string[]).includes(d))) return `days must be 1-7 distinct values of ${WEEKDAYS.join(", ")}.`;
      }
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "set_timer": {
      if (!exactKeys(args, ["seconds", "idempotency_key"], ["label"])) return "Provide seconds and idempotency_key, optionally label.";
      if (!Number.isInteger(args.seconds) || (args.seconds as number) < 1 || (args.seconds as number) > 86_400) return "seconds must be an integer 1-86400.";
      if (args.label !== undefined && args.label !== null && !text(args.label, 100)) return "label must contain 1-100 characters.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "dismiss_alarm": {
      if (!exactKeys(args, ["mode"], ["hour", "minute", "label"])) return "Provide mode, plus hour and minute (mode time) or label (mode label).";
      if (args.mode === "next") return Object.keys(args).length === 1 ? null : "mode next takes no other fields.";
      if (args.mode === "time") {
        if (!exactKeys(args, ["mode", "hour", "minute"])) return "mode time needs exactly hour and minute.";
        if (!Number.isInteger(args.hour) || (args.hour as number) < 0 || (args.hour as number) > 23) return "hour must be an integer 0-23.";
        return Number.isInteger(args.minute) && (args.minute as number) >= 0 && (args.minute as number) <= 59 ? null : "minute must be an integer 0-59.";
      }
      if (args.mode === "label") {
        if (!exactKeys(args, ["mode", "label"])) return "mode label needs exactly label.";
        return text(args.label, 100) ? null : "label must contain 1-100 characters.";
      }
      return "mode must be next, time or label.";
    }
    case "send_notification": {
      if (!exactKeys(args, ["title", "message", "idempotency_key"], ["priority"])) return "Provide title, message and idempotency_key, optionally priority.";
      if (!text(args.title, 80)) return "title must contain 1-80 characters.";
      if (!text(args.message, 500)) return "message must contain 1-500 characters.";
      if (args.priority !== undefined && args.priority !== "normal" && args.priority !== "high") return "priority must be normal or high.";
      return text(args.idempotency_key, 128) ? null : "idempotency_key is required (1-128 characters).";
    }
    case "speak": {
      if (!exactKeys(args, ["message", "idempotency_key"])) return "Provide exactly message and idempotency_key.";
      if (!text(args.message, 500)) return "message must contain 1-500 characters.";
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
