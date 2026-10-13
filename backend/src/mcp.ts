import { createMcpHandler, McpServer } from "@modelcontextprotocol/server";
import { z } from "zod";
import type { Env, McpOutcome } from "./bridge";
import { bearerChallenge, type GrantProps } from "./oauth";
import { EXERCISE_TYPES, LIMITS, MAX_BATCH, type Operation } from "./protocol";
import { SCOPE_FOR } from "./scopes";

/** Present only after the provider validated issuer, audience (this /mcp resource), expiry and client. */
export interface McpAuth { props: GrantProps; scopes: string[] }

const isoTime = z.string().min(20).max(40).describe("ISO 8601 timestamp with an explicit UTC offset, e.g. 2025-01-31T08:00:00+01:00");
const range = z.object({ start_time: isoTime, end_time: isoTime }).strict();
const measure = z.number().finite().min(0).max(100_000);
const idempotency = z.string().min(1).max(128).describe("Unique key per intended entry; reuse it only to retry the same entry.");
const recordId = z.object({ record_id: z.string().min(1).max(200).describe("Exact id returned by a read or add tool.") }).strict();
const empty = z.object({}).strict();
const mealType = z.enum(["breakfast", "lunch", "dinner", "snack", "unknown"]);
const nutritionEntry = z.object({
  meal_name: z.string().min(1).max(200), meal_type: mealType, timestamp: isoTime,
  calories_kcal: measure, protein_g: measure, carbs_g: measure, fat_g: measure,
  sugar_g: measure.nullable().optional(), fiber_g: measure.nullable().optional(), sodium_mg: measure.nullable().optional(),
  idempotency_key: idempotency,
}).strict();
const nutritionChanges = z.object({
  meal_name: z.string().min(1).max(200), meal_type: mealType, timestamp: isoTime,
  calories_kcal: measure, protein_g: measure, carbs_g: measure, fat_g: measure,
  sugar_g: measure, fiber_g: measure, sodium_mg: measure,
}).partial().strict();
const span = { start_time: isoTime, end_time: isoTime };
const result = z.object({}).passthrough();

interface ToolSpec {
  operation: Operation; title: string; description: string; input: z.ZodObject;
  readOnly: boolean; destructive: boolean; idempotent: boolean;
}

const TOOLS: Record<string, ToolSpec> = {
  get_bridge_status: { operation: "get_gateway_status", title: "Bridge status", readOnly: true, destructive: false, idempotent: true, input: empty,
    description: "Phone bridge status: connection, Health Connect availability and app version. No health values." },
  get_permissions: { operation: "get_permissions", title: "Health permissions", readOnly: true, destructive: false, idempotent: true, input: empty,
    description: "Which Health Connect permissions and per-client grants are currently active on the phone." },
  get_nutrition: { operation: "get_nutrition", title: "Read nutrition", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read nutrition records between start_time and end_time (at most 366 days)." },
  add_nutrition: { operation: "add_nutrition", title: "Add nutrition", readOnly: false, destructive: false, idempotent: true,
    description: "Add one meal to Health Connect. Retrying with the same idempotency_key does not create a duplicate.",
    input: nutritionEntry },
  delete_nutrition: { operation: "delete_nutrition", title: "Delete nutrition", readOnly: false, destructive: true, idempotent: false, input: recordId,
    description: "Delete one nutrition record written by Health Bridge. The owner must confirm on the phone." },
  get_weight: { operation: "get_weight", title: "Read weight", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read body weight records between start_time and end_time (at most 366 days)." },
  add_weight: { operation: "add_weight", title: "Add weight", readOnly: false, destructive: false, idempotent: true,
    description: "Add one body weight entry in kilograms. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({ weight_kg: z.number().finite().gt(0).max(1000), timestamp: isoTime, idempotency_key: idempotency }).strict() },
  delete_weight: { operation: "delete_weight", title: "Delete weight", readOnly: false, destructive: true, idempotent: false, input: recordId,
    description: "Delete one weight record written by Health Bridge. The owner must confirm on the phone." },
  get_steps: { operation: "get_steps", title: "Read steps", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read the step total between start_time and end_time (at most 366 days)." },
  update_nutrition: { operation: "update_nutrition", title: "Update nutrition", readOnly: false, destructive: false, idempotent: true,
    description: "Correct a meal previously added by Health Bridge, in place and without creating a duplicate. Send only the fields to change, e.g. {\"calories_kcal\": 410}. Records created by other apps (such as Samsung Health) cannot be changed.",
    input: z.object({ record_id: z.string().min(1).max(200).describe("Exact id of a Health Bridge nutrition record."), changes: nutritionChanges }).strict() },
  add_nutrition_batch: { operation: "add_nutrition_batch", title: "Add nutrition batch", readOnly: false, destructive: false, idempotent: true,
    description: `Save up to ${MAX_BATCH} meals at once. Each entry needs its own idempotency_key; retrying the same entry never duplicates it. Returns one result per entry, in order, so a failure in one meal does not block the others.`,
    input: z.object({ entries: z.array(nutritionEntry).min(1).max(MAX_BATCH) }).strict() },
  get_hydration: { operation: "get_hydration", title: "Read hydration", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read water intake records and the de-duplicated total volume in millilitres between start_time and end_time (at most 366 days)." },
  add_hydration: { operation: "add_hydration", title: "Add water intake", readOnly: false, destructive: false, idempotent: true,
    description: "Log water intake in millilitres. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({ volume_ml: z.number().finite().gt(0).max(10_000), timestamp: isoTime, idempotency_key: idempotency }).strict() },
  get_sleep: { operation: "get_sleep", title: "Read sleep", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read sleep sessions with duration and stage totals (when the source provides stages) between start_time and end_time (at most 366 days)." },
  add_sleep: { operation: "add_sleep", title: "Add sleep session", readOnly: false, destructive: false, idempotent: true,
    description: "Log one sleep session (at most 24 hours). Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({ ...span, title: z.string().min(1).max(200).nullable().optional(), idempotency_key: idempotency }).strict() },
  get_workouts: { operation: "get_workouts", title: "Read workouts", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read exercise sessions (type, title, start, end, duration) between start_time and end_time (at most 366 days)." },
  add_workout: { operation: "add_workout", title: "Add workout", readOnly: false, destructive: false, idempotent: true,
    description: "Log one exercise session (at most 24 hours). Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({
      exercise_type: z.enum(EXERCISE_TYPES), ...span, title: z.string().min(1).max(200).nullable().optional(),
      notes: z.string().min(1).max(1000).nullable().optional(), idempotency_key: idempotency,
    }).strict() },
  get_heart_rate: { operation: "get_heart_rate", title: "Read heart rate", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Heart rate summary between start_time and end_time: average, minimum and maximum bpm, plus average resting heart rate when available." },
  get_active_calories: { operation: "get_active_calories", title: "Read active calories", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Active calories burned (kcal) between start_time and end_time, with the contributing source applications." },
  get_body_measurements: { operation: "get_body_measurements", title: "Read body measurements", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read body fat percentage and lean body mass records between start_time and end_time (at most 366 days)." },
  add_body_measurement: { operation: "add_body_measurement", title: "Add body measurement", readOnly: false, destructive: false, idempotent: true,
    description: "Log body fat percentage and/or lean body mass (kg). Health Connect has no waist circumference type, so waist cannot be stored. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({
      timestamp: isoTime, body_fat_percent: z.number().finite().gt(0).max(100).nullable().optional(),
      lean_body_mass_kg: z.number().finite().gt(0).max(1000).nullable().optional(), idempotency_key: idempotency,
    }).strict() },
  get_daily_summary: { operation: "get_daily_summary", title: "Daily health summary", readOnly: true, destructive: false, idempotent: true,
    description: "One-call summary of a calendar day in the phone's time zone: nutrition (totals per source), hydration, steps, workouts, sleep, weight, body measurements, active calories and heart rate. Sections the connection may not read are reported as not_permitted. Today is summarised up to now.",
    input: z.object({ date: z.string().length(10).describe("Calendar date, YYYY-MM-DD, in the phone's time zone.") }).strict() },
  get_data_freshness: { operation: "get_data_freshness", title: "Data freshness", readOnly: true, destructive: false, idempotent: true, input: empty,
    description: "Per data type, which apps wrote data in the last 30 days and when each last wrote or synced (e.g. Samsung Health vs Health Bridge). Use it to tell missing data from data that has not synced yet." },
};

function server(env: Env, origin: string, auth: McpAuth | null): McpServer {
  const mcp = new McpServer({ name: "health-bridge", version: "1.0.0" }, { capabilities: { tools: {} } });
  mcp.registerTool("bridge_connection_check", {
    title: "Connection check",
    description: "Confirms this MCP endpoint answers. Returns no account, phone, pairing or health information.",
    inputSchema: empty, outputSchema: z.object({ ok: z.literal(true), protocol: z.literal("mcp") }).strict(),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    _meta: { securitySchemes: [{ type: "noauth" }, { type: "oauth2", scopes: [] }] },
  }, async () => ({ content: [{ type: "text", text: "Health Bridge MCP endpoint is reachable." }], structuredContent: { ok: true, protocol: "mcp" } }));

  for (const [name, spec] of Object.entries(TOOLS)) {
    const scope = SCOPE_FOR[spec.operation];
    mcp.registerTool(name, {
      title: spec.title, description: spec.description, inputSchema: spec.input, outputSchema: result,
      annotations: { readOnlyHint: spec.readOnly, destructiveHint: spec.destructive, idempotentHint: spec.idempotent, openWorldHint: false },
      _meta: { securitySchemes: [{ type: "oauth2", scopes: [scope] }] },
    }, async (args: Record<string, unknown>) => {
      if (!auth) return challenge(origin, "Sign in to use health tools.", undefined);
      if (!auth.scopes.includes(scope)) return challenge(origin, `This connection was not granted ${scope}.`, scope);
      const id = env.BRIDGE.idFromName("owner");
      const outcome = await env.BRIDGE.get(id).mcpExecute(spec.operation, args, auth.props, auth.scopes) as unknown as McpOutcome;
      const failed = outcome.status >= 400 || outcome.body.success === false;
      return {
        content: [{ type: "text", text: failed ? `Failed: ${String(outcome.body.error_category ?? "error")}: ${String(outcome.body.message ?? "")}` : JSON.stringify(outcome.body) }],
        structuredContent: outcome.body, isError: failed,
      };
    });
  }
  return mcp;
}

function challenge(origin: string, message: string, scope: string | undefined) {
  return {
    content: [{ type: "text" as const, text: message }],
    structuredContent: { success: false, error_category: scope ? "permission_denied" : "unauthorized", message },
    isError: true,
    _meta: { "mcp/www_authenticate": [bearerChallenge(origin, scope ? "insufficient_scope" : "invalid_token", scope)] },
  };
}

/** Stateless Streamable HTTP. A fresh server per request; nothing is persisted and SDK errors are not logged. */
export async function handleMcp(request: Request, env: Env, origin: string, auth: McpAuth | null): Promise<Response> {
  const handler = createMcpHandler(() => server(env, origin, auth), {
    legacy: "stateless", responseMode: "json", maxRequestBodySize: LIMITS.inputBytes,
    onerror: () => { console.error("MCP protocol processing failed."); },
  });
  try { return await handler.fetch(request); }
  finally { await handler.close?.(); }
}
