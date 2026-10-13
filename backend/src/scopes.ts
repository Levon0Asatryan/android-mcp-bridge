import type { Operation } from "./protocol";

/** OAuth scopes. Each maps 1:1 to a phone-side ClientPermission, except bridge.status which needs only a live grant. */
export const SCOPES = [
  "bridge.status", "health.nutrition.read", "health.nutrition.write", "health.nutrition.delete",
  "health.weight.read", "health.weight.write", "health.weight.delete", "health.steps.read",
  "health.hydration.read", "health.hydration.write", "health.sleep.read", "health.sleep.write", "health.workouts.read", "health.workouts.write",
  "health.heart_rate.read", "health.active_calories.read", "health.body.read", "health.body.write", "health.summary.read",
  "health.oxygen.read", "health.oxygen.write", "health.vitals.read", "health.activity.read", "health.mindfulness.read", "health.mindfulness.write",
] as const;
export type Scope = (typeof SCOPES)[number];

export const SCOPE_FOR: Record<Operation, Scope> = {
  get_gateway_status: "bridge.status", get_permissions: "bridge.status",
  get_nutrition: "health.nutrition.read", add_nutrition: "health.nutrition.write", delete_nutrition: "health.nutrition.delete",
  get_weight: "health.weight.read", add_weight: "health.weight.write", delete_weight: "health.weight.delete", get_steps: "health.steps.read",
  update_nutrition: "health.nutrition.write", add_nutrition_batch: "health.nutrition.write",
  get_hydration: "health.hydration.read", add_hydration: "health.hydration.write",
  get_sleep: "health.sleep.read", add_sleep: "health.sleep.write",
  get_workouts: "health.workouts.read", add_workout: "health.workouts.write",
  get_heart_rate: "health.heart_rate.read", get_active_calories: "health.active_calories.read",
  get_body_measurements: "health.body.read", add_body_measurement: "health.body.write",
  get_daily_summary: "health.summary.read", get_data_freshness: "health.summary.read",
  get_oxygen_saturation: "health.oxygen.read", add_oxygen_saturation: "health.oxygen.write", get_vitals: "health.vitals.read",
  get_activity_detail: "health.activity.read", get_mindfulness: "health.mindfulness.read", add_mindfulness: "health.mindfulness.write",
};

export const isScope = (value: unknown): value is Scope => typeof value === "string" && (SCOPES as readonly string[]).includes(value);
