import type { Operation } from "./protocol";

/** OAuth scopes. Each maps 1:1 to a phone-side ClientPermission, except bridge.status which needs only a live grant. */
export const SCOPES = [
  "bridge.status", "nutrition.read", "nutrition.write", "nutrition.delete",
  "weight.read", "weight.write", "weight.delete", "steps.read",
] as const;
export type Scope = (typeof SCOPES)[number];

export const SCOPE_FOR: Record<Operation, Scope> = {
  get_gateway_status: "bridge.status", get_permissions: "bridge.status",
  get_nutrition: "nutrition.read", add_nutrition: "nutrition.write", delete_nutrition: "nutrition.delete",
  get_weight: "weight.read", add_weight: "weight.write", delete_weight: "weight.delete", get_steps: "steps.read",
};

export const isScope = (value: unknown): value is Scope => typeof value === "string" && (SCOPES as readonly string[]).includes(value);
