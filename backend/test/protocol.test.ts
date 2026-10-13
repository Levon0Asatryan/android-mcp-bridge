import { describe, expect, it } from "vitest";
import { configuredOrigin } from "../src/index";
import { routeAction, statusFor, TOKEN_PATTERN, validateArguments, withinOutputBudget } from "../src/protocol";

describe("extended operations", () => {
  const meal = { meal_name: "Chicken", meal_type: "lunch", timestamp: "2030-01-01T12:00:00+04:00", calories_kcal: 300, protein_g: 40, carbs_g: 0, fat_g: 8 };
  it("validates batches, updates and new writes", () => {
    expect(validateArguments("add_nutrition_batch", { entries: [{ ...meal, idempotency_key: "a" }, { ...meal, idempotency_key: "b" }] })).toBeNull();
    expect(validateArguments("add_nutrition_batch", { entries: [{ ...meal, idempotency_key: "a" }, { ...meal, idempotency_key: "a" }] })).not.toBeNull();
    expect(validateArguments("add_nutrition_batch", { entries: [] })).not.toBeNull();
    expect(validateArguments("add_nutrition_batch", { entries: Array.from({ length: 21 }, (_, i) => ({ ...meal, idempotency_key: `k${i}` })) })).not.toBeNull();
    expect(validateArguments("update_nutrition", { record_id: "r", changes: { calories_kcal: 410 } })).toBeNull();
    expect(validateArguments("update_nutrition", { record_id: "r", changes: {} })).not.toBeNull();
    expect(validateArguments("update_nutrition", { record_id: "r", changes: { idempotency_key: "x" } })).not.toBeNull();
    expect(validateArguments("add_hydration", { volume_ml: 250, timestamp: meal.timestamp, idempotency_key: "k" })).toBeNull();
    expect(validateArguments("add_hydration", { volume_ml: 0, timestamp: meal.timestamp, idempotency_key: "k" })).not.toBeNull();
    const span = { start_time: "2030-01-01T00:00:00Z", end_time: "2030-01-01T08:00:00Z", idempotency_key: "k" };
    expect(validateArguments("add_sleep", span)).toBeNull();
    expect(validateArguments("add_sleep", { ...span, end_time: "2030-01-02T08:00:00Z" })).not.toBeNull();
    expect(validateArguments("add_workout", { ...span, exercise_type: "running" })).toBeNull();
    expect(validateArguments("add_workout", { ...span, exercise_type: "skydiving" })).not.toBeNull();
    expect(validateArguments("add_body_measurement", { timestamp: meal.timestamp, body_fat_percent: 18, idempotency_key: "k" })).toBeNull();
    expect(validateArguments("add_body_measurement", { timestamp: meal.timestamp, idempotency_key: "k" })).not.toBeNull();
    expect(validateArguments("get_daily_summary", { date: "2030-02-28" })).toBeNull();
    expect(validateArguments("get_daily_summary", { date: "2030-02-30" })).not.toBeNull();
    expect(validateArguments("get_data_freshness", {})).toBeNull();
  });
});

describe("protocol policy", () => {
  it("requires idempotency keys and exact finite fields for writes", () => {
    const weight = { weight_kg: 70, timestamp: "2030-01-01T00:00:00+04:00", idempotency_key: "k" };
    expect(validateArguments("add_weight", weight)).toBeNull();
    expect(validateArguments("add_weight", { ...weight, idempotency_key: undefined })).not.toBeNull();
    expect(validateArguments("add_weight", { weight_kg: 70, timestamp: weight.timestamp })).not.toBeNull();
    expect(validateArguments("add_weight", { ...weight, weight_kg: Number.POSITIVE_INFINITY })).not.toBeNull();
    expect(validateArguments("add_weight", { ...weight, weight_kg: 0 })).not.toBeNull();
    expect(validateArguments("add_weight", { ...weight, note: "x" })).not.toBeNull();
    expect(validateArguments("get_steps", { start_time: "2030-01-02T00:00:00Z", end_time: "2030-01-01T00:00:00Z" })).not.toBeNull();
    expect(validateArguments("get_steps", { start_time: "2028-01-01T00:00:00Z", end_time: "2030-01-01T00:00:00Z" })).not.toBeNull();
    expect(validateArguments("delete_weight", { record_id: "r".repeat(201) })).not.toBeNull();
  });

  it("maps only the fixed Actions routes", () => {
    expect(routeAction("GET", new URL("https://h/v1/status"))?.operation).toBe("get_gateway_status");
    expect(routeAction("DELETE", new URL("https://h/v1/weight/a%2Fb"))?.arguments).toEqual({ record_id: "a/b" });
    expect(routeAction("POST", new URL("https://h/v1/steps"))).toBeNull();
    expect(routeAction("GET", new URL("https://h/v1/status?x=1"))).toBeNull();
    expect(routeAction("PUT", new URL("https://h/v1/nutrition"))).toBeNull();
  });

  it("enforces both byte and character output budgets", () => {
    expect(withinOutputBudget("a".repeat(65_000))).toBe(true);
    expect(withinOutputBudget("€".repeat(22_000))).toBe(false);
    expect(withinOutputBudget("a".repeat(90_001))).toBe(false);
  });

  it("keeps structured categories stable", () => {
    expect(statusFor("unauthorized")).toBe(401);
    expect(statusFor("phone_locked")).toBe(403);
    expect(statusFor("outcome_unknown")).toBe(504);
    expect(statusFor("anything-else")).toBe(502);
    expect(TOKEN_PATTERN.test(`amb.${"0".repeat(8)}-0000-0000-0000-${"0".repeat(12)}.${"A".repeat(43)}`)).toBe(true);
  });

  it("accepts only canonical HTTPS or loopback origins", () => {
    expect(configuredOrigin("https://bridge.example.test")).toBe("https://bridge.example.test");
    expect(configuredOrigin("http://localhost:8787")).toBe("http://localhost:8787");
    expect(configuredOrigin("http://bridge.example.test")).toBeNull();
    expect(configuredOrigin("https://bridge.example.test/")).toBeNull();
    expect(configuredOrigin("https://user@bridge.example.test")).toBeNull();
  });
});

describe("detail nutrients and new operations", () => {
  const meal = { meal_name: "m", meal_type: "lunch", timestamp: "2030-01-01T12:00:00Z", calories_kcal: 1, protein_g: 1, carbs_g: 1, fat_g: 1, idempotency_key: "k" };
  it("accepts detail nutrients and rejects bad ones", () => {
    expect(validateArguments("add_nutrition", { ...meal, vitamin_c_mg: 30, iron_mg: null, caffeine_mg: 80 })).toBeNull();
    expect(validateArguments("add_nutrition", { ...meal, vitamin_c_mg: -1 })).not.toBeNull();
    expect(validateArguments("add_nutrition", { ...meal, vitamin_z_mg: 1 })).not.toBeNull();
    expect(validateArguments("update_nutrition", { record_id: "r", changes: { zinc_mg: 5 } })).toBeNull();
  });
  it("validates oxygen, body composition and mindfulness", () => {
    expect(validateArguments("add_oxygen_saturation", { percent: 97, timestamp: meal.timestamp, idempotency_key: "k" })).toBeNull();
    expect(validateArguments("add_oxygen_saturation", { percent: 101, timestamp: meal.timestamp, idempotency_key: "k" })).not.toBeNull();
    expect(validateArguments("add_body_measurement", { timestamp: meal.timestamp, height_cm: 180, idempotency_key: "k" })).toBeNull();
    expect(validateArguments("add_body_measurement", { timestamp: meal.timestamp, idempotency_key: "k" })).not.toBeNull();
    const span = { start_time: "2030-01-01T07:00:00Z", end_time: "2030-01-01T07:20:00Z", idempotency_key: "k" };
    expect(validateArguments("add_mindfulness", { ...span, session_type: "meditation" })).toBeNull();
    expect(validateArguments("add_mindfulness", { ...span, session_type: "nap" })).not.toBeNull();
  });
  it("validates clock and notify actions", () => {
    const k = { idempotency_key: "k" };
    expect(validateArguments("set_alarm", { hour: 7, minute: 30, days: ["mon", "fri"], label: "Gym", ...k })).toBeNull();
    expect(validateArguments("set_alarm", { hour: 24, minute: 0, ...k })).not.toBeNull();
    expect(validateArguments("set_alarm", { hour: 7, minute: 0, days: ["mon", "mon"], ...k })).not.toBeNull();
    expect(validateArguments("set_alarm", { hour: 7, minute: 0 })).not.toBeNull();
    expect(validateArguments("set_timer", { seconds: 600, ...k })).toBeNull();
    expect(validateArguments("set_timer", { seconds: 0, ...k })).not.toBeNull();
    expect(validateArguments("dismiss_alarm", { mode: "next" })).toBeNull();
    expect(validateArguments("dismiss_alarm", { mode: "time", hour: 7, minute: 0 })).toBeNull();
    expect(validateArguments("dismiss_alarm", { mode: "all" })).not.toBeNull();
    expect(validateArguments("dismiss_alarm", { mode: "next", hour: 1 })).not.toBeNull();
    expect(validateArguments("get_next_alarm", {})).toBeNull();
    expect(validateArguments("send_notification", { title: "Hi", message: "Drink water", priority: "high", ...k })).toBeNull();
    expect(validateArguments("send_notification", { title: "Hi", message: "x".repeat(501), ...k })).not.toBeNull();
    expect(validateArguments("speak", { message: "Dinner is ready", ...k })).toBeNull();
    expect(validateArguments("speak", { message: " ", ...k })).not.toBeNull();
  });
});
