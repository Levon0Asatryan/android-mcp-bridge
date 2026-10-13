import { createMcpHandler, McpServer } from "@modelcontextprotocol/server";
import { z } from "zod";
import type { Env, McpOutcome } from "./bridge";
import { bearerChallenge, type GrantProps } from "./oauth";
import { EXERCISE_TYPES, LIMITS, MAX_BATCH, MINDFULNESS_TYPES, NUTRIENT_KEYS, WEEKDAYS, type Operation } from "./protocol";
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
const nutrientShape = Object.fromEntries(NUTRIENT_KEYS.map((key) => [key, measure.nullable().optional()]));
const nutrientChanges = Object.fromEntries(NUTRIENT_KEYS.map((key) => [key, measure]));
const nutritionEntry = z.object({
  meal_name: z.string().min(1).max(200), meal_type: mealType, timestamp: isoTime,
  calories_kcal: measure, protein_g: measure, carbs_g: measure, fat_g: measure,
  ...nutrientShape, idempotency_key: idempotency,
}).strict();
const nutritionChanges = z.object({
  meal_name: z.string().min(1).max(200), meal_type: mealType, timestamp: isoTime,
  calories_kcal: measure, protein_g: measure, carbs_g: measure, fat_g: measure, ...nutrientChanges,
}).partial().strict();
const positive = (max: number) => z.number().finite().gt(0).max(max).nullable().optional();
const span = { start_time: isoTime, end_time: isoTime };
const result = z.object({}).passthrough();

/** Tool modules. Each module's tools are exposed as `<module>_<tool>`; core tools keep their `bridge_` names. */
export const MODULES = {
  core: { title: "Bridge", description: "Connection and permission status." },
  health: { title: "Health", description: "Health Connect data: nutrition, body, activity, sleep, vitals, mindfulness." },
  clock: { title: "Clock", description: "Alarms and timers in the phone's Clock app." },
  notify: { title: "Notify", description: "Notifications and spoken messages on the phone." },
} as const;
export type ModuleName = keyof typeof MODULES;

interface ToolSpec {
  module?: ModuleName; operation: Operation; title: string; description: string; input: z.ZodObject;
  readOnly: boolean; destructive: boolean; idempotent: boolean;
}

const TOOLS: Record<string, ToolSpec> = {
  bridge_status: { module: "core", operation: "get_gateway_status", title: "Bridge status", readOnly: true, destructive: false, idempotent: true, input: empty,
    description: "Phone bridge status: connection, Health Connect availability and app version. No health values." },
  bridge_permissions: { module: "core", operation: "get_permissions", title: "Health permissions", readOnly: true, destructive: false, idempotent: true, input: empty,
    description: "Which Health Connect permissions and per-client grants are currently active on the phone." },
  get_nutrition: { operation: "get_nutrition", title: "Read nutrition", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read nutrition records between start_time and end_time (at most 366 days)." },
  add_nutrition: { operation: "add_nutrition", title: "Add nutrition", readOnly: false, destructive: false, idempotent: true,
    description: "Add one meal to Health Connect. Besides calories and macros it accepts optional detail: sugar, fiber, sodium, fat types, cholesterol, minerals, vitamins and caffeine (units are in the field names). Retrying with the same idempotency_key does not create a duplicate.",
    input: nutritionEntry },
  delete_nutrition: { operation: "delete_nutrition", title: "Delete nutrition", readOnly: false, destructive: true, idempotent: false, input: recordId,
    description: "Delete one nutrition record written by Android MCP Bridge. The owner must confirm on the phone." },
  get_weight: { operation: "get_weight", title: "Read weight", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read body weight records between start_time and end_time (at most 366 days)." },
  add_weight: { operation: "add_weight", title: "Add weight", readOnly: false, destructive: false, idempotent: true,
    description: "Add one body weight entry in kilograms. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({ weight_kg: z.number().finite().gt(0).max(1000), timestamp: isoTime, idempotency_key: idempotency }).strict() },
  delete_weight: { operation: "delete_weight", title: "Delete weight", readOnly: false, destructive: true, idempotent: false, input: recordId,
    description: "Delete one weight record written by Android MCP Bridge. The owner must confirm on the phone." },
  get_steps: { operation: "get_steps", title: "Read steps", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Read the step total between start_time and end_time (at most 366 days)." },
  update_nutrition: { operation: "update_nutrition", title: "Update nutrition", readOnly: false, destructive: false, idempotent: true,
    description: "Correct a meal previously added by Android MCP Bridge, in place and without creating a duplicate. Send only the fields to change, e.g. {\"calories_kcal\": 410}. Records created by other apps (such as Samsung Health) cannot be changed.",
    input: z.object({ record_id: z.string().min(1).max(200).describe("Exact id of a Android MCP Bridge nutrition record."), changes: nutritionChanges }).strict() },
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
    description: "Read body composition (body fat %, lean body mass, height, bone mass, body water mass) between start_time and end_time (at most 366 days)." },
  add_body_measurement: { operation: "add_body_measurement", title: "Add body measurement", readOnly: false, destructive: false, idempotent: true,
    description: "Log body composition: body fat %, lean body mass, height (cm), bone mass and/or body water mass (kg). At least one value. Health Connect has no waist circumference type. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({
      timestamp: isoTime, body_fat_percent: positive(100), lean_body_mass_kg: positive(1000), height_cm: positive(300),
      bone_mass_kg: positive(100), body_water_mass_kg: positive(500), idempotency_key: idempotency,
    }).strict() },
  get_oxygen_saturation: { operation: "get_oxygen_saturation", title: "Read blood oxygen", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Blood oxygen (SpO2) readings with average, minimum and maximum percent between start_time and end_time (at most 366 days)." },
  add_oxygen_saturation: { operation: "add_oxygen_saturation", title: "Add blood oxygen", readOnly: false, destructive: false, idempotent: true,
    description: "Log one manual SpO2 reading in percent. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({ percent: z.number().finite().gt(0).max(100), timestamp: isoTime, idempotency_key: idempotency }).strict() },
  get_vitals: { operation: "get_vitals", title: "Read vitals", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Heart rate variability (RMSSD, ms) and respiratory rate (breaths/min): averages, minimum, maximum and sample count between start_time and end_time." },
  get_activity_detail: { operation: "get_activity_detail", title: "Read activity detail", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Distance (m), floors climbed, elevation gained (m), speed (average/max m/s), total calories and basal calories (kcal) between start_time and end_time." },
  get_mindfulness: { operation: "get_mindfulness", title: "Read mindfulness", readOnly: true, destructive: false, idempotent: true, input: range,
    description: "Mindfulness sessions (type, title, start, end, duration) between start_time and end_time (at most 366 days)." },
  add_mindfulness: { operation: "add_mindfulness", title: "Add mindfulness session", readOnly: false, destructive: false, idempotent: true,
    description: "Log one mindfulness session (at most 24 hours). Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({
      session_type: z.enum(MINDFULNESS_TYPES), ...span, title: z.string().min(1).max(200).nullable().optional(),
      notes: z.string().min(1).max(1000).nullable().optional(), idempotency_key: idempotency,
    }).strict() },
  get_next_alarm: { module: "clock", operation: "get_next_alarm", title: "Next alarm", readOnly: true, destructive: false, idempotent: true, input: empty,
    description: "The next alarm that will ring on the phone (from any alarm app), with its time. Android does not reveal any other alarm." },
  set_alarm: { module: "clock", operation: "set_alarm", title: "Set alarm", readOnly: false, destructive: false, idempotent: true,
    description: "Create an alarm in the phone's Clock app at hour:minute (24-hour, phone time zone), optionally repeating on days. If the phone is not in use the owner may need to tap a notification to finish; the result says which. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({
      hour: z.number().int().min(0).max(23), minute: z.number().int().min(0).max(59), label: z.string().min(1).max(100).nullable().optional(),
      days: z.array(z.enum(WEEKDAYS)).min(1).max(7).nullable().optional().describe("Repeat days; omit for a one-time alarm."), idempotency_key: idempotency,
    }).strict() },
  dismiss_alarm: { module: "clock", operation: "dismiss_alarm", title: "Dismiss alarm", readOnly: false, destructive: true, idempotent: false,
    description: "Dismiss or remove an alarm in the phone's Clock app: the next one, one at a given time, or one with a given label. Best effort; the Clock app decides.",
    input: z.object({
      mode: z.enum(["next", "time", "label"]), hour: z.number().int().min(0).max(23).optional(), minute: z.number().int().min(0).max(59).optional(),
      label: z.string().min(1).max(100).optional(),
    }).strict() },
  show_alarms: { module: "clock", operation: "show_alarms", title: "Show alarms", readOnly: false, destructive: false, idempotent: true, input: empty,
    description: "Open the alarm list in the phone's Clock app so the owner can see it." },
  set_timer: { module: "clock", operation: "set_timer", title: "Start timer", readOnly: false, destructive: false, idempotent: true,
    description: "Start a countdown timer in the phone's Clock app (1 second to 24 hours). Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({ seconds: z.number().int().min(1).max(86_400), label: z.string().min(1).max(100).nullable().optional(), idempotency_key: idempotency }).strict() },
  send_notification: { module: "notify", operation: "send_notification", title: "Send notification", readOnly: false, destructive: false, idempotent: true,
    description: "Show a notification on the phone. Use priority high only for something urgent: it pops up on screen. Retrying with the same idempotency_key does not create a duplicate.",
    input: z.object({ title: z.string().min(1).max(80), message: z.string().min(1).max(500), priority: z.enum(["normal", "high"]).optional(), idempotency_key: idempotency }).strict() },
  speak: { module: "notify", operation: "speak", title: "Speak aloud", readOnly: false, destructive: false, idempotent: true,
    description: "Read a message aloud on the phone speaker (text to speech, up to 500 characters). Fails if the media volume is muted. Retrying with the same idempotency_key does not repeat it.",
    input: z.object({ message: z.string().min(1).max(500), idempotency_key: idempotency }).strict() },
  get_daily_summary: { operation: "get_daily_summary", title: "Daily health summary", readOnly: true, destructive: false, idempotent: true,
    description: "One-call summary of a calendar day in the phone's time zone: nutrition (totals per source), hydration, steps, workouts, sleep, weight, body measurements, active calories and heart rate. Sections the connection may not read are reported as not_permitted. Today is summarised up to now.",
    input: z.object({ date: z.string().length(10).describe("Calendar date, YYYY-MM-DD, in the phone's time zone.") }).strict() },
  get_data_freshness: { operation: "get_data_freshness", title: "Data freshness", readOnly: true, destructive: false, idempotent: true, input: empty,
    description: "Per data type, which apps wrote data in the last 30 days and when each last wrote or synced (e.g. Samsung Health vs Android MCP Bridge). Use it to tell missing data from data that has not synced yet." },
};

const toolName = (key: string, spec: ToolSpec) => {
  const module = spec.module ?? "health";
  return module === "core" ? key : `${module}_${key}`;
};

function server(env: Env, origin: string, auth: McpAuth | null): McpServer {
  const mcp = new McpServer({ name: "android-mcp", version: "1.0.0" }, { capabilities: { tools: {} } });
  mcp.registerTool("bridge_connection_check", {
    title: "Connection check",
    description: "Confirms this MCP endpoint answers. Returns no account, phone, pairing or health information.",
    inputSchema: empty, outputSchema: z.object({ ok: z.literal(true), protocol: z.literal("mcp") }).strict(),
    annotations: { readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    _meta: { securitySchemes: [{ type: "noauth" }, { type: "oauth2", scopes: [] }] },
  }, async () => ({ content: [{ type: "text", text: "Android MCP Bridge MCP endpoint is reachable." }], structuredContent: { ok: true, protocol: "mcp" } }));

  for (const [key, spec] of Object.entries(TOOLS)) {
    const name = toolName(key, spec);
    const scope = SCOPE_FOR[spec.operation];
    mcp.registerTool(name, {
      title: spec.title, description: spec.description, inputSchema: spec.input, outputSchema: result,
      annotations: { readOnlyHint: spec.readOnly, destructiveHint: spec.destructive, idempotentHint: spec.idempotent, openWorldHint: false },
      _meta: { securitySchemes: [{ type: "oauth2", scopes: [scope] }] },
    }, async (args: Record<string, unknown>) => {
      if (!auth) return challenge(origin, "Sign in to use phone tools.", undefined);
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
