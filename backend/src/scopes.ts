import type { Operation } from "./protocol";

/** OAuth scopes. Each maps 1:1 to a phone-side ClientPermission, except bridge.status which needs only a live grant. */
export const SCOPES = [
  "bridge.status", "nutrition.read", "nutrition.write", "nutrition.delete",
  "weight.read", "weight.write", "weight.delete", "steps.read",
  "hydration.read", "hydration.write", "sleep.read", "sleep.write", "workouts.read", "workouts.write",
  "heart_rate.read", "active_calories.read", "body.read", "body.write", "summary.read",
  "oxygen.read", "oxygen.write", "vitals.read", "activity.read", "mindfulness.read", "mindfulness.write",
] as const;
export type Scope = (typeof SCOPES)[number];

export const SCOPE_FOR: Record<Operation, Scope> = {
  get_gateway_status: "bridge.status", get_permissions: "bridge.status",
  get_nutrition: "nutrition.read", add_nutrition: "nutrition.write", delete_nutrition: "nutrition.delete",
  get_weight: "weight.read", add_weight: "weight.write", delete_weight: "weight.delete", get_steps: "steps.read",
  update_nutrition: "nutrition.write", add_nutrition_batch: "nutrition.write",
  get_hydration: "hydration.read", add_hydration: "hydration.write",
  get_sleep: "sleep.read", add_sleep: "sleep.write",
  get_workouts: "workouts.read", add_workout: "workouts.write",
  get_heart_rate: "heart_rate.read", get_active_calories: "active_calories.read",
  get_body_measurements: "body.read", add_body_measurement: "body.write",
  get_daily_summary: "summary.read", get_data_freshness: "summary.read",
  get_oxygen_saturation: "oxygen.read", add_oxygen_saturation: "oxygen.write", get_vitals: "vitals.read",
  get_activity_detail: "activity.read", get_mindfulness: "mindfulness.read", add_mindfulness: "mindfulness.write",
};

export const isScope = (value: unknown): value is Scope => typeof value === "string" && (SCOPES as readonly string[]).includes(value);
